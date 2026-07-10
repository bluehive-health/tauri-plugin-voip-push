package com.bluehive.voippush

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.telecom.DisconnectCause
import android.telecom.PhoneAccount
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.util.Log
import java.lang.ref.WeakReference

/**
 * Android incoming-call orchestration — the counterpart of the CallKit
 * half of ios/Sources/VoipPushPlugin.swift. One entry point per lifecycle
 * event; every terminal path funnels through [cleanUp] so the Telecom
 * connection, the notification, the full-screen activity, and the stored
 * ring can never leak independently.
 *
 * Ring presentation is belt-and-braces: we report the call to Telecom
 * (self-managed ConnectionService) so the system knows a call is active,
 * AND post a CallStyle full-screen notification ourselves — self-managed
 * Telecom deliberately delegates incoming-call UI to the app
 * (Connection.onShowIncomingCallUi). If Telecom rejects the call
 * (constraints, disabled account), the notification path still rings.
 *
 * Everything runs on the main thread (FCM callbacks, Telecom callbacks,
 * BroadcastReceivers, and plugin commands all land there), so the
 * connection map needs no locking. Durable state lives in
 * [CallStateStore]; the map only holds live Telecom objects, which cannot
 * outlive the process anyway.
 */
object IncomingCallManager {

    private const val TAG = "voip-push"
    private const val PHONE_ACCOUNT_ID = "voip-push"

    /** Live Telecom connections by call id. Main-thread only. */
    private val connections = mutableMapOf<String, VoipConnection>()

    /** Local ring-expiry timers (the server should also push a cancel; this is the fallback). */
    private val expiryHandler = Handler(Looper.getMainLooper())
    private val expiryTokens = mutableMapOf<String, Runnable>()

    /** The full-screen ring activity, if one is showing — finished on teardown. */
    var fullScreenActivity: WeakReference<IncomingCallActivity>? = null

    // ---------------------------------------------------------------
    // Ring lifecycle
    // ---------------------------------------------------------------

    /** A `call.ring` push arrived. Present the incoming call natively. */
    fun startRing(context: Context, ring: RingPayload) {
        val store = CallStateStore(context)
        if (store.getRing(ring.callId) != null) return // duplicate push
        if (ring.isExpired || ring.wsToken.isEmpty()) {
            Log.w(TAG, "dropping unusable ring for ${ring.callId} (expired or tokenless)")
            return
        }
        store.saveRing(ring)

        // Tell Telecom about the call. Self-managed accounts still rely on
        // the app for the actual ring UI, which onShowIncomingCallUi (or
        // the fallback below) provides.
        var telecomAccepted = false
        try {
            val telecom = context.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
            val handle = phoneAccountHandle(context)
            registerPhoneAccount(context, telecom, handle)
            val extras = Bundle().apply {
                putString(VoipConnectionService.EXTRA_CALL_ID, ring.callId)
                if (ring.from.isNotEmpty()) {
                    putParcelable(
                        TelecomManager.EXTRA_INCOMING_CALL_ADDRESS,
                        Uri.fromParts(PhoneAccount.SCHEME_TEL, ring.from, null),
                    )
                }
            }
            telecom.addNewIncomingCall(handle, extras)
            telecomAccepted = true
        } catch (e: Exception) {
            // SecurityException (account disabled), telecom constraints, …
            Log.w(TAG, "telecom addNewIncomingCall failed, notification-only ring", e)
        }
        if (!telecomAccepted) showIncomingCallUi(context, ring.callId)

        // Local expiry fallback: the server pushes a cancel when the ring
        // deadline lapses, but if that push is lost the ring must not stay
        // up forever.
        if (ring.expiresAt > 0) {
            val delay = ring.expiresAt - System.currentTimeMillis()
            val token = Runnable { expire(context.applicationContext, ring.callId) }
            expiryTokens[ring.callId] = token
            expiryHandler.postDelayed(token, delay.coerceAtLeast(0))
        }
    }

    /** Telecom accepted the call and created our Connection for it. */
    fun onConnectionCreated(callId: String, connection: VoipConnection) {
        connections[callId] = connection
    }

    /** Show the ring surface (CallStyle notification + full-screen intent). */
    fun showIncomingCallUi(context: Context, callId: String) {
        val ring = CallStateStore(context).getRing(callId) ?: return
        IncomingCallNotifier.show(context, ring)
    }

    // ---------------------------------------------------------------
    // User actions
    // ---------------------------------------------------------------

    /**
     * User accepted — from the notification button, the full-screen
     * activity, or Telecom (e.g. a paired watch). Queue the `answer`
     * action for the webview and bring the app up so the webview can
     * drain it and join the call.
     */
    fun answer(context: Context, callId: String) {
        val store = CallStateStore(context)
        val ring = store.getRing(callId) ?: return
        if (store.isAnswered(callId)) return // double-tap
        store.markAnswered(callId)
        store.enqueueAction(
            PendingCallAction(
                kind = "answer",
                callId = callId,
                wsToken = ring.wsToken,
                from = ring.from,
                personName = ring.personName,
                flowName = ring.flowName,
            ),
        )
        connections[callId]?.let {
            try {
                it.setActive()
            } catch (e: Exception) {
                Log.w(TAG, "setActive failed", e)
            }
        }
        dismissRingSurfaces(context, callId)
        launchApp(context)
        VoipPushPlugin.instance?.emitCallAction()
    }

    /** User declined an un-answered ring. */
    fun decline(context: Context, callId: String) {
        val store = CallStateStore(context)
        if (store.getRing(callId) == null) return
        store.enqueueAction(
            PendingCallAction(
                kind = "decline", callId = callId,
                wsToken = "", from = "", personName = "", flowName = "",
            ),
        )
        cleanUp(context, callId, DisconnectCause(DisconnectCause.REJECTED))
        VoipPushPlugin.instance?.emitCallAction()
    }

    /**
     * Telecom told us to disconnect (system UI End tap, emergency call
     * pre-emption, watch hangup). An answered call becomes an `end`
     * action (hangup request); an un-answered one is a decline.
     */
    fun telecomDisconnect(context: Context, callId: String) {
        val store = CallStateStore(context)
        if (store.getRing(callId) == null) return
        if (store.isAnswered(callId)) {
            store.enqueueAction(
                PendingCallAction(
                    kind = "end", callId = callId,
                    wsToken = "", from = "", personName = "", flowName = "",
                ),
            )
            cleanUp(context, callId, DisconnectCause(DisconnectCause.LOCAL))
            VoipPushPlugin.instance?.emitCallAction()
        } else {
            decline(context, callId)
        }
    }

    // ---------------------------------------------------------------
    // Non-user teardown (no action queued)
    // ---------------------------------------------------------------

    /**
     * `call.ring_cancel` push: answered elsewhere, caller hung up, or timeout.
     * Also arrives on THIS device right after it answers when the backend
     * fans out a cancel to all the user's devices — that's fine: by then
     * the answer action is queued, the surfaces are already dismissed, and
     * the webview is about to end the native call anyway, so this just
     * tears down the Telecom connection a moment earlier.
     */
    fun cancelRing(context: Context, callId: String, reason: String) {
        val cause = if (reason.contains("answer")) {
            DisconnectCause(DisconnectCause.ANSWERED_ELSEWHERE)
        } else {
            DisconnectCause(DisconnectCause.REMOTE)
        }
        cleanUp(context, callId, cause)
    }

    /** The webview reported the call over (`end_callkit_call` command). */
    fun endFromSpa(context: Context, callId: String) {
        cleanUp(context, callId, DisconnectCause(DisconnectCause.LOCAL))
    }

    /** Local expiry timer fired without a server cancel. */
    private fun expire(context: Context, callId: String) {
        val store = CallStateStore(context)
        if (store.getRing(callId) == null || store.isAnswered(callId)) return
        cleanUp(context, callId, DisconnectCause(DisconnectCause.MISSED))
    }

    /**
     * Tear down every ring surface for a call: Telecom connection,
     * notification, full-screen activity, expiry timer, stored ring.
     * Queued-but-undrained actions are deliberately NOT touched — a tap
     * that happened before a cancel raced in must still reach the webview.
     */
    private fun cleanUp(context: Context, callId: String, cause: DisconnectCause) {
        connections.remove(callId)?.let {
            try {
                it.setDisconnected(cause)
                it.destroy()
            } catch (e: Exception) {
                Log.w(TAG, "connection teardown failed", e)
            }
        }
        dismissRingSurfaces(context, callId)
        CallStateStore(context).removeRing(callId)
    }

    /** Remove the notification + full-screen activity + expiry timer only. */
    private fun dismissRingSurfaces(context: Context, callId: String) {
        expiryTokens.remove(callId)?.let { expiryHandler.removeCallbacks(it) }
        IncomingCallNotifier.cancel(context, callId)
        fullScreenActivity?.get()?.let {
            if (it.callId == callId) it.finish()
        }
    }

    /** Foreground the Tauri activity so the webview can drain the answer. */
    private fun launchApp(context: Context) {
        try {
            val intent: Intent? =
                context.packageManager.getLaunchIntentForPackage(context.packageName)
            intent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (intent != null) context.startActivity(intent)
        } catch (e: Exception) {
            // Background-activity-launch restriction — the queued action
            // still drains when the user opens the app (same behavior as
            // an iOS lock-screen answer).
            Log.w(TAG, "could not foreground app after answer", e)
        }
    }

    // ---------------------------------------------------------------
    // PhoneAccount
    // ---------------------------------------------------------------

    private fun phoneAccountHandle(context: Context): PhoneAccountHandle =
        PhoneAccountHandle(
            ComponentName(context, VoipConnectionService::class.java),
            PHONE_ACCOUNT_ID,
        )

    private fun registerPhoneAccount(
        context: Context,
        telecom: TelecomManager,
        handle: PhoneAccountHandle,
    ) {
        // registerPhoneAccount is idempotent — re-registering the same
        // handle just updates the account. The label is the host app's
        // user-visible name.
        val label = context.applicationInfo.loadLabel(context.packageManager).toString()
        val account = PhoneAccount.builder(handle, label)
            .setCapabilities(PhoneAccount.CAPABILITY_SELF_MANAGED)
            .build()
        telecom.registerPhoneAccount(account)
    }
}
