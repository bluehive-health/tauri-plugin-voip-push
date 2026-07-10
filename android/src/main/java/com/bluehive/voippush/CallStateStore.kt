package com.bluehive.voippush

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
            .put("wsToken", ring.wsToken)
            .put("from", ring.from)
            .put("personName", ring.personName)
            .put("flowName", ring.flowName)
            .put("expiresAt", ring.expiresAt)
            .put("answered", false)
        prefs.edit().putString(RING_KEY_PREFIX + ring.callId, json.toString()).apply()
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
            // dupe-check. Answered calls stay until end_callkit_call.
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
                wsToken = json.optString("wsToken"),
                from = json.optString("from"),
                personName = json.optString("personName"),
                flowName = json.optString("flowName"),
                reason = "",
                expiresAt = expiresAt,
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
            prefs.edit().putString(RING_KEY_PREFIX + callId, json.toString()).apply()
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

    /** Queue an action unless an identical kind+callId one is already waiting. */
    @Synchronized
    fun enqueueAction(action: PendingCallAction) {
        val actions = readActions()
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
                .put("wsToken", action.wsToken)
                .put("from", action.from)
                .put("personName", action.personName)
                .put("flowName", action.flowName),
        )
        prefs.edit().putString(ACTIONS_KEY, actions.toString()).apply()
    }

    /** Return all queued actions and clear the queue (atomic read-and-clear). */
    @Synchronized
    fun drainActions(): List<PendingCallAction> {
        val actions = readActions()
        if (actions.length() == 0) return emptyList()
        prefs.edit().remove(ACTIONS_KEY).apply()
        val out = mutableListOf<PendingCallAction>()
        for (i in 0 until actions.length()) {
            try {
                val json = actions.getJSONObject(i)
                out.add(
                    PendingCallAction(
                        kind = json.getString("kind"),
                        callId = json.getString("callId"),
                        wsToken = json.optString("wsToken"),
                        from = json.optString("from"),
                        personName = json.optString("personName"),
                        flowName = json.optString("flowName"),
                    ),
                )
            } catch (e: Exception) {
                /* skip a corrupt entry, keep the rest */
            }
        }
        return out
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
    }
}
