package com.gamesinvr.questheadpose

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class HeadposeState(
    val connected: Boolean = false,
    val receiverAcknowledged: Boolean = false,
    val macIp: String = "",
    val macPort: Int = 7007,
    val questIp: String = "",
    val localPort: Int = 0,
    val yaw: Float = 0f,
    val pitch: Float = 0f,
    val roll: Float = 0f,
    val yawVelocity: Float = 0f,
    val pitchVelocity: Float = 0f,
    val openXrStatus: String = "OpenXR native immersive available; window mode uses sensor streaming",
    val sensorName: String = "Unavailable",
    val lastAckMessage: String = "No receiver reply yet",
    val lastPacketAtMs: Long = 0L,
    val lastAckAtMs: Long = 0L,
    val sensitivity: Float = 18f,
    val immersiveActive: Boolean = false,
)

object HeadposeRepository {
    private val mutableState = MutableStateFlow(HeadposeState())

    val state: StateFlow<HeadposeState> = mutableState

    fun update(transform: (HeadposeState) -> HeadposeState) {
        mutableState.value = transform(mutableState.value)
    }
}
