package com.gamesinvr.questheadpose

import android.content.Context

private const val PREFS_NAME = "quest_headpose"
private const val KEY_MAC_IP = "mac_ip"
private const val KEY_MAC_PORT = "mac_port"
private const val KEY_SENSITIVITY_PRESET_INDEX = "sensitivity_preset_index"

object QuestPrefs {
    val sensitivityPresets = floatArrayOf(
        60f,
        85f,
        115f,
        150f,
        190f,
        240f,
        300f,
        375f,
        470f,
        580f,
    )

    private const val DEFAULT_SENSITIVITY_PRESET_INDEX = 5

    fun getMacIp(context: Context): String {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_MAC_IP, "") ?: ""
    }

    fun getMacPort(context: Context): Int {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_MAC_PORT, 7007)
    }

    fun saveMacTarget(context: Context, macIp: String, macPort: Int) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_MAC_IP, macIp)
            .putInt(KEY_MAC_PORT, macPort)
            .apply()
    }

    fun getSensitivityPresetIndex(context: Context): Int {
        val savedIndex = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_SENSITIVITY_PRESET_INDEX, DEFAULT_SENSITIVITY_PRESET_INDEX)
        return savedIndex.coerceIn(0, sensitivityPresets.lastIndex)
    }

    fun getSensitivity(context: Context): Float {
        return sensitivityPresets[getSensitivityPresetIndex(context)]
    }

    fun saveSensitivityPresetIndex(context: Context, index: Int) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_SENSITIVITY_PRESET_INDEX, index.coerceIn(0, sensitivityPresets.lastIndex))
            .apply()
    }

    fun nearestSensitivityPresetIndex(value: Float): Int {
        var nearestIndex = 0
        var nearestDistance = Float.MAX_VALUE
        sensitivityPresets.forEachIndexed { index, preset ->
            val distance = kotlin.math.abs(preset - value)
            if (distance < nearestDistance) {
                nearestIndex = index
                nearestDistance = distance
            }
        }
        return nearestIndex
    }
}
