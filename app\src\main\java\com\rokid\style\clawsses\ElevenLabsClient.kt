package com.rokid.style.clawsses

import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * HTTP client for the ElevenLabs Text-to-Speech API.
 *
 * Usage:
 *   val client = ElevenLabsClient(prefs)
 *   val mp3Bytes = client.synthesize("Hello world")  // blocking, call from coroutine
 */
class ElevenLabsClient(private val prefs: Prefs) {

    companion object {
        private const val TAG = "ElevenLabsClient"
        private const val BASE_URL = "https://api.elevenlabs.io/v1/text-to-speech"
        private const val MODEL_ID = "eleven_flash_v2_5"
    }

    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /**
     * Synthesizes [text] to MP3 audio using ElevenLabs.
     * Returns the raw MP3 bytes, or null on error.
     * This is a blocking network call — run it from a coroutine (Dispatchers.IO).
     */
    fun synthesize(text: String): ByteArray? {
        val apiKey = prefs.elevenLabsApiKey
        val voiceId = prefs.voiceId

        if (apiKey.isBlank()) {
            Log.w(TAG, "ElevenLabs API key not configured")
            return null
        }
        if (voiceId.isBlank()) {
            Log.w(TAG, "Voice ID not configured")
            return null
        }

        val bodyJson = JSONObject().apply {
            put("text", text)
            put("model_id", MODEL_ID)
            put("voice_settings", JSONObject().apply {
                put("stability", 0.5)
                put("similarity_boost", 0.75)
            })
        }.toString()

        val requestBody = bodyJson.toRequestBody("application/json; charset=utf-8".toMediaType())
        val request = Request.Builder()
            .url("$BASE_URL/$voiceId")
            .addHeader("xi-api-key", apiKey)
            .addHeader("Accept", "audio/mpeg")
            .post(requestBody)
            .build()

        return try {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val errorBody = response.body?.string() ?: ""
                    Log.e(TAG, "ElevenLabs error ${response.code}: $errorBody")
                    null
                } else {
                    response.body?.bytes()
                }
            }
        } catch (e: IOException) {
            Log.e(TAG, "ElevenLabs network error: ${e.message}")
            null
        }
    }
}
