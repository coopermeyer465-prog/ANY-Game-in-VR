package com.gamesinvr.questheadpose

import android.app.NativeActivity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.util.Collections

class OpenXrActivity : NativeActivity() {
    private val tag = "QuestHeadpose"
    private val activityScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var socket: Socket? = null
    private var reader: BufferedReader? = null
    private var writer: BufferedWriter? = null
    private var macPort: Int = 7007
    private var questIp: String = ""
    private var receiveJob: Job? = null
    private var handshakeJob: Job? = null
    private var streamJob: Job? = null
    private var yawOffset = 0f
    private var pitchOffset = 0f
    private var rollOffset = 0f
    private var lastYaw = 0f
    private var lastPitch = 0f
    private var lastUiUpdateNs = 0L
    private var lastPoseLogNs = 0L

    private val closeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            finish()
        }
    }

    private val controlReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_CONNECT_STREAMING -> {
                    val requestedIp = intent.getStringExtra(EXTRA_MAC_IP)?.trim().orEmpty()
                    val requestedPort = intent.getIntExtra(EXTRA_MAC_PORT, QuestPrefs.getMacPort(this@OpenXrActivity))
                    startOrReconnectNetworking(requestedIp, requestedPort)
                }

                ACTION_DISCONNECT_STREAMING -> {
                    shutdownNetworking(
                        reason = "Quest app disconnect",
                        sendReason = true,
                        clearConnected = true,
                    )
                }

                ACTION_RECENTER_STREAMING -> recenter()

                ACTION_SENSITIVITY_CHANGED -> {
                    val sensitivity = intent.getFloatExtra(
                        EXTRA_SENSITIVITY,
                        HeadposeRepository.state.value.sensitivity,
                    )
                    sendPacketAsync(buildSensitivityPayload(sensitivity))
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        registerReceiver(closeReceiver, IntentFilter(ACTION_CLOSE_OPENXR), RECEIVER_NOT_EXPORTED)
        registerReceiver(
            controlReceiver,
            IntentFilter().apply {
                addAction(ACTION_CONNECT_STREAMING)
                addAction(ACTION_DISCONNECT_STREAMING)
                addAction(ACTION_RECENTER_STREAMING)
                addAction(ACTION_SENSITIVITY_CHANGED)
            },
            RECEIVER_NOT_EXPORTED,
        )

        questIp = findQuestIp()
        val savedMacIp = QuestPrefs.getMacIp(this)
        val savedMacPort = QuestPrefs.getMacPort(this)
        val shouldConnect = QuestPrefs.getShouldConnect(this)
        Log.i(
            tag,
            "OpenXR onCreate immersive=true shouldConnect=$shouldConnect savedMacIp=$savedMacIp savedMacPort=$savedMacPort repoConnected=${HeadposeRepository.state.value.connected}",
        )
        HeadposeRepository.update { current ->
            current.copy(
                immersiveActive = true,
                questIp = questIp,
                sensorName = "OpenXR tracked head pose",
                openXrStatus = if (current.connected) {
                    "OpenXR activity launched; starting receiver link"
                } else {
                    "OpenXR active. Connect to the receiver to start streaming"
                },
            )
        }

        if (shouldConnect && savedMacIp.isNotBlank()) {
            HeadposeRepository.update {
                it.copy(
                    connected = true,
                    macIp = savedMacIp,
                    macPort = savedMacPort,
                )
            }
            startOrReconnectNetworking(savedMacIp, savedMacPort)
        }
    }

    override fun onDestroy() {
        unregisterReceiver(controlReceiver)
        unregisterReceiver(closeReceiver)
        shutdownNetworking(
            reason = "OpenXR closed. Connect again to resume streaming.",
            sendReason = true,
            clearConnected = true,
        )
        OpenXrPoseBridge.clearPose()
        HeadposeRepository.update {
            it.copy(
                immersiveActive = false,
                receiverAcknowledged = false,
                localPort = 0,
                yaw = 0f,
                pitch = 0f,
                roll = 0f,
                openXrStatus = "OpenXR only. Enter OpenXR to stream tracked head pose",
            )
        }
        activityScope.cancel()
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        super.onDestroy()
    }

    fun onNativeOpenXrReady(runtimeName: String) {
        Log.i(tag, "OpenXR runtime ready: $runtimeName")
        HeadposeRepository.update { current ->
            current.copy(
                immersiveActive = true,
                openXrStatus = "OpenXR session active: $runtimeName",
            )
        }
        val savedMacIp = QuestPrefs.getMacIp(this)
        val savedMacPort = QuestPrefs.getMacPort(this)
        if ((HeadposeRepository.state.value.connected || QuestPrefs.getShouldConnect(this)) &&
            socket == null &&
            savedMacIp.isNotBlank()
        ) {
            startOrReconnectNetworking(savedMacIp, savedMacPort)
        }
    }

    fun onNativeOpenXrPose(yaw: Float, pitch: Float, roll: Float) {
        OpenXrPoseBridge.updatePose(yaw, pitch, roll)
        val nowNs = SystemClock.elapsedRealtimeNanos()
        if (nowNs - lastPoseLogNs >= 1_000_000_000L) {
            lastPoseLogNs = nowNs
            Log.i(tag, "OpenXR pose callback yaw=$yaw pitch=$pitch roll=$roll")
        }
        if (nowNs - lastUiUpdateNs >= 100_000_000L) {
            lastUiUpdateNs = nowNs
            HeadposeRepository.update { current ->
                current.copy(
                    yaw = yaw,
                    pitch = pitch,
                    roll = roll,
                    openXrStatus = if (socket == null) {
                        "OpenXR pose live; waiting for receiver link"
                    } else {
                        "OpenXR pose callback active"
                    },
                )
            }
        }
    }

    fun onNativeOpenXrError(message: String) {
        Log.e(tag, "OpenXR error: $message")
        OpenXrPoseBridge.clearPose()
        shutdownNetworking(
            reason = "OpenXR launch failed: $message",
            sendReason = true,
            clearConnected = true,
        )
        HeadposeRepository.update {
            it.copy(
                immersiveActive = false,
                openXrStatus = "OpenXR error: $message",
                lastAckMessage = "OpenXR launch failed: $message",
            )
        }
        finish()
    }

    private fun startOrReconnectNetworking(
        requestedMacIp: String = HeadposeRepository.state.value.macIp.ifBlank { QuestPrefs.getMacIp(this) },
        requestedPort: Int = HeadposeRepository.state.value.macPort.takeIf { it > 0 } ?: QuestPrefs.getMacPort(this),
    ) {
        Log.i(tag, "OpenXR startOrReconnectNetworking macIp=$requestedMacIp port=$requestedPort")
        if (requestedMacIp.isBlank()) {
            HeadposeRepository.update {
                it.copy(
                    connected = false,
                    receiverAcknowledged = false,
                    lastAckMessage = "Enter the Mac IP before connecting",
                )
            }
            return
        }

        activityScope.launch {
            shutdownNetworking(reason = "Reconnecting", sendReason = false, clearConnected = false)

            try {
                val newSocket = Socket().apply {
                    connect(InetSocketAddress(requestedMacIp, requestedPort), 1500)
                    soTimeout = 1000
                    tcpNoDelay = true
                }
                val newReader = BufferedReader(InputStreamReader(newSocket.getInputStream(), Charsets.UTF_8))
                val newWriter = BufferedWriter(OutputStreamWriter(newSocket.getOutputStream(), Charsets.UTF_8))

                socket = newSocket
                reader = newReader
                writer = newWriter
                macPort = requestedPort
                questIp = findQuestIp()
                yawOffset = 0f
                pitchOffset = 0f
                rollOffset = 0f
                lastYaw = 0f
                lastPitch = 0f

                startReceiveLoop(newSocket, newReader)
                startHandshakeLoop()
                startPoseStreamLoop()
                HeadposeRepository.update {
                    it.copy(
                        connected = true,
                        receiverAcknowledged = true,
                        macIp = requestedMacIp,
                        macPort = requestedPort,
                        questIp = questIp,
                        localPort = newSocket.localPort,
                        sensorName = "OpenXR tracked head pose",
                        lastAckAtMs = System.currentTimeMillis(),
                        lastAckMessage = "Receiver link established",
                        openXrStatus = "OpenXR session active; receiver link established",
                    )
                }
                QuestPrefs.saveReceiverStatus(
                    this@OpenXrActivity,
                    acknowledged = true,
                    ackAtMs = System.currentTimeMillis(),
                    message = "Receiver link established",
                )
                sendPacket(buildHelloPayload())
                Log.i(tag, "OpenXR TCP sender connected to $requestedMacIp:$requestedPort from ${newSocket.localPort}")
            } catch (error: Exception) {
                Log.e(tag, "OpenXR TCP connect failed", error)
                shutdownNetworking(reason = "Connect failed", sendReason = false, clearConnected = true)
                HeadposeRepository.update {
                    it.copy(
                        connected = false,
                        receiverAcknowledged = false,
                        lastAckMessage = "Connect failed: ${error.message ?: error::class.java.simpleName}",
                    )
                }
                QuestPrefs.clearReceiverStatus(
                    this@OpenXrActivity,
                    message = "Connect failed: ${error.message ?: error::class.java.simpleName}",
                )
            }
        }
    }

    private fun startReceiveLoop(activeSocket: Socket, activeReader: BufferedReader) {
        receiveJob?.cancel()
        receiveJob = activityScope.launch {
            while (isActive && socket === activeSocket && !activeSocket.isClosed) {
                try {
                    val line = activeReader.readLine() ?: break
                    handleIncoming(line)
                } catch (_: java.net.SocketTimeoutException) {
                    // Keep polling so disconnect stays responsive.
                } catch (error: Exception) {
                    Log.w(tag, "TCP receive loop error", error)
                }
            }
        }
    }

    private fun startHandshakeLoop() {
        handshakeJob?.cancel()
        handshakeJob = activityScope.launch {
            while (
                isActive &&
                socket != null &&
                socket?.isClosed == false &&
                !HeadposeRepository.state.value.receiverAcknowledged
            ) {
                try {
                    sendPacket(buildHelloPayload())
                } catch (error: Exception) {
                    Log.w(tag, "Hello retry failed", error)
                }
                delay(1000)
            }
        }
    }

    private fun startPoseStreamLoop() {
        streamJob?.cancel()
        streamJob = activityScope.launch {
            while (isActive && socket != null && socket?.isClosed == false) {
                val pose = OpenXrPoseBridge.latestRecentPose(250_000_000L)
                if (pose == null) {
                    reportOpenXrInactive()
                } else {
                    sendCurrentPose(pose)
                }
                delay(8)
            }
        }
    }

    private fun handleIncoming(raw: String) {
        Log.i(tag, "OpenXR received receiver status: $raw")
        val payload = try {
            JSONObject(raw)
        } catch (_: Exception) {
            return
        }
        if (payload.optString("type") != "status") {
            return
        }
        if (payload.optBoolean("receiverRunning", false)) {
            handshakeJob?.cancel()
            handshakeJob = null
        }
        val ackAtMs = System.currentTimeMillis()
        val acknowledged = payload.optBoolean("receiverRunning", false)
        val message = payload.optString("message", "Receiver online")
        QuestPrefs.saveReceiverStatus(
            this,
            acknowledged = acknowledged,
            ackAtMs = ackAtMs,
            message = message,
        )
        HeadposeRepository.update {
            it.copy(
                receiverAcknowledged = acknowledged,
                lastAckAtMs = ackAtMs,
                lastAckMessage = message,
            )
        }
    }

    private fun reportOpenXrInactive() {
        val nowNs = SystemClock.elapsedRealtimeNanos()
        if (nowNs - lastUiUpdateNs < 100_000_000L) {
            return
        }
        lastUiUpdateNs = nowNs
        HeadposeRepository.update {
            it.copy(
                questIp = questIp.ifBlank { findQuestIp() },
                localPort = socket?.localPort ?: 0,
                openXrStatus = "OpenXR session starting or paused",
            )
        }
    }

    private fun sendCurrentPose(frame: OpenXrPoseFrame) {
        val yaw = normalizeAngle(frame.yaw - yawOffset)
        val pitch = normalizeAngle(frame.pitch - pitchOffset)
        val roll = normalizeAngle(frame.roll - rollOffset)
        val yawDelta = normalizeAngle(yaw - lastYaw)
        val pitchDelta = normalizeAngle(pitch - lastPitch)

        lastYaw = yaw
        lastPitch = pitch

        val nowNs = SystemClock.elapsedRealtimeNanos()
        if (nowNs - lastUiUpdateNs >= 100_000_000L) {
            lastUiUpdateNs = nowNs
            HeadposeRepository.update {
                it.copy(
                    yaw = yaw,
                    pitch = pitch,
                    roll = roll,
                    lastPacketAtMs = System.currentTimeMillis(),
                    receiverAcknowledged = true,
                    questIp = questIp.ifBlank { findQuestIp() },
                    localPort = socket?.localPort ?: 0,
                    sensorName = "OpenXR tracked head pose",
                    openXrStatus = "OpenXR tracked head pose active",
                )
            }
        }

        val payload = JSONObject()
            .put("type", "headpose")
            .put("mode", "openxr")
            .put("questIp", questIp.ifBlank { findQuestIp() })
            .put("questPort", socket?.localPort ?: 0)
            .put("sensitivity", HeadposeRepository.state.value.sensitivity.toDouble())
            .put("yaw", yaw.toDouble())
            .put("pitch", pitch.toDouble())
            .put("roll", roll.toDouble())
            .put("yawDelta", yawDelta.toDouble())
            .put("pitchDelta", pitchDelta.toDouble())
            .put("tsMs", System.currentTimeMillis())
            .toString()

        try {
            sendPacket(payload)
        } catch (error: Exception) {
            Log.e(tag, "OpenXR TCP send failed", error)
            HeadposeRepository.update {
                it.copy(lastAckMessage = "Pose send failed: ${error.message ?: error::class.java.simpleName}")
            }
        }
    }

    private fun recenter() {
        val current = HeadposeRepository.state.value
        yawOffset += current.yaw
        pitchOffset += current.pitch
        rollOffset += current.roll
        lastYaw = 0f
        lastPitch = 0f
        HeadposeRepository.update {
            it.copy(lastAckMessage = "Recentered at current tracked head pose")
        }
        sendPacketAsync(buildRecenterPayload())
    }

    private fun sendPacket(payload: String) {
        val activeWriter = writer ?: return
        activeWriter.write(payload)
        activeWriter.newLine()
        activeWriter.flush()
    }

    private fun sendPacketAsync(payload: String) {
        activityScope.launch {
            try {
                sendPacket(payload)
            } catch (error: Exception) {
                Log.e(tag, "OpenXR TCP async send failed", error)
            }
        }
    }

    private fun shutdownNetworking(
        reason: String,
        sendReason: Boolean,
        clearConnected: Boolean,
    ) {
        val activeSocket = socket

        if (sendReason && activeSocket != null && !activeSocket.isClosed) {
            try {
                sendPacket(
                    JSONObject()
                        .put("type", "disconnect")
                        .put("reason", reason)
                        .put("questIp", questIp.ifBlank { findQuestIp() })
                        .toString(),
                )
            } catch (_: Exception) {
                // Best effort.
            }
        }

        receiveJob?.cancel()
        handshakeJob?.cancel()
        streamJob?.cancel()
        receiveJob = null
        handshakeJob = null
        streamJob = null
        try {
            reader?.close()
        } catch (_: Exception) {
        }
        try {
            writer?.close()
        } catch (_: Exception) {
        }
        try {
            activeSocket?.close()
        } catch (_: Exception) {
        }
        socket = null
        reader = null
        writer = null
        lastYaw = 0f
        lastPitch = 0f
        Log.i(tag, "OpenXR networking shut down: $reason clearConnected=$clearConnected")
        if (clearConnected) {
            QuestPrefs.clearReceiverStatus(this, reason)
        }
        HeadposeRepository.update { current ->
            current.copy(
                connected = if (clearConnected) false else current.connected,
                receiverAcknowledged = false,
                localPort = 0,
                lastAckMessage = reason,
            )
        }
    }

    private fun buildHelloPayload(): String {
        val current = HeadposeRepository.state.value
        return JSONObject()
            .put("type", "hello")
            .put("questIp", questIp.ifBlank { findQuestIp() })
            .put("questPort", socket?.localPort ?: current.localPort)
            .put("sensor", "OpenXR tracked head pose")
            .put("openxr", current.openXrStatus)
            .put("sensitivity", current.sensitivity.toDouble())
            .toString()
    }

    private fun buildSensitivityPayload(sensitivity: Float): String {
        return JSONObject()
            .put("type", "set_sensitivity")
            .put("questIp", questIp.ifBlank { findQuestIp() })
            .put("sensitivity", sensitivity.toDouble())
            .toString()
    }

    private fun buildRecenterPayload(): String {
        return JSONObject()
            .put("type", "recenter")
            .put("questIp", questIp.ifBlank { findQuestIp() })
            .toString()
    }

    private fun findQuestIp(): String {
        return try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            interfaces.asSequence()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { Collections.list(it.inetAddresses).asSequence() }
                .filterIsInstance<Inet4Address>()
                .firstOrNull { !it.isLoopbackAddress && !it.hostAddress.isNullOrBlank() }
                ?.hostAddress
                ?: ""
        } catch (_: Exception) {
            ""
        }
    }

    private fun normalizeAngle(value: Float): Float {
        var normalized = value
        while (normalized > 180f) {
            normalized -= 360f
        }
        while (normalized < -180f) {
            normalized += 360f
        }
        return normalized
    }

    companion object {
        private const val ACTION_CLOSE_OPENXR = "com.gamesinvr.questheadpose.CLOSE_OPENXR"
        private const val ACTION_OPENXR = "com.gamesinvr.questheadpose.OPENXR"
        private const val IMMERSIVE_HMD_CATEGORY = "org.khronos.openxr.intent.category.IMMERSIVE_HMD"
        private const val ACTION_CONNECT_STREAMING = "com.gamesinvr.questheadpose.CONNECT_STREAMING"
        private const val ACTION_DISCONNECT_STREAMING = "com.gamesinvr.questheadpose.DISCONNECT_STREAMING"
        private const val ACTION_RECENTER_STREAMING = "com.gamesinvr.questheadpose.RECENTER_STREAMING"
        private const val ACTION_SENSITIVITY_CHANGED = "com.gamesinvr.questheadpose.SENSITIVITY_CHANGED"
        private const val EXTRA_MAC_IP = "mac_ip"
        private const val EXTRA_MAC_PORT = "mac_port"
        private const val EXTRA_SENSITIVITY = "sensitivity"

        fun launch(context: Context) {
            context.startActivity(
                Intent(context, OpenXrActivity::class.java)
                    .setAction(ACTION_OPENXR)
                    .addCategory(IMMERSIVE_HMD_CATEGORY)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }

        fun closeActive(context: Context) {
            context.sendBroadcast(Intent(ACTION_CLOSE_OPENXR).setPackage(context.packageName))
        }

        fun requestConnect(context: Context, macIp: String, macPort: Int) {
            context.sendBroadcast(
                Intent(ACTION_CONNECT_STREAMING)
                    .setPackage(context.packageName)
                    .putExtra(EXTRA_MAC_IP, macIp)
                    .putExtra(EXTRA_MAC_PORT, macPort),
            )
        }

        fun requestDisconnect(context: Context) {
            context.sendBroadcast(Intent(ACTION_DISCONNECT_STREAMING).setPackage(context.packageName))
        }

        fun requestRecenter(context: Context) {
            context.sendBroadcast(Intent(ACTION_RECENTER_STREAMING).setPackage(context.packageName))
        }

        fun notifySensitivityChanged(context: Context, sensitivity: Float) {
            context.sendBroadcast(
                Intent(ACTION_SENSITIVITY_CHANGED)
                    .setPackage(context.packageName)
                    .putExtra(EXTRA_SENSITIVITY, sensitivity),
            )
        }
    }
}
