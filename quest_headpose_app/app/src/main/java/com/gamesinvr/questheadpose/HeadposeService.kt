package com.gamesinvr.questheadpose

import android.app.Service
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.Collections
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt

class HeadposeService : Service(), SensorEventListener {
    private val tag = "QuestHeadpose"
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private lateinit var sensorManager: SensorManager
    private var sensor: Sensor? = null
    private var sensorThread: HandlerThread? = null
    private var sensorHandler: Handler? = null
    private var socket: DatagramSocket? = null
    private var receiveJob: Job? = null
    private var handshakeJob: Job? = null
    private var macAddress: InetAddress? = null
    private var macPort: Int = 7007
    private var yawOffset = 0f
    private var pitchOffset = 0f
    private var rollOffset = 0f
    private var lastYaw = 0f
    private var lastPitch = 0f
    private var lastSendNs = 0L
    private var lastSensorTimestampNs = 0L
    private var imuIntegratedYaw = 0f
    private var imuIntegratedPitch = 0f
    private var imuIntegratedRoll = 0f
    private var poseSensorMode = PoseSensorMode.NONE
    private var lastSensorDebugMessage = ""
    private var cachedQuestIp = ""
    private var cachedSensorDescription = "Unavailable"
    private var lastUiUpdateNs = 0L
    private var lastRawImuLogNs = 0L
    private var lastImuLayout = "unknown"
    private var questImuInterpretation = QuestImuInterpretation.AUTO

    override fun onCreate() {
        super.onCreate()
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        sensorThread = HandlerThread("QuestHeadposeSensors").also { thread ->
            thread.start()
            sensorHandler = Handler(thread.looper)
        }
        sensor = selectPoseSensor().also {
            poseSensorMode = poseSensorModeFor(it)
        }
        cachedQuestIp = findQuestIp()
        cachedSensorDescription = describeSensor(sensor, poseSensorMode)
        val savedSensitivity = QuestPrefs.getSensitivity(this)

        HeadposeRepository.update {
            it.copy(
                openXrStatus = "OpenXR native immersive available; window mode uses sensor streaming",
                sensorName = cachedSensorDescription,
                sensitivity = savedSensitivity,
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

            ACTION_SET_SENSITIVITY -> {
                val requestedSensitivity = intent.getFloatExtra(
                    EXTRA_SENSITIVITY,
                    HeadposeRepository.state.value.sensitivity,
                )
                updateSensitivity(requestedSensitivity)
            }
        }
        return START_NOT_STICKY
    }

    private fun connect(macIp: String, port: Int) {
        if (macIp.isBlank()) {
            HeadposeRepository.update { it.copy(lastAckMessage = "Enter the Mac IP before connecting") }
            return
        }
        if (sensor == null || poseSensorMode == PoseSensorMode.NONE) {
            HeadposeRepository.update {
                it.copy(
                    connected = false,
                    receiverAcknowledged = false,
                    lastAckMessage = "No supported Quest head-tracking sensor found",
                    sensorName = describeSensor(sensor, poseSensorMode),
                )
            }
            return
        }

        serviceScope.launch {
            disconnectInternal("Reconnecting", sendReason = false)

            try {
                val newSocket = DatagramSocket().apply { soTimeout = 1000 }
                val resolvedMacAddress = InetAddress.getByName(macIp)

                socket = newSocket
                macAddress = resolvedMacAddress
                macPort = port
                yawOffset = 0f
                pitchOffset = 0f
                rollOffset = 0f
                lastYaw = 0f
                lastPitch = 0f
                lastSendNs = 0L
                lastSensorTimestampNs = 0L
                imuIntegratedYaw = 0f
                imuIntegratedPitch = 0f
                imuIntegratedRoll = 0f
                lastSensorDebugMessage = ""
                lastUiUpdateNs = 0L
                lastRawImuLogNs = 0L
                lastImuLayout = "unknown"
                questImuInterpretation = QuestImuInterpretation.AUTO
                cachedQuestIp = findQuestIp()
                cachedSensorDescription = describeSensor(sensor, poseSensorMode)

                val activeSensor = sensor ?: throw IllegalStateException("Pose sensor unavailable")
                val registered = sensorManager.registerListener(
                    this@HeadposeService,
                    activeSensor,
                    SensorManager.SENSOR_DELAY_GAME,
                    sensorHandler,
                )
                if (!registered) {
                    throw IllegalStateException("Could not register pose sensor listener")
                }

                startReceiveLoop(newSocket)
                startHandshakeLoop(newSocket, resolvedMacAddress, port)
                HeadposeRepository.update {
                    it.copy(
                        connected = true,
                        receiverAcknowledged = false,
                        macIp = macIp,
                        macPort = port,
                        questIp = cachedQuestIp,
                        localPort = newSocket.localPort,
                        sensorName = cachedSensorDescription,
                        lastAckMessage = "Connecting to receiver...",
                    )
                }
                sendPacket(buildHelloPayload(), resolvedMacAddress, port)
                Log.i(tag, "Connected to receiver at $macIp:$port from local UDP ${newSocket.localPort}")
            } catch (error: Exception) {
                Log.e(tag, "Connect failed", error)
                disconnectInternal("Connect failed", sendReason = false)
                HeadposeRepository.update {
                    it.copy(
                        connected = false,
                        receiverAcknowledged = false,
                        lastAckMessage = "Connect failed: ${error.message ?: error::class.java.simpleName}",
                    )
                }
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

    private fun startHandshakeLoop(
        activeSocket: DatagramSocket,
        resolvedMacAddress: InetAddress,
        port: Int,
    ) {
        handshakeJob?.cancel()
        handshakeJob = serviceScope.launch {
            while (
                isActive &&
                socket === activeSocket &&
                !activeSocket.isClosed &&
                !HeadposeRepository.state.value.receiverAcknowledged
            ) {
                try {
                    sendPacket(buildHelloPayload(), resolvedMacAddress, port)
                } catch (_: Exception) {
                    // Retry on next loop iteration.
                }
                delay(1000)
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
        if (acknowledged) {
            handshakeJob?.cancel()
            handshakeJob = null
        }
        HeadposeRepository.update {
            it.copy(
                receiverAcknowledged = acknowledged,
                sensitivity = newSensitivity,
                lastAckAtMs = System.currentTimeMillis(),
                lastAckMessage = payload.optString("message", "Receiver online"),
            )
        }
    }

    private fun disconnect(reason: String, sendReason: Boolean = true) {
        serviceScope.launch {
            disconnectInternal(reason, sendReason)
        }
    }

    private suspend fun disconnectInternal(reason: String, sendReason: Boolean) {
        val activeSocket = socket
        val activeAddress = macAddress
        val activePort = macPort

        if (sendReason && activeSocket != null && activeAddress != null && !activeSocket.isClosed) {
            try {
                sendPacket(
                    JSONObject()
                        .put("type", "disconnect")
                        .put("reason", reason)
                        .put("questIp", cachedQuestIp.ifBlank { findQuestIp() })
                        .toString(),
                    activeAddress,
                    activePort,
                )
            } catch (_: Exception) {
                // Best effort.
            }
        }

        sensorManager.unregisterListener(this@HeadposeService)
        receiveJob?.cancel()
        receiveJob = null
        handshakeJob?.cancel()
        handshakeJob = null
        activeSocket?.close()
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

    private fun updateSensitivity(value: Float) {
        val sanitizedValue = value.coerceAtLeast(1f)
        HeadposeRepository.update { current ->
            current.copy(
                sensitivity = sanitizedValue,
                lastAckMessage = if (current.connected) {
                    "Updating sensitivity to ${sanitizedValue.toInt()}"
                } else {
                    "Sensitivity preset saved"
                },
            )
        }
        if (socket != null && macAddress != null) {
            sendPacketAsync(buildSensitivityPayload(sanitizedValue))
        }
    }

    override fun onDestroy() {
        sensorManager.unregisterListener(this)
        receiveJob?.cancel()
        handshakeJob?.cancel()
        socket?.close()
        socket = null
        macAddress = null
        sensorThread?.quitSafely()
        sensorThread = null
        sensorHandler = null
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
        val sensorEvent = event ?: return
        val values = sensorEvent.values ?: return
        val pose = decodePose(sensorEvent.sensor, values, sensorEvent.timestamp) ?: run {
            maybeReportUnsupportedSensor(sensorEvent.sensor, values)
            return
        }

        val nowNs = System.nanoTime()
        if (nowNs - lastSendNs < 16_000_000L) {
            return
        }
        lastSendNs = nowNs

        val yawDeg = pose.yaw - yawOffset
        val pitchDeg = pose.pitch - pitchOffset
        val rollDeg = pose.roll - rollOffset

        val normalizedYaw = normalizeAngle(yawDeg)
        val normalizedPitch = normalizeAngle(pitchDeg)
        val normalizedRoll = normalizeAngle(rollDeg)
        val yawDelta = normalizeAngle(normalizedYaw - lastYaw)
        val pitchDelta = normalizeAngle(normalizedPitch - lastPitch)

        lastYaw = normalizedYaw
        lastPitch = normalizedPitch

        if (cachedQuestIp.isBlank()) {
            cachedQuestIp = findQuestIp()
        }
        val sensorDescription = cachedSensorDescription.ifBlank {
            describeSensor(sensorEvent.sensor, poseSensorMode)
        }
        if (nowNs - lastUiUpdateNs >= 100_000_000L) {
            lastUiUpdateNs = nowNs
            HeadposeRepository.update { current ->
                current.copy(
                    yaw = normalizedYaw,
                    pitch = normalizedPitch,
                    roll = normalizedRoll,
                    yawVelocity = yawDelta,
                    pitchVelocity = pitchDelta,
                    lastPacketAtMs = System.currentTimeMillis(),
                    questIp = cachedQuestIp,
                    localPort = packetSocket.localPort,
                    sensorName = sensorDescription,
                )
            }
        }

        val currentState = HeadposeRepository.state.value
        val payload = JSONObject()
            .put("type", "headpose")
            .put("mode", if (currentState.immersiveActive) "openxr" else "window")
            .put("questIp", cachedQuestIp)
            .put("questPort", packetSocket.localPort)
            .put("sensitivity", currentState.sensitivity.toDouble())
            .put("yaw", normalizedYaw.toDouble())
            .put("pitch", normalizedPitch.toDouble())
            .put("roll", normalizedRoll.toDouble())
            .put("yawDelta", yawDelta.toDouble())
            .put("pitchDelta", pitchDelta.toDouble())
            .put("tsMs", System.currentTimeMillis())
            .toString()

        sendPacketAsync(payload, currentMac, macPort)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun sendPacket(payload: String, address: InetAddress? = macAddress, port: Int = macPort) {
        val resolvedAddress = address ?: return
        val bytes = payload.toByteArray(Charsets.UTF_8)
        val packet = DatagramPacket(bytes, bytes.size, resolvedAddress, port)
        socket?.send(packet)
    }

    private fun sendPacketAsync(payload: String, address: InetAddress? = macAddress, port: Int = macPort) {
        serviceScope.launch {
            try {
                sendPacket(payload, address, port)
            } catch (_: Exception) {
                // Best effort for live pose streaming. Connect/disconnect paths already report errors.
            }
        }
    }

    private fun buildHelloPayload(): String {
        val currentState = HeadposeRepository.state.value
        return JSONObject()
            .put("type", "hello")
            .put("questIp", cachedQuestIp.ifBlank { findQuestIp() })
            .put("questPort", socket?.localPort ?: currentState.localPort)
            .put("sensor", cachedSensorDescription.ifBlank { currentState.sensorName })
            .put("openxr", currentState.openXrStatus)
            .put("sensitivity", currentState.sensitivity.toDouble())
            .toString()
    }

    private fun buildSensitivityPayload(sensitivity: Float): String {
        return JSONObject()
            .put("type", "set_sensitivity")
            .put("questIp", cachedQuestIp.ifBlank { findQuestIp() })
            .put("sensitivity", sensitivity.toDouble())
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

    private fun selectPoseSensor(): Sensor? {
        val sensors = sensorManager.getSensorList(Sensor.TYPE_ALL)
        return sensors.firstOrNull { it.type == HEAD_TRACKER_TYPE }
            ?: sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
            ?: sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
            ?: sensors.firstOrNull { it.type == QUEST_HMD_IMU_TYPE }
            ?: sensors.firstOrNull { it.stringType?.contains("head_tracker", ignoreCase = true) == true }
            ?: sensors.firstOrNull { it.stringType?.contains("hmd_imu", ignoreCase = true) == true }
            ?: sensors.firstOrNull { it.name.contains("HMD IMU", ignoreCase = true) }
    }

    private fun poseSensorModeFor(selectedSensor: Sensor?): PoseSensorMode {
        if (selectedSensor == null) {
            return PoseSensorMode.NONE
        }
        return when {
            selectedSensor.type == HEAD_TRACKER_TYPE -> PoseSensorMode.ROTATION_VECTOR
            selectedSensor.type == Sensor.TYPE_GAME_ROTATION_VECTOR -> PoseSensorMode.ROTATION_VECTOR
            selectedSensor.type == Sensor.TYPE_ROTATION_VECTOR -> PoseSensorMode.ROTATION_VECTOR
            selectedSensor.type == QUEST_HMD_IMU_TYPE -> PoseSensorMode.QUEST_IMU
            selectedSensor.stringType?.contains("hmd_imu", ignoreCase = true) == true -> PoseSensorMode.QUEST_IMU
            else -> PoseSensorMode.ROTATION_VECTOR
        }
    }

    private fun describeSensor(selectedSensor: Sensor?, mode: PoseSensorMode): String {
        if (selectedSensor == null) {
            return "No pose sensor"
        }
        return when (mode) {
            PoseSensorMode.NONE -> selectedSensor.name
            PoseSensorMode.ROTATION_VECTOR -> "Rotation sensor"
            PoseSensorMode.QUEST_IMU -> "HMD IMU"
        }
    }

    private fun decodePose(activeSensor: Sensor, values: FloatArray, eventTimestampNs: Long): Pose? {
        return when (poseSensorModeFor(activeSensor)) {
            PoseSensorMode.ROTATION_VECTOR -> decodeRotationVectorPose(values)
            PoseSensorMode.QUEST_IMU -> decodeQuestImuPose(values, eventTimestampNs)
            PoseSensorMode.NONE -> null
        }
    }

    private fun decodeRotationVectorPose(values: FloatArray): Pose? {
        if (values.size < 3) {
            return null
        }
        val x = values[0]
        val y = values[1]
        val z = values[2]
        val w = if (values.size >= 4) {
            values[3]
        } else {
            sqrt(max(0f, 1f - x * x - y * y - z * z))
        }
        val yaw = Math.toDegrees(atan2(2.0 * (w * y + x * z), 1.0 - 2.0 * (y * y + z * z)).toDouble()).toFloat()
        val pitch = Math.toDegrees(asin((2.0 * (w * x - z * y)).coerceIn(-1.0, 1.0))).toFloat()
        val roll = Math.toDegrees(atan2(2.0 * (w * z + x * y), 1.0 - 2.0 * (x * x + z * z)).toDouble()).toFloat()
        return Pose(yaw = yaw, pitch = pitch, roll = roll)
    }

    private fun decodeQuestImuPose(values: FloatArray, eventTimestampNs: Long): Pose? {
        if (values.size < 3) {
            return null
        }

        if (questImuInterpretation == QuestImuInterpretation.AUTO && looksLikeDirectEuler(values)) {
            questImuInterpretation = QuestImuInterpretation.DIRECT_EULER
            Log.i(tag, "Quest IMU switched to direct Euler interpretation")
        }

        if (questImuInterpretation == QuestImuInterpretation.DIRECT_EULER) {
            return decodeQuestEulerPose(values)
        }

        val imuSample = parseQuestImuSample(values) ?: return null
        maybeLogRawImu(values, imuSample, eventTimestampNs)

        val dtSeconds = when {
            lastSensorTimestampNs == 0L || eventTimestampNs <= lastSensorTimestampNs -> 0f
            else -> ((eventTimestampNs - lastSensorTimestampNs).coerceAtMost(100_000_000L)).toFloat() / 1_000_000_000f
        }
        lastSensorTimestampNs = eventTimestampNs

        val gyroX = imuSample.gyro[0]
        val gyroY = imuSample.gyro[1]
        val gyroZ = imuSample.gyro[2]

        imuIntegratedPitch = normalizeAngle(imuIntegratedPitch + Math.toDegrees((-gyroX * dtSeconds).toDouble()).toFloat())
        imuIntegratedYaw = normalizeAngle(imuIntegratedYaw + Math.toDegrees((-gyroY * dtSeconds).toDouble()).toFloat())
        imuIntegratedRoll = normalizeAngle(imuIntegratedRoll + Math.toDegrees((-gyroZ * dtSeconds).toDouble()).toFloat())

        imuSample.accel?.let { accel ->
            val accelX = accel[0]
            val accelY = accel[1]
            val accelZ = accel[2]
            val accelNorm = sqrt(accelX.pow(2) + accelY.pow(2) + accelZ.pow(2))
            if (accelNorm > 0.01f) {
                val normX = accelX / accelNorm
                val normY = accelY / accelNorm
                val normZ = accelZ / accelNorm
                val accelPitch = Math.toDegrees(atan2((-normX).toDouble(), sqrt((normY * normY + normZ * normZ).toDouble()))).toFloat()
                val accelRoll = Math.toDegrees(atan2(normY.toDouble(), normZ.toDouble())).toFloat()
                if (dtSeconds == 0f) {
                    imuIntegratedPitch = accelPitch
                    imuIntegratedRoll = accelRoll
                } else {
                    imuIntegratedPitch = normalizeAngle(imuIntegratedPitch * 0.98f + accelPitch * 0.02f)
                    imuIntegratedRoll = normalizeAngle(imuIntegratedRoll * 0.98f + accelRoll * 0.02f)
                }
            }
        }

        return Pose(
            yaw = imuIntegratedYaw,
            pitch = imuIntegratedPitch,
            roll = imuIntegratedRoll,
        )
    }

    private fun decodeQuestEulerPose(values: FloatArray): Pose? {
        if (values.size < 3) {
            return null
        }
        return Pose(
            yaw = normalizeAngle(values[0]),
            pitch = normalizeAngle(values[1]),
            roll = normalizeAngle(values[2]),
        )
    }

    private fun maybeReportUnsupportedSensor(activeSensor: Sensor, values: FloatArray) {
        val message = "Sensor ${activeSensor.type} produced ${values.size} values"
        if (message == lastSensorDebugMessage) {
            return
        }
        lastSensorDebugMessage = message
        Log.w(tag, "Unsupported sensor payload from ${activeSensor.name}: size=${values.size}, values=${values.joinToString(limit = 8)}")
        HeadposeRepository.update {
            it.copy(
                sensorName = describeSensor(activeSensor, poseSensorModeFor(activeSensor)),
                lastAckMessage = message,
            )
        }
    }

    private fun parseQuestImuSample(values: FloatArray): ImuSample? {
        if (values.size < 3) {
            return null
        }

        if (values.size < 6) {
            lastImuLayout = "gyro-only"
            return ImuSample(
                gyro = floatArrayOf(values[0], values[1], values[2]),
                accel = null,
                layout = lastImuLayout,
            )
        }

        val first = floatArrayOf(values[0], values[1], values[2])
        val second = floatArrayOf(values[3], values[4], values[5])
        val firstGravityScore = gravityScore(first)
        val secondGravityScore = gravityScore(second)
        val accelFirst = firstGravityScore <= secondGravityScore
        val accel = if (accelFirst) first else second
        val gyro = if (accelFirst) second else first
        lastImuLayout = if (accelFirst) "accel-first" else "gyro-first"
        return ImuSample(
            gyro = gyro,
            accel = accel,
            layout = lastImuLayout,
        )
    }

    private fun looksLikeDirectEuler(values: FloatArray): Boolean {
        if (values.size < 6) {
            return false
        }

        val yaw = abs(values[0])
        val pitch = abs(values[1])
        val roll = abs(values[2])
        if (yaw > 180f || pitch > 180f || roll > 180f) {
            return false
        }

        val secondaryMax = max(abs(values[3]), max(abs(values[4]), abs(values[5])))
        return (yaw > 20f || pitch > 10f || roll > 20f) && secondaryMax < 5f
    }

    private fun gravityScore(values: FloatArray): Float {
        val norm = sqrt(values[0].pow(2) + values[1].pow(2) + values[2].pow(2))
        return minOf(abs(norm - 9.80665f), abs(norm - 1f))
    }

    private fun maybeLogRawImu(values: FloatArray, imuSample: ImuSample, eventTimestampNs: Long) {
        if (eventTimestampNs - lastRawImuLogNs < 1_000_000_000L) {
            return
        }
        lastRawImuLogNs = eventTimestampNs
        val accel = imuSample.accel?.joinToString(prefix = "[", postfix = "]") { "%.3f".format(it) } ?: "none"
        val gyro = imuSample.gyro.joinToString(prefix = "[", postfix = "]") { "%.3f".format(it) }
        val raw = values.joinToString(prefix = "[", postfix = "]", limit = 8) { "%.3f".format(it) }
        Log.i(tag, "Raw IMU layout=${imuSample.layout} gyro=$gyro accel=$accel raw=$raw")
    }

    private data class Pose(
        val yaw: Float,
        val pitch: Float,
        val roll: Float,
    )

    private data class ImuSample(
        val gyro: FloatArray,
        val accel: FloatArray?,
        val layout: String,
    )

    private enum class QuestImuInterpretation {
        AUTO,
        DIRECT_EULER,
        GYRO_ACCEL,
    }

    private enum class PoseSensorMode {
        NONE,
        ROTATION_VECTOR,
        QUEST_IMU,
    }

    companion object {
        private const val HEAD_TRACKER_TYPE = 37
        private const val QUEST_HMD_IMU_TYPE = 65537
        const val ACTION_CONNECT = "com.gamesinvr.questheadpose.CONNECT"
        const val ACTION_DISCONNECT = "com.gamesinvr.questheadpose.DISCONNECT"
        const val ACTION_RECENTER = "com.gamesinvr.questheadpose.RECENTER"
        const val ACTION_SET_SENSITIVITY = "com.gamesinvr.questheadpose.SET_SENSITIVITY"
        const val EXTRA_MAC_IP = "mac_ip"
        const val EXTRA_MAC_PORT = "mac_port"
        const val EXTRA_SENSITIVITY = "sensitivity"
    }
}
