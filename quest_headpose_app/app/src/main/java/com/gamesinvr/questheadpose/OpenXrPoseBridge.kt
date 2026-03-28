package com.gamesinvr.questheadpose

import android.os.SystemClock

data class OpenXrPoseFrame(
    val yaw: Float,
    val pitch: Float,
    val roll: Float,
    val receivedAtNs: Long,
)

object OpenXrPoseBridge {
    @Volatile
    private var latestPose: OpenXrPoseFrame? = null

    @JvmStatic
    fun updatePose(yaw: Float, pitch: Float, roll: Float) {
        latestPose = OpenXrPoseFrame(
            yaw = yaw,
            pitch = pitch,
            roll = roll,
            receivedAtNs = SystemClock.elapsedRealtimeNanos(),
        )
    }

    @JvmStatic
    fun clearPose() {
        latestPose = null
    }

    fun latestRecentPose(maxAgeNs: Long): OpenXrPoseFrame? {
        val pose = latestPose ?: return null
        return if (SystemClock.elapsedRealtimeNanos() - pose.receivedAtNs <= maxAgeNs) {
            pose
        } else {
            null
        }
    }
}
