package com.gamesinvr.questheadpose

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import java.text.DecimalFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {
    private lateinit var macIpInput: EditText
    private lateinit var macPortInput: EditText
    private lateinit var connectButton: Button
    private lateinit var disconnectButton: Button
    private lateinit var recenterButton: Button
    private lateinit var immersiveButton: Button
    private lateinit var statusText: TextView

    private val decimal = DecimalFormat("0.00")
    private val clock = SimpleDateFormat("HH:mm:ss", Locale.US)
    private var autoConnectRequested = false
    private var disconnectRequested = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        macIpInput = findViewById(R.id.macIpInput)
        macPortInput = findViewById(R.id.macPortInput)
        connectButton = findViewById(R.id.connectButton)
        disconnectButton = findViewById(R.id.disconnectButton)
        recenterButton = findViewById(R.id.recenterButton)
        immersiveButton = findViewById(R.id.immersiveButton)
        statusText = findViewById(R.id.statusText)

        if (macIpInput.text.isNullOrBlank()) {
            macIpInput.setText(QuestPrefs.getMacIp(this))
        }
        macPortInput.setText(QuestPrefs.getMacPort(this).toString())
        applyLaunchExtras(intent)

        connectButton.setOnClickListener {
            val macIp = macIpInput.text.toString().trim()
            val macPort = macPortInput.text.toString().toIntOrNull() ?: 7007
            QuestPrefs.saveMacTarget(this, macIp, macPort)
            startService(
                Intent(this, HeadposeService::class.java)
                    .setAction(HeadposeService.ACTION_CONNECT)
                    .putExtra(HeadposeService.EXTRA_MAC_IP, macIp)
                    .putExtra(HeadposeService.EXTRA_MAC_PORT, macPort),
            )
        }

        disconnectButton.setOnClickListener {
            startService(Intent(this, HeadposeService::class.java).setAction(HeadposeService.ACTION_DISCONNECT))
        }

        recenterButton.setOnClickListener {
            startService(Intent(this, HeadposeService::class.java).setAction(HeadposeService.ACTION_RECENTER))
        }

        immersiveButton.setOnClickListener {
            if (HeadposeRepository.state.value.immersiveActive) {
                OpenXrActivity.closeActive(this)
            } else {
                OpenXrActivity.launch(this)
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                HeadposeRepository.state.collect(::renderState)
            }
        }

        maybeHandleLaunchRequests()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (intent != null) {
            applyLaunchExtras(intent)
            maybeHandleLaunchRequests()
        }
    }

    private fun applyLaunchExtras(intent: Intent) {
        val extraMacIp = intent.getStringExtra("mac_ip")?.trim().orEmpty()
        val extraMacPort = intent.getIntExtra("mac_port", -1).takeIf { it > 0 }
        if (extraMacIp.isNotBlank()) {
            macIpInput.setText(extraMacIp)
        }
        if (extraMacPort != null) {
            macPortInput.setText(extraMacPort.toString())
        }
        autoConnectRequested = autoConnectRequested || intent.getBooleanExtra("auto_connect", false)
        disconnectRequested = disconnectRequested || intent.getBooleanExtra("request_disconnect", false)
    }

    private fun maybeHandleLaunchRequests() {
        window.decorView.post {
            if (disconnectRequested) {
                disconnectRequested = false
                disconnectButton.performClick()
            }
            if (autoConnectRequested && !HeadposeRepository.state.value.connected) {
                autoConnectRequested = false
                connectButton.performClick()
            }
        }
    }

    private fun renderState(state: HeadposeState) {
        immersiveButton.text = if (state.immersiveActive) {
            getString(R.string.quit_immersive)
        } else {
            getString(R.string.enter_immersive)
        }

        val lastPacket = if (state.lastPacketAtMs > 0) {
            clock.format(Date(state.lastPacketAtMs))
        } else {
            "never"
        }
        val lastAck = if (state.lastAckAtMs > 0) {
            clock.format(Date(state.lastAckAtMs))
        } else {
            "never"
        }

        statusText.text =
            """
            Connection: ${if (state.connected) "connected" else "idle"}
            Receiver ack: ${if (state.receiverAcknowledged) "yes" else "no"}
            OpenXR: ${state.openXrStatus}
            Sensor: ${state.sensorName}
            Mac: ${state.macIp}:${state.macPort}
            Quest IP: ${state.questIp.ifBlank { "unknown" }}
            Local UDP port: ${state.localPort}
            Yaw: ${decimal.format(state.yaw)}
            Pitch: ${decimal.format(state.pitch)}
            Roll: ${decimal.format(state.roll)}
            Yaw delta: ${decimal.format(state.yawVelocity)}
            Pitch delta: ${decimal.format(state.pitchVelocity)}
            Receiver sensitivity: ${decimal.format(state.sensitivity)}
            Last sent: $lastPacket
            Last ack: $lastAck
            Last receiver message: ${state.lastAckMessage}
            Immersive black view: ${if (state.immersiveActive) "active" else "off"}
            """.trimIndent()
    }
}
