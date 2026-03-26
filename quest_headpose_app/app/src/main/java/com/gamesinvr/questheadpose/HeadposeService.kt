package com.gamesinvr.questheadpose

import android.app.Service
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.Collections
import kotlin.math.asin
import kotlin.math.atan2

class HeadposeService : Service(), SensorEventListener {
    private val tag = "QuestHeadpose"
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private lateinit var sensorManager: SensorManager
    private var sensor: Sensor? = null
    private var socket: DatagramSocket? = null
    private var receiveJob: Job? = null
    private var macAddress: InetAddress? = null
    private var macPort: Int = 7007
    private var yawOffset = 0f
    private var pitchOffset = 0f
    private var rollOffset = 0f
    private var lastYaw = 0f
    private var lastPitch = 0f
    private var lastSendNs = 0L

    override fun onCreate() {
        super.onCreate()
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        sensor = sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
            ?: sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

        HeadposeRepository.update {
            it.copy(
                openXrStatus = "OpenXR native immersive available; window mode uses sensor streaming",
                sensorName = sensor?.name ?: "No rotation vector sensor",
            )
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CONNECT -> {
                val requestedIp = intent.getStringExtra(EXTRA_MAC_IP)?.trim().orEmpty()
                val requestedPort = intent.getIntExtra(EXTRA_MAC_PORT, 7007)
                connect(requestedIp, requestedPort)
            }

            ACTION_DISCONNECT -> {
                disconnect("Quest app disconnect")
                stopSelf()
            }

            ACTION_RECENTER -> recenter()
        }
        return START_NOT_STICKY
    }

    private fun connect(macIp: String, port: Int) {
        if (macIp.isBlank()) {
            HeadposeRepository.update { it.copy(lastAckMessage = "Enter the Mac IP before connecting") }
            return
        }

        disconnect("Reconnecting")

        try {
            val newSocket = DatagramSocket()
            newSocket.soTimeout = 1000
            socket = newSocket
            macAddress = InetAddress.getByName(macIp)
            macPort = port
            yawOffset = 0f
            pitchOffset = 0f
            rollOffset = 0f
            lastYaw = 0f
            lastPitch = 0f
            lastSendNs = 0L
            sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME)
            startReceiveLoop(newSocket)
            HeadposeRepository.update {
                it.copy(
                    connected = true,
                    receiverAcknowledged = false,
                    macIp = macIp,
                    macPort = port,
                    questIp = findQuestIp(),
                    localPort = newSocket.localPort,
                    lastAckMessage = "Streaming to receiver",
                )
            }
            sendPacket(buildHelloPayload())
            Log.i(tag, "Connected to receiver at $macIp:$port from local UDP ${newSocket.localPort}")
        } catch (error: Exception) {
            Log.e(tag, "Connect failed", error)
            HeadposeRepository.update {
                it.copy(
                    connected = false,
                    receiverAcknowledged = false,
                    lastAckMessage = "Connect failed: ${error.message ?: error::class.java.simpleName}",
                )
            }
        }
    }

    private fun startReceiveLoop(activeSocket: DatagramSocket) {
        receiveJob?.cancel()
        receiveJob = serviceScope.launch {
            val buffer = ByteArray(4096)
            while (isActive && socket === activeSocket && !activeSocket.isClosed) {
                try {
                    val packet = DatagramPacket(buffer, buffer.size)
                    activeSocket.receive(packet)
                    handleIncoming(String(packet.data, 0, packet.length))
                } catch (_: java.net.SocketTimeoutException) {
                    // Polling timeout keeps the coroutine responsive to disconnect.
                } catch (_: Exception) {
                    // Ignore malformed or transient packets.
                }
            }
        }
    }

    private fun handleIncoming(raw: String) {
        val payload = JSONObject(raw)
        if (payload.optString("type") != "status") {
            return
        }
        val acknowledged = payload.optBoolean("receiverRunning", false)
        val newSensitivity = payload.optDouble("sensitivity", 18.0).toFloat()
        HeadposeRepository.update {
            it.copy(
                receiverAcknowledged = acknowledged,
                sensitivity = newSensitivity,
                lastAckAtMs = System.currentTimeMillis(),
                lastAckMessage = payload.optString("message", "Receiver online"),
            )
        }
    }

    private fun disconnect(reason: String) {
        try {
            sendPacket(
                JSONObject()
                    .put("type", "disconnect")
                    .put("reason", reason)
                    .put("questIp", findQuestIp())
                    .toString(),
            )
        } catch (_: Exception) {
            // Best effort.
        }

        sensorManager.unregisterListener(this)
        receiveJob?.cancel()
        receiveJob = null
        socket?.close()
        socket = null
        macAddress = null
        HeadposeRepository.update {
            it.copy(
                connected = false,
                receiverAcknowledged = false,
                localPort = 0,
                lastAckMessage = reason,
            )
        }
    }

    private fun recenter() {
        val current = HeadposeRepository.state.value
        yawOffset += current.yaw
        pitchOffset += current.pitch
        rollOffset += current.roll
        HeadposeRepository.update { it.copy(lastAckMessage = "Recentered at current head pose") }
        Log.i(tag, "Recentering head pose")
    }

    override fun onDestroy() {
        disconnect("Quest app stopped")
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        disconnect("Quest app task removed")
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onSensorChanged(event: SensorEvent?) {
        val packetSocket = socket ?: return
        val currentMac = macAddress ?: return
        val vector = event?.values ?: return
        if (vector.size < 4) {
            return
        }

        val nowNs = System.nanoTime()
        if (nowNs - lastSendNs < 16_000_000L) {
            return
        }
        lastSendNs = nowNs

        val x = vector[0]
        val y = vector[1]
        val z = vector[2]
        val w = vector[3]

        val yawDeg = Math.toDegrees(atan2(2.0 * (w * y + x * z), 1.0 - 2.0 * (y * y + z * z)).toDouble()).toFloat() - yawOffset
        val pitchDeg = Math.toDegrees(asin((2.0 * (w * x - z * y)).coerceIn(-1.0, 1.0))).toFloat() - pitchOffset
        val rollDeg = Math.toDegrees(atan2(2.0 * (w * z + x * y), 1.0 - 2.0 * (x * x + z * z)).toDouble()).toFloat() - rollOffset

        val normalizedYaw = normalizeAngle(yawDeg)
        val normalizedPitch = normalizeAngle(pitchDeg)
        val normalizedRoll = normalizeAngle(rollDeg)
        val yawDelta = normalizeAngle(normalizedYaw - lastYaw)
        val pitchDelta = normalizeAngle(normalizedPitch - lastPitch)

        lastYaw = normalizedYaw
        lastPitch = normalizedPitch

        HeadposeRepository.update {
            it.copy(
                yaw = normalizedYaw,
                pitch = normalizedPitch,
                roll = normalizedRoll,
                yawVelocity = yawDelta,
                pitchVelocity = pitchDelta,
                lastPacketAtMs = System.currentTimeMillis(),
                questIp = findQuestIp(),
                localPort = packetSocket.localPort,
            )
        }

        val payload = JSONObject()
            .put("type", "headpose")
            .put("mode", if (HeadposeRepository.state.value.immersiveActive) "openxr" else "window")
            .put("questIp", findQuestIp())
            .put("questPort", packetSocket.localPort)
            .put("yaw", normalizedYaw.toDouble())
            .put("pitch", normalizedPitch.toDouble())
            .put("roll", normalizedRoll.toDouble())
            .put("yawDelta", yawDelta.toDouble())
            .put("pitchDelta", pitchDelta.toDouble())
            .put("tsMs", System.currentTimeMillis())
            .toString()

        sendPacket(payload, currentMac, macPort)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun sendPacket(payload: String, address: InetAddress? = macAddress, port: Int = macPort) {
        val resolvedAddress = address ?: return
        val bytes = payload.toByteArray(Charsets.UTF_8)
        val packet = DatagramPacket(bytes, bytes.size, resolvedAddress, port)
        socket?.send(packet)
    }

    private fun buildHelloPayload(): String {
        val currentState = HeadposeRepository.state.value
        return JSONObject()
            .put("type", "hello")
            .put("questIp", findQuestIp())
            .put("questPort", currentState.localPort)
            .put("sensor", currentState.sensorName)
            .put("openxr", currentState.openXrStatus)
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
        const val ACTION_CONNECT = "com.gamesinvr.questheadpose.CONNECT"
        const val ACTION_DISCONNECT = "com.gamesinvr.questheadpose.DISCONNECT"
        const val ACTION_RECENTER = "com.gamesinvr.questheadpose.RECENTER"
        const val EXTRA_MAC_IP = "mac_ip"
        const val EXTRA_MAC_PORT = "mac_port"
    }
}
