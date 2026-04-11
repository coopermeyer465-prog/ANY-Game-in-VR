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
import com.google.android.material.slider.Slider
import kotlinx.coroutines.launch
import java.text.DecimalFormat

class MainActivity : AppCompatActivity() {
    private lateinit var macIpInput: EditText
    private lateinit var macPortInput: EditText
    private lateinit var connectButton: Button
    private lateinit var disconnectButton: Button
    private lateinit var recenterButton: Button
    private lateinit var immersiveButton: Button
    private lateinit var sensitivitySlider: Slider
    private lateinit var sensitivityValueText: TextView
    private lateinit var statusText: TextView

    private val decimal = DecimalFormat("0.00")
    private val sensitivityPresets = QuestPrefs.sensitivityPresets
    private var autoConnectRequested = false
    private var disconnectRequested = false
    private var syncingSensitivitySlider = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        macIpInput = findViewById(R.id.macIpInput)
        macPortInput = findViewById(R.id.macPortInput)
        connectButton = findViewById(R.id.connectButton)
        disconnectButton = findViewById(R.id.disconnectButton)
        recenterButton = findViewById(R.id.recenterButton)
        immersiveButton = findViewById(R.id.immersiveButton)
        sensitivitySlider = findViewById(R.id.sensitivitySlider)
        sensitivityValueText = findViewById(R.id.sensitivityValueText)
        statusText = findViewById(R.id.statusText)

        if (macIpInput.text.isNullOrBlank()) {
            macIpInput.setText(QuestPrefs.getMacIp(this))
        }
        macPortInput.setText(QuestPrefs.getMacPort(this).toString())
        val savedSensitivityIndex = QuestPrefs.getSensitivityPresetIndex(this)
        val savedSensitivity = sensitivityPresets[savedSensitivityIndex]
        HeadposeRepository.update { it.copy(sensitivity = savedSensitivity) }
        syncSensitivitySlider(savedSensitivityIndex, savedSensitivity)
        applyLaunchExtras(intent)

        connectButton.setOnClickListener {
            val macIp = macIpInput.text.toString().trim()
            val macPort = macPortInput.text.toString().toIntOrNull() ?: 7007
            QuestPrefs.saveMacTarget(this, macIp, macPort)
            HeadposeRepository.update { current ->
                current.copy(
                    connected = macIp.isNotBlank(),
                    receiverAcknowledged = false,
                    macIp = macIp,
                    macPort = macPort,
                    lastAckMessage = if (macIp.isBlank()) {
                        "Enter the Mac IP before connecting"
                    } else if (current.immersiveActive) {
                        "Connecting to receiver from OpenXR"
                    } else {
                        "Receiver target saved. Enter OpenXR to start streaming."
                    },
                )
            }
            if (macIp.isNotBlank()) {
                OpenXrActivity.requestConnect(this, macIp, macPort)
            }
        }

        disconnectButton.setOnClickListener {
            HeadposeRepository.update { current ->
                current.copy(
                    connected = false,
                    receiverAcknowledged = false,
                    localPort = 0,
                    lastAckMessage = "Disconnected",
                )
            }
            OpenXrActivity.requestDisconnect(this)
        }

        recenterButton.setOnClickListener {
            HeadposeRepository.update { current ->
                current.copy(
                    lastAckMessage = if (current.immersiveActive) {
                        "Recentering tracked head pose"
                    } else {
                        "Recenter will apply when OpenXR is active"
                    },
                )
            }
            OpenXrActivity.requestRecenter(this)
        }

        immersiveButton.setOnClickListener {
            if (HeadposeRepository.state.value.immersiveActive) {
                OpenXrActivity.closeActive(this)
            } else {
                OpenXrActivity.launch(this)
                finish()
            }
        }

        sensitivitySlider.addOnChangeListener { _, value, fromUser ->
            if (!fromUser || syncingSensitivitySlider) {
                return@addOnChangeListener
            }
            val presetIndex = value.toInt().coerceIn(0, sensitivityPresets.lastIndex)
            applySensitivityPreset(presetIndex)
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

    private fun applySensitivityPreset(index: Int) {
        val presetIndex = index.coerceIn(0, sensitivityPresets.lastIndex)
        val sensitivity = sensitivityPresets[presetIndex]
        QuestPrefs.saveSensitivityPresetIndex(this, presetIndex)
        HeadposeRepository.update { current ->
            current.copy(
                sensitivity = sensitivity,
                lastAckMessage = if (current.connected) {
                    "Updating sensitivity to ${sensitivity.toInt()}"
                } else {
                    "Sensitivity preset saved"
                },
            )
        }
        updateSensitivityLabel(presetIndex, sensitivity)
        OpenXrActivity.notifySensitivityChanged(this, sensitivity)
    }

    private fun syncSensitivitySlider(index: Int, sensitivity: Float) {
        syncingSensitivitySlider = true
        sensitivitySlider.value = index.toFloat()
        syncingSensitivitySlider = false
        updateSensitivityLabel(index, sensitivity)
    }

    private fun updateSensitivityLabel(index: Int, sensitivity: Float) {
        sensitivityValueText.text = getString(
            R.string.sensitivity_value,
            index + 1,
            sensitivityPresets.size,
            sensitivity.toInt(),
        )
    }

    private fun renderState(state: HeadposeState) {
        immersiveButton.text = if (state.immersiveActive) {
            getString(R.string.quit_immersive)
        } else {
            getString(R.string.enter_immersive)
        }

        val connectionLabel = if (state.connected) "Connected" else "Idle"
        val receiverLabel = if (state.receiverAcknowledged) "Receiver ready" else "Waiting for receiver"
        val openXrLabel = state.openXrStatus
        val presetIndex = QuestPrefs.nearestSensitivityPresetIndex(state.sensitivity)
        syncSensitivitySlider(presetIndex, state.sensitivity)

        statusText.text =
            """
            Connection: $connectionLabel
            Receiver: $receiverLabel
            OpenXR: $openXrLabel
            Sensitivity: ${state.sensitivity.toInt()}
            Yaw: ${decimal.format(state.yaw)}
            Pitch: ${decimal.format(state.pitch)}
            Message: ${state.lastAckMessage}
            """.trimIndent()
    }
}
