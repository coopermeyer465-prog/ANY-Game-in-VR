package com.gamesinvr.questheadpose

import android.app.NativeActivity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle

class OpenXrActivity : NativeActivity() {
    private val closeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        registerReceiver(closeReceiver, IntentFilter(ACTION_CLOSE_OPENXR), RECEIVER_NOT_EXPORTED)
        HeadposeRepository.update {
            it.copy(
                immersiveActive = true,
                openXrStatus = "OpenXR native activity launched",
            )
        }
    }

    override fun onPause() {
        super.onPause()
        if (!isFinishing) {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra("from_openxr", true),
            )
        }
    }

    override fun onDestroy() {
        unregisterReceiver(closeReceiver)
        HeadposeRepository.update {
            it.copy(
                immersiveActive = false,
                openXrStatus = "OpenXR native immersive available; window mode uses sensor streaming",
            )
        }
        super.onDestroy()
    }

    fun onNativeOpenXrReady(runtimeName: String) {
        HeadposeRepository.update {
            it.copy(
                immersiveActive = true,
                openXrStatus = "OpenXR session active: $runtimeName",
            )
        }
    }

    fun onNativeOpenXrError(message: String) {
        HeadposeRepository.update {
            it.copy(
                immersiveActive = false,
                openXrStatus = "OpenXR error: $message",
                lastAckMessage = "OpenXR launch failed: $message",
            )
        }
        finish()
    }

    companion object {
        private const val ACTION_CLOSE_OPENXR = "com.gamesinvr.questheadpose.CLOSE_OPENXR"

        fun launch(context: Context) {
            context.startActivity(
                Intent(context, OpenXrActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }

        fun closeActive(context: Context) {
            context.sendBroadcast(Intent(ACTION_CLOSE_OPENXR).setPackage(context.packageName))
        }
    }
}
