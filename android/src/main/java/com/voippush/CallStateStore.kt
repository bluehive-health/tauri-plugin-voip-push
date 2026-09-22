package com.voippush

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * Durable call state, backed by SharedPreferences so it survives process
 * death — the Android analog of the in-memory dictionaries in
 * ios/Sources/VoipPushPlugin.swift (iOS keeps them in memory because a VoIP
 * push always relaunches the app; Android's FCM service can run in a
 * process that dies before the webview ever boots).
 *
 * Two things live here:
 *   - active rings (`ring:<callId>`): the payload the webview needs to
 *     join, kept until answered/declined/cancelled/expired.
 *   - pending call actions: Answer/Decline/End taps not yet drained by the
 *     webview. Draining clears the queue (that's what dedupes the
 *     live-event and cold-start paths, same as iOS).
 *
 * All methods are @Synchronized on the class — writes are tiny and rare
 * (one per ring/tap), so a lock is simpler than atomicity gymnastics.
 */
class CallStateStore(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ---------------------------------------------------------------
    // Active rings
    // ---------------------------------------------------------------

    @Synchronized
    fun saveRing(ring: RingPayload) {
        val json = JSONObject()
            .put("callId", ring.callId)
            .put("joinToken", ring.joinToken)
            .put("from", ring.from)
            .put("personName", ring.personName)
            .put("lineName", ring.lineName)
            .put("expiresAt", ring.expiresAt)
            .put("answerUrl", ring.answerUrl)
            .put("answered", false)
        // commit(), not apply(): the FCM process can be killed right after
        // this write, and a lost ring means an unanswerable call.
        prefs.edit().putString(RING_KEY_PREFIX + ring.callId, json.toString()).commit()
    }

    @Synchronized
    fun getRing(callId: String): RingPayload? {
        val raw = prefs.getString(RING_KEY_PREFIX + callId, null) ?: return null
        return try {
            val json = JSONObject(raw)
            val expiresAt = json.optLong("expiresAt")
            // Self-purge: the process can die mid-ring, taking the cleanup
            // timer with it. An unanswered ring past its expiry is dead —
            // drop it so stale entries can't accumulate or shadow the
            // dupe-check. Answered calls stay until end_call.
            if (!json.optBoolean("answered") &&
                expiresAt > 0 &&
                expiresAt <= System.currentTimeMillis()
            ) {
                prefs.edit().remove(RING_KEY_PREFIX + callId).apply()
                return null
            }
            RingPayload(
                action = "ring",
                callId = json.getString("callId"),
                joinToken = json.optString("joinToken"),
                from = json.optString("from"),
                personName = json.optString("personName"),
                lineName = json.optString("lineName"),
                reason = "",
                expiresAt = expiresAt,
                // Absent on rings stored before this field existed.
                answerUrl = json.optString("answerUrl"),
            )
        } catch (e: Exception) {
            null
        }
    }

    @Synchronized
    fun markAnswered(callId: String) {
        val raw = prefs.getString(RING_KEY_PREFIX + callId, null) ?: return
        try {
            val json = JSONObject(raw).put("answered", true)
            // commit(): losing this write would turn a hangup into a decline.
            prefs.edit().putString(RING_KEY_PREFIX + callId, json.toString()).commit()
        } catch (e: Exception) {
            /* corrupt entry — removeRing will clean it up */
        }
    }

    @Synchronized
    fun isAnswered(callId: String): Boolean {
        val raw = prefs.getString(RING_KEY_PREFIX + callId, null) ?: return false
        return try {
            JSONObject(raw).optBoolean("answered")
        } catch (e: Exception) {
            false
        }
    }

    @Synchronized
    fun removeRing(callId: String) {
        prefs.edit().remove(RING_KEY_PREFIX + callId).apply()
    }

    // ---------------------------------------------------------------
    // Pending call actions (drained by the webview)
    // ---------------------------------------------------------------

    /**
     * Queue an action unless an identical kind+callId one is already waiting.
     * The queue is capped ([MAX_PENDING_ACTIONS], oldest dropped first) and
     * entries expire after [MAX_ACTION_AGE_MS] — an un-drained action from
     * hours ago belongs to a call that is long over, and its join token
     * should not linger on disk.
     */
    @Synchronized
    fun enqueueAction(action: PendingCallAction) {
        val actions = pruneActions(readActions())
        for (i in 0 until actions.length()) {
            val existing = actions.getJSONObject(i)
            if (existing.optString("kind") == action.kind &&
                existing.optString("callId") == action.callId
            ) {
                return
            }
        }
        actions.put(
            JSONObject()
                .put("kind", action.kind)
                .put("callId", action.callId)
                .put("joinToken", action.joinToken)
                .put("from", action.from)
                .put("personName", action.personName)
                .put("lineName", action.lineName)
                .put("queuedAt", System.currentTimeMillis()),
        )
        while (actions.length() > MAX_PENDING_ACTIONS) actions.remove(0)
        // commit(): an async apply() can lose an Answer tap to process death.
        prefs.edit().putString(ACTIONS_KEY, actions.toString()).commit()
    }

    /** Return all queued, un-expired actions and clear the queue (atomic read-and-clear). */
    @Synchronized
    fun drainActions(): List<PendingCallAction> {
        val actions = pruneActions(readActions())
        prefs.edit().remove(ACTIONS_KEY).apply()
        if (actions.length() == 0) return emptyList()
        val out = mutableListOf<PendingCallAction>()
        for (i in 0 until actions.length()) {
            try {
                val json = actions.getJSONObject(i)
                out.add(
                    PendingCallAction(
                        kind = json.getString("kind"),
                        callId = json.getString("callId"),
                        joinToken = json.optStringOrNull("joinToken"),
                        from = json.optStringOrNull("from"),
                        personName = json.optStringOrNull("personName"),
                        lineName = json.optStringOrNull("lineName"),
                    ),
                )
            } catch (e: Exception) {
                /* skip a corrupt entry, keep the rest */
            }
        }
        return out
    }

    /** Drop actions past [MAX_ACTION_AGE_MS] (entries without a timestamp are kept). */
    private fun pruneActions(actions: JSONArray): JSONArray {
        val cutoff = System.currentTimeMillis() - MAX_ACTION_AGE_MS
        val kept = JSONArray()
        for (i in 0 until actions.length()) {
            val entry = actions.optJSONObject(i) ?: continue
            val queuedAt = entry.optLong("queuedAt", Long.MAX_VALUE)
            if (queuedAt >= cutoff) kept.put(entry)
        }
        return kept
    }

    private fun JSONObject.optStringOrNull(key: String): String? {
        if (isNull(key)) return null
        val value = optString(key)
        return value.ifEmpty { null }
    }

    private fun readActions(): JSONArray {
        val raw = prefs.getString(ACTIONS_KEY, null) ?: return JSONArray()
        return try {
            JSONArray(raw)
        } catch (e: Exception) {
            JSONArray()
        }
    }

    companion object {
        private const val PREFS_NAME = "voip_push_call_state"
        private const val RING_KEY_PREFIX = "ring:"
        private const val ACTIONS_KEY = "pending_actions"

        /** A stuck webview should not let taps (and their tokens) pile up forever. */
        private const val MAX_PENDING_ACTIONS = 50

        /** Un-drained actions older than this are for calls that are long over. */
        private const val MAX_ACTION_AGE_MS = 24L * 60 * 60 * 1000
    }
}
