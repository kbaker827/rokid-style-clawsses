package com.rokid.style.clawsses

import org.json.JSONArray
import org.json.JSONObject

// ---------------------------------------------------------------------------
// Outbound message builders
// ---------------------------------------------------------------------------

object Protocol {

    fun buildAuthMessage(
        deviceId: String,
        publicKeyBase64: String,
        signatureBase64: String,
        token: String
    ): String = JSONObject().apply {
        put("type", "auth")
        put("device_id", deviceId)
        put("public_key", publicKeyBase64)
        put("signature", signatureBase64)
        put("token", token)
    }.toString()

    fun buildChatMessage(seq: Int, sessionKey: String, content: String): String =
        JSONObject().apply {
            put("type", "chat")
            put("seq", seq)
            put("session_key", sessionKey)
            put("content", content)
        }.toString()

    fun buildListSessionsMessage(seq: Int): String =
        JSONObject().apply {
            put("type", "list_sessions")
            put("seq", seq)
        }.toString()
}

// ---------------------------------------------------------------------------
// Inbound message data classes
// ---------------------------------------------------------------------------

sealed class InboundMessage {
    data class Challenge(val nonce: String) : InboundMessage()
    object AuthOk : InboundMessage()
    data class PairingRequired(val code: String) : InboundMessage()
    data class ChatStream(val delta: String, val runId: String) : InboundMessage()
    data class ChatStreamEnd(val runId: String) : InboundMessage()
    data class ChatMessage(val content: String, val role: String, val sessionKey: String) : InboundMessage()
    data class SessionList(val sessions: List<Session>) : InboundMessage()
    data class ConnectionUpdate(val status: String) : InboundMessage()
    data class Unknown(val type: String) : InboundMessage()
}

data class Session(val key: String, val title: String)

fun parseInboundMessage(json: String): InboundMessage? {
    return try {
        val obj = JSONObject(json)
        when (val type = obj.optString("type")) {
            "challenge" -> InboundMessage.Challenge(obj.getString("nonce"))
            "auth_ok" -> InboundMessage.AuthOk
            "pairing_required" -> InboundMessage.PairingRequired(obj.optString("code", ""))
            "chat_stream" -> InboundMessage.ChatStream(
                delta = obj.optString("delta", ""),
                runId = obj.optString("run_id", "")
            )
            "chat_stream_end" -> InboundMessage.ChatStreamEnd(
                runId = obj.optString("run_id", "")
            )
            "chat_message" -> InboundMessage.ChatMessage(
                content = obj.optString("content", ""),
                role = obj.optString("role", ""),
                sessionKey = obj.optString("session_key", "")
            )
            "session_list" -> {
                val arr: JSONArray = obj.optJSONArray("sessions") ?: JSONArray()
                val sessions = (0 until arr.length()).map { i ->
                    val s = arr.getJSONObject(i)
                    Session(
                        key = s.optString("key", ""),
                        title = s.optString("title", "")
                    )
                }
                InboundMessage.SessionList(sessions)
            }
            "connection_update" -> InboundMessage.ConnectionUpdate(obj.optString("status", ""))
            else -> InboundMessage.Unknown(type)
        }
    } catch (e: Exception) {
        null
    }
}
