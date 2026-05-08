package com.rokid.style.clawsses

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import java.util.Locale

/**
 * Manages Android speech recognition with silence detection.
 *
 * Listens continuously: after a final result or a configurable silence timeout,
 * it delivers the transcript to [onResult] and optionally restarts listening.
 *
 * Must be created and used on the **main thread** (SpeechRecognizer requirement).
 */
class SpeechManager(
    private val context: Context,
    private val onResult: (String) -> Unit,
    private val onListeningStateChanged: (Boolean) -> Unit,
    private val onError: (String) -> Unit
) {
    companion object {
        private const val TAG = "SpeechManager"
    }

    private var recognizer: SpeechRecognizer? = null
    private var isListening = false
    private var shouldContinue = false

    /** Start continuous listening. */
    fun startListening() {
        shouldContinue = true
        beginSession()
    }

    /** Stop listening permanently. */
    fun stopListening() {
        shouldContinue = false
        isListening = false
        recognizer?.apply {
            stopListening()
            destroy()
        }
        recognizer = null
        onListeningStateChanged(false)
    }

    /** Temporarily pause for TTS playback, then resume. */
    fun pauseForPlayback() {
        if (!isListening) return
        recognizer?.stopListening()
        isListening = false
        onListeningStateChanged(false)
    }

    /** Resume after TTS finished. */
    fun resumeAfterPlayback() {
        if (shouldContinue && !isListening) {
            beginSession()
        }
    }

    // -----------------------------------------------------------------------

    private fun beginSession() {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            onError("Speech recognition not available on this device")
            return
        }

        recognizer?.destroy()
        recognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
            setRecognitionListener(InternalListener())
        }

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 500L)
        }

        try {
            recognizer?.startListening(intent)
            isListening = true
            onListeningStateChanged(true)
        } catch (e: Exception) {
            Log.e(TAG, "startListening failed: ${e.message}")
            onError("Failed to start listening: ${e.message}")
        }
    }

    private inner class InternalListener : RecognitionListener {

        override fun onReadyForSpeech(params: Bundle?) {
            Log.d(TAG, "Ready for speech")
        }

        override fun onBeginningOfSpeech() {
            Log.d(TAG, "Speech started")
        }

        override fun onRmsChanged(rmsdB: Float) {
            // Could expose RMS for a visual level meter; unused for audio-only device
        }

        override fun onBufferReceived(buffer: ByteArray?) {}

        override fun onEndOfSpeech() {
            Log.d(TAG, "Speech ended")
            isListening = false
            onListeningStateChanged(false)
        }

        override fun onError(error: Int) {
            isListening = false
            onListeningStateChanged(false)
            val msg = when (error) {
                SpeechRecognizer.ERROR_AUDIO -> "Audio recording error"
                SpeechRecognizer.ERROR_CLIENT -> "Client side error"
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Insufficient permissions"
                SpeechRecognizer.ERROR_NETWORK -> "Network error"
                SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timeout"
                SpeechRecognizer.ERROR_NO_MATCH -> "No speech match"
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognition service busy"
                SpeechRecognizer.ERROR_SERVER -> "Server error"
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech input"
                else -> "Unknown error $error"
            }
            Log.w(TAG, "Recognition error: $msg")

            // Restart on non-fatal errors if we should continue
            if (shouldContinue && error != SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                beginSession()
            } else {
                onError(msg)
            }
        }

        override fun onResults(results: Bundle?) {
            isListening = false
            onListeningStateChanged(false)
            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val transcript = matches?.firstOrNull()?.trim() ?: ""
            if (transcript.isNotEmpty()) {
                Log.i(TAG, "Result: $transcript")
                onResult(transcript)
            }
            // Restart listening after delivering result
            if (shouldContinue) {
                beginSession()
            }
        }

        override fun onPartialResults(partialResults: Bundle?) {}

        override fun onEvent(eventType: Int, params: Bundle?) {}
    }
}
