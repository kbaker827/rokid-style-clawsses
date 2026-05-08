package com.rokid.style.clawsses

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * WebSocket client for the OpenClaw AI gateway.
 *
 * Lifecycle:
 *  - Call [connect] to establish a connection. Auto-reconnect is built in.
 *  - Call [disconnect] to stop permanently.
 *  - Use [sendChat] to send a user message.
 *  - Use [sendListSessions] to request the session list.
 *  - Provide a [listener] to receive inbound events.
 */
class OpenClawClient(
    private val prefs: Prefs,
    private val identity: DeviceIdentity,
    private val listener: Listener
) {
    interface Listener {
        fun onConnected()
        fun onDisconnected(reason: String)
        fun onAuthOk()
        fun onPairingRequired(code: String)
        fun onChatStreamDelta(delta: String, runId: String)
        fun onChatStreamEnd(runId: String)
        fun onChatMessage(content: String, role: String, sessionKey: String)
        fun onSessionList(sessions: List<Session>)
        fun onError(message: String)
    }

    companion object {
        private const val TAG = "OpenClawClient"
        private const val RECONNECT_DELAY_MS = 3_000L
        private const val MAX_RECONNECT_DELAY_MS = 30_000L
        private const val PING_INTERVAL_SECONDS = 20L
    }

    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .pingInterval(PING_INTERVAL_SECONDS, TimeUnit.SECONDS)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS) // no read timeout for streaming
        .build()

    @Volatile private var webSocket: WebSocket? = null
    private val isConnected = AtomicBoolean(false)
    private val shouldReconnect = AtomicBoolean(false)
    private val seqCounter = AtomicInteger(1)
    private var reconnectDelay = RECONNECT_DELAY_MS

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    fun connect() {
        shouldReconnect.set(true)
        reconnectDelay = RECONNECT_DELAY_MS
        attemptConnect()
    }

    fun disconnect() {
        shouldReconnect.set(false)
        webSocket?.close(1000, "Client disconnect")
        webSocket = null
        isConnected.set(false)
    }

    fun sendChat(content: String, sessionKey: String) {
        val seq = seqCounter.getAndIncrement()
        val json = Protocol.buildChatMessage(seq, sessionKey, content)
        sendRaw(json)
    }

    fun sendListSessions() {
        val seq = seqCounter.getAndIncrement()
        val json = Protocol.buildListSessionsMessage(seq)
        sendRaw(json)
    }

    val connected: Boolean get() = isConnected.get()

    // -----------------------------------------------------------------------
    // Private helpers
    // -----------------------------------------------------------------------

    private fun attemptConnect() {
        if (!shouldReconnect.get()) return
        val url = prefs.wsUrl
        Log.i(TAG, "Connecting to $url")

        val request = Request.Builder().url(url).build()
        webSocket = httpClient.newWebSocket(request, InternalListener())
    }

    private fun sendRaw(json: String): Boolean {
        val ws = webSocket
        if (ws == null || !isConnected.get()) {
            Log.w(TAG, "sendRaw called but not connected; dropping: $json")
            return false
        }
        return ws.send(json)
    }

    private fun scheduleReconnect() {
        if (!shouldReconnect.get()) return
        scope.launch {
            Log.i(TAG, "Reconnecting in ${reconnectDelay}ms")
            delay(reconnectDelay)
            reconnectDelay = minOf(reconnectDelay * 2, MAX_RECONNECT_DELAY_MS)
            attemptConnect()
        }
    }

    // -----------------------------------------------------------------------
    // WebSocket listener (inner class)
    // -----------------------------------------------------------------------

    private inner class InternalListener : WebSocketListener() {

        override fun onOpen(webSocket: WebSocket, response: Response) {
            Log.i(TAG, "WebSocket opened")
            isConnected.set(true)
            reconnectDelay = RECONNECT_DELAY_MS
            listener.onConnected()
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            Log.d(TAG, "RX: $text")
            val msg = parseInboundMessage(text) ?: run {
                Log.w(TAG, "Failed to parse: $text")
                return
            }

            when (msg) {
                is InboundMessage.Challenge -> handleChallenge(msg.nonce)
                is InboundMessage.AuthOk -> {
                    listener.onAuthOk()
                    // Request session list immediately after auth
                    sendListSessions()
                }
                is InboundMessage.PairingRequired -> listener.onPairingRequired(msg.code)
                is InboundMessage.ChatStream -> listener.onChatStreamDelta(msg.delta, msg.runId)
                is InboundMessage.ChatStreamEnd -> listener.onChatStreamEnd(msg.runId)
                is InboundMessage.ChatMessage -> listener.onChatMessage(msg.content, msg.role, msg.sessionKey)
                is InboundMessage.SessionList -> listener.onSessionList(msg.sessions)
                is InboundMessage.ConnectionUpdate -> Log.d(TAG, "Connection update: ${msg.status}")
                is InboundMessage.Unknown -> Log.d(TAG, "Unknown message type: ${msg.type}")
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            Log.i(TAG, "WebSocket closing: $code $reason")
            webSocket.close(1000, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            Log.i(TAG, "WebSocket closed: $code $reason")
            isConnected.set(false)
            this@OpenClawClient.webSocket = null
            listener.onDisconnected(reason)
            scheduleReconnect()
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.e(TAG, "WebSocket failure: ${t.message}")
            isConnected.set(false)
            this@OpenClawClient.webSocket = null
            listener.onDisconnected(t.message ?: "Unknown error")
            listener.onError(t.message ?: "Connection failed")
            scheduleReconnect()
        }
    }

    private fun handleChallenge(nonce: String) {
        Log.i(TAG, "Received challenge, signing nonce")
        try {
            val signature = identity.signNonce(nonce)
            val authMsg = Protocol.buildAuthMessage(
                deviceId = identity.deviceId,
                publicKeyBase64 = identity.publicKeyBase64,
                signatureBase64 = signature,
                token = prefs.token
            )
            sendRaw(authMsg)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to sign challenge: ${e.message}")
            listener.onError("Auth signing failed: ${e.message}")
        }
    }
}
