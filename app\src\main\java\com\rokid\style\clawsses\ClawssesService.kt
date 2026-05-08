package com.rokid.style.clawsses

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Foreground service that orchestrates the full voice assistant pipeline:
 *
 *   SpeechManager → OpenClawClient → ElevenLabsClient → AudioQueuePlayer
 *
 * State is broadcast via local Intent broadcasts so MainActivity can update its UI.
 */
class ClawssesService : LifecycleService() {

    companion object {
        private const val TAG = "ClawssesService"
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "clawsses_main"

        // Broadcast actions sent to MainActivity
        const val ACTION_STATUS_UPDATE = "com.rokid.style.clawsses.STATUS_UPDATE"
        const val ACTION_TRANSCRIPT = "com.rokid.style.clawsses.TRANSCRIPT"
        const val ACTION_RESPONSE_DELTA = "com.rokid.style.clawsses.RESPONSE_DELTA"
        const val ACTION_RESPONSE_DONE = "com.rokid.style.clawsses.RESPONSE_DONE"
        const val ACTION_SESSIONS_UPDATED = "com.rokid.style.clawsses.SESSIONS_UPDATED"

        const val EXTRA_STATUS = "status"
        const val EXTRA_TEXT = "text"
        const val EXTRA_SESSION_KEYS = "session_keys"
        const val EXTRA_SESSION_TITLES = "session_titles"

        // Commands from MainActivity
        const val ACTION_START_LISTENING = "com.rokid.style.clawsses.START_LISTENING"
        const val ACTION_STOP_LISTENING = "com.rokid.style.clawsses.STOP_LISTENING"
    }

    private lateinit var prefs: Prefs
    private lateinit var identity: DeviceIdentity
    private lateinit var clawClient: OpenClawClient
    private lateinit var elevenLabs: ElevenLabsClient
    private lateinit var audioPlayer: AudioQueuePlayer
    private lateinit var speechManager: SpeechManager

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val ioScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Accumulates streaming response text for TTS
    private val streamBuffer = StringBuilder()
    private var currentRunId = ""
    private var isPlayingTts = false

    private val mainHandler = Handler(Looper.getMainLooper())

    // -----------------------------------------------------------------------
    // Lifecycle
    // -----------------------------------------------------------------------

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        identity = DeviceIdentity(this)
        elevenLabs = ElevenLabsClient(prefs)
        audioPlayer = AudioQueuePlayer(cacheDir).also { it.start() }

        clawClient = OpenClawClient(prefs, identity, ClawListener())

        // SpeechManager must run on main thread
        speechManager = SpeechManager(
            context = this,
            onResult = { transcript -> handleTranscript(transcript) },
            onListeningStateChanged = { listening ->
                broadcastStatus(if (listening) "Listening…" else "Idle")
            },
            onError = { err ->
                Log.e(TAG, "Speech error: $err")
                broadcastStatus("Speech error: $err")
            }
        )

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Connecting…"))

        clawClient.connect()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_START_LISTENING -> speechManager.startListening()
            ACTION_STOP_LISTENING -> speechManager.stopListening()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        speechManager.stopListening()
        clawClient.disconnect()
        audioPlayer.stop()
        super.onDestroy()
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    // -----------------------------------------------------------------------
    // Transcript handling
    // -----------------------------------------------------------------------

    private fun handleTranscript(text: String) {
        broadcastIntent(ACTION_TRANSCRIPT, EXTRA_TEXT to text)
        broadcastStatus("Processing…")

        val sessionKey = prefs.selectedSessionKey
        if (sessionKey.isEmpty()) {
            broadcastStatus("No session selected — check Settings")
            return
        }
        if (!clawClient.connected) {
            broadcastStatus("Not connected to OpenClaw")
            return
        }

        // Pause listening while we wait for and play the response
        speechManager.pauseForPlayback()
        streamBuffer.clear()
        clawClient.sendChat(text, sessionKey)
    }

    // -----------------------------------------------------------------------
    // TTS pipeline
    // -----------------------------------------------------------------------

    /** Called when a stream is fully received. Synthesize buffered text. */
    private fun synthesizeAndPlay(text: String) {
        if (text.isBlank()) {
            finishPlayback()
            return
        }
        isPlayingTts = true
        ioScope.launch {
            val mp3 = elevenLabs.synthesize(text)
            if (mp3 != null) {
                audioPlayer.enqueue(mp3)
                // Wait until the queue drains before resuming listening.
                // Rough heuristic: 1 byte ≈ 0.1ms MP3 @ 128kbps ≈ duration.
                // Better: we wait for MediaPlayer completion via a callback.
                // For simplicity here we use a coroutine delay estimate.
                val estimatedMs = ((mp3.size / 128) + 1000).toLong().coerceAtMost(30_000)
                kotlinx.coroutines.delay(estimatedMs)
            }
            mainHandler.post { finishPlayback() }
        }
    }

    private fun finishPlayback() {
        isPlayingTts = false
        broadcastStatus("Idle")
        speechManager.resumeAfterPlayback()
    }

    // -----------------------------------------------------------------------
    // Broadcasts to MainActivity
    // -----------------------------------------------------------------------

    private fun broadcastStatus(status: String) {
        updateNotification(status)
        broadcastIntent(ACTION_STATUS_UPDATE, EXTRA_STATUS to status)
    }

    private fun broadcastIntent(action: String, vararg extras: Pair<String, String>) {
        val intent = Intent(action)
        extras.forEach { (k, v) -> intent.putExtra(k, v) }
        sendBroadcast(intent)
    }

    // -----------------------------------------------------------------------
    // Notifications
    // -----------------------------------------------------------------------

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Clawsses Assistant",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Voice assistant status"
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(status: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Clawsses")
            .setContentText(status)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(status: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildNotification(status))
    }

    // -----------------------------------------------------------------------
    // OpenClaw listener implementation
    // -----------------------------------------------------------------------

    private inner class ClawListener : OpenClawClient.Listener {

        override fun onConnected() {
            mainHandler.post { broadcastStatus("Connected — authenticating…") }
        }

        override fun onDisconnected(reason: String) {
            mainHandler.post { broadcastStatus("Disconnected: $reason") }
        }

        override fun onAuthOk() {
            mainHandler.post {
                broadcastStatus("Authenticated — ready")
                speechManager.startListening()
            }
        }

        override fun onPairingRequired(code: String) {
            mainHandler.post {
                broadcastStatus("Pairing required — code: $code")
            }
        }

        override fun onChatStreamDelta(delta: String, runId: String) {
            mainHandler.post {
                if (runId != currentRunId) {
                    streamBuffer.clear()
                    currentRunId = runId
                }
                streamBuffer.append(delta)
                broadcastIntent(ACTION_RESPONSE_DELTA, EXTRA_TEXT to delta)
            }
        }

        override fun onChatStreamEnd(runId: String) {
            mainHandler.post {
                val fullText = streamBuffer.toString().trim()
                streamBuffer.clear()
                broadcastIntent(ACTION_RESPONSE_DONE, EXTRA_TEXT to fullText)
                broadcastStatus("Speaking…")
                synthesizeAndPlay(fullText)
            }
        }

        override fun onChatMessage(content: String, role: String, sessionKey: String) {
            // Persisted message confirmation — no action needed
        }

        override fun onSessionList(sessions: List<Session>) {
            mainHandler.post {
                val keys = sessions.map { it.key }.toTypedArray()
                val titles = sessions.map { it.title }.toTypedArray()
                val intent = Intent(ACTION_SESSIONS_UPDATED).apply {
                    putExtra(EXTRA_SESSION_KEYS, keys)
                    putExtra(EXTRA_SESSION_TITLES, titles)
                }
                sendBroadcast(intent)

                // Auto-select first session if none selected
                if (prefs.selectedSessionKey.isEmpty() && sessions.isNotEmpty()) {
                    prefs.selectedSessionKey = sessions.first().key
                }
            }
        }

        override fun onError(message: String) {
            mainHandler.post { broadcastStatus("Error: $message") }
        }
    }
}
