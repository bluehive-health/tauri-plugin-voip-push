package com.voippush

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
    val joinToken: String,
    val from: String,
    val personName: String,
    val lineName: String,
    /** Cancel reason ("answered_elsewhere", "caller_hung_up", …); empty on ring. */
    val reason: String,
    /** ms epoch after which the ring is dead; 0 = no expiry supplied. */
    val expiresAt: Long,
    /** Optional URL POSTed when the user answers natively; empty = no callback. */
    val answerUrl: String = "",
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
        lineName.isNotEmpty() && from.isNotEmpty() -> "$lineName · $from"
        lineName.isNotEmpty() -> lineName
        from.isNotEmpty() -> from
        else -> context.getString(R.string.voip_push_unknown_caller)
    }

    /** Redacts the join token and answer URL — a leaked token lets anyone join the call. */
    override fun toString(): String =
        "RingPayload(action=$action, callId=$callId, joinToken=<redacted>, from=$from, " +
            "personName=$personName, lineName=$lineName, reason=$reason, expiresAt=$expiresAt, " +
            "answerUrl=<redacted>)"

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
                joinToken = data["join_token"] ?: "",
                // `from_number`, not `from` — FCM reserves the `from` data key.
                from = data["from_number"] ?: "",
                personName = data["person_name"] ?: "",
                lineName = data["line_name"] ?: "",
                reason = data["reason"] ?: "",
                expiresAt = data["expires_at"]?.toLongOrNull() ?: 0L,
                answerUrl = data["answer_url"] ?: "",
            )
        }
    }
}

/**
 * One Answer / Decline / End tap on the native call surface, queued until
 * the webview drains it. Field names match the iOS `PendingCallAction`
 * struct — the webview consumes them camelCase via
 * `drain_pending_call_actions`. `joinToken`/`from`/`personName`/`lineName`
 * are only present on "answer" actions.
 */
data class PendingCallAction(
    /** [KIND_ANSWER] | [KIND_DECLINE] (un-answered dismiss) | [KIND_END] (answered hangup) */
    val kind: String,
    val callId: String,
    /** Present on "answer" only. */
    val joinToken: String? = null,
    val from: String? = null,
    val personName: String? = null,
    val lineName: String? = null,
) {
    /** Redacts the join token — a leaked one lets anyone join the call. */
    override fun toString(): String =
        "PendingCallAction(kind=$kind, callId=$callId, joinToken=<redacted>, from=$from, " +
            "personName=$personName, lineName=$lineName)"

    companion object {
        const val KIND_ANSWER = "answer"
        const val KIND_DECLINE = "decline"
        const val KIND_END = "end"
    }
}
