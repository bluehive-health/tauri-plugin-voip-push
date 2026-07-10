package com.bluehive.voippush

import android.content.Context

/**
 * Parsed `call.ring` / `call.ring_cancel` FCM data message — the Android
 * counterpart of the payload handled by ios/Sources/VoipPushPlugin.swift
 * `handleVoipPush`. See the README for the payload contract (all values
 * arrive as strings, including `expires_at`).
 */
data class RingPayload(
    /** "ring" | "cancel" */
    val action: String,
    val callId: String,
    /** Signed token the app uses to join the call — empty on cancel. */
    val wsToken: String,
    val from: String,
    val personName: String,
    val flowName: String,
    /** Cancel reason ("answered_elsewhere", "caller_hung_up", …); empty on ring. */
    val reason: String,
    /** ms epoch after which the ring is dead; 0 = no expiry supplied. */
    val expiresAt: Long,
) {
    val isExpired: Boolean
        get() = expiresAt > 0 && expiresAt <= System.currentTimeMillis()

    /**
     * What every ring surface (Telecom, notification, full-screen activity)
     * shows for the caller: the person's name when known, else
     * "Line · number".
     */
    fun callerDisplayName(context: Context): String = when {
        personName.isNotEmpty() -> personName
        flowName.isNotEmpty() && from.isNotEmpty() -> "$flowName · $from"
        flowName.isNotEmpty() -> flowName
        from.isNotEmpty() -> from
        else -> context.getString(R.string.voip_push_unknown_caller)
    }

    /** Redacts the ws token — a leaked one lets anyone join the call. */
    override fun toString(): String =
        "RingPayload(action=$action, callId=$callId, wsToken=<redacted>, from=$from, " +
            "personName=$personName, flowName=$flowName, reason=$reason, expiresAt=$expiresAt)"

    companion object {
        /** Returns null when the message is not a ring/cancel or lacks a call id. */
        fun fromFcmData(data: Map<String, String>): RingPayload? {
            val action = data["action"] ?: return null
            if (action != "ring" && action != "cancel") return null
            val callId = data["call_id"] ?: return null
            if (callId.isEmpty()) return null
            return RingPayload(
                action = action,
                callId = callId,
                wsToken = data["ws_token"] ?: "",
                // `from_number`, not `from` — FCM reserves the `from` data key.
                from = data["from_number"] ?: "",
                personName = data["person_name"] ?: "",
                flowName = data["flow_name"] ?: "",
                reason = data["reason"] ?: "",
                expiresAt = data["expires_at"]?.toLongOrNull() ?: 0L,
            )
        }
    }
}

/**
 * One Answer / Decline / End tap on the native call surface, queued until
 * the webview drains it. Field names match the iOS `PendingCallAction`
 * struct — the webview consumes them camelCase via
 * `drain_pending_call_actions`.
 */
data class PendingCallAction(
    /** "answer" | "decline" (un-answered dismiss) | "end" (answered hangup) */
    val kind: String,
    val callId: String,
    /** Present on "answer" only. */
    val wsToken: String,
    val from: String,
    val personName: String,
    val flowName: String,
) {
    /** Redacts the ws token — a leaked one lets anyone join the call. */
    override fun toString(): String =
        "PendingCallAction(kind=$kind, callId=$callId, wsToken=<redacted>, from=$from, " +
            "personName=$personName, flowName=$flowName)"
}
