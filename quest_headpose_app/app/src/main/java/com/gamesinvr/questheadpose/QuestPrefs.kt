package com.gamesinvr.questheadpose

import android.content.Context

private const val PREFS_NAME = "quest_headpose"
private const val KEY_MAC_IP = "mac_ip"
private const val KEY_MAC_PORT = "mac_port"

object QuestPrefs {
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
}
