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

        val connectionLabel = if (state.connected) "Connected" else "Idle"
        val receiverLabel = if (state.receiverAcknowledged) "Receiver ready" else "Waiting for receiver"
        val openXrLabel = when {
            state.immersiveActive -> "Immersive active"
            state.openXrStatus.startsWith("OpenXR error:", ignoreCase = true) -> state.openXrStatus
            else -> "Ready"
        }

        statusText.text =
            """
            Connection: $connectionLabel
            Receiver: $receiverLabel
            OpenXR: $openXrLabel
            Sensor: ${state.sensorName}
            Yaw: ${decimal.format(state.yaw)}
            Pitch: ${decimal.format(state.pitch)}
            Roll: ${decimal.format(state.roll)}
            Message: ${state.lastAckMessage}
            """.trimIndent()
    }
}
