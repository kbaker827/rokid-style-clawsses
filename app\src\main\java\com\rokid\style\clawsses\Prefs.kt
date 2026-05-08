package com.rokid.style.clawsses

import android.content.Context
import android.content.SharedPreferences

class Prefs(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("clawsses_prefs", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_HOST = "host"
        private const val KEY_PORT = "port"
        private const val KEY_TOKEN = "token"
        private const val KEY_ELEVENLABS_API_KEY = "elevenlabs_api_key"
        private const val KEY_VOICE_ID = "voice_id"
        private const val KEY_SELECTED_SESSION_KEY = "selected_session_key"

        private const val DEFAULT_HOST = "192.168.1.100"
        private const val DEFAULT_PORT = 18789
        private const val DEFAULT_VOICE_ID = "21m00Tcm4TlvDq8ikWAM" // ElevenLabs "Rachel"
    }

    var host: String
        get() = prefs.getString(KEY_HOST, DEFAULT_HOST) ?: DEFAULT_HOST
        set(value) = prefs.edit().putString(KEY_HOST, value).apply()

    var port: Int
        get() = prefs.getInt(KEY_PORT, DEFAULT_PORT)
        set(value) = prefs.edit().putInt(KEY_PORT, value).apply()

    var token: String
        get() = prefs.getString(KEY_TOKEN, "") ?: ""
        set(value) = prefs.edit().putString(KEY_TOKEN, value).apply()

    var elevenLabsApiKey: String
        get() = prefs.getString(KEY_ELEVENLABS_API_KEY, "") ?: ""
        set(value) = prefs.edit().putString(KEY_ELEVENLABS_API_KEY, value).apply()

    var voiceId: String
        get() = prefs.getString(KEY_VOICE_ID, DEFAULT_VOICE_ID) ?: DEFAULT_VOICE_ID
        set(value) = prefs.edit().putString(KEY_VOICE_ID, value).apply()

    var selectedSessionKey: String
        get() = prefs.getString(KEY_SELECTED_SESSION_KEY, "") ?: ""
        set(value) = prefs.edit().putString(KEY_SELECTED_SESSION_KEY, value).apply()

    val wsUrl: String
        get() = "ws://$host:$port"
}
