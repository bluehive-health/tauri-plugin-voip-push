package com.bluehive.voippush

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

/**
 * FCM entry point — the Android counterpart of the PushKit half of
 * ios/Sources/VoipPushPlugin.swift. High-priority data-only messages (see
 * README for the payload shape) wake this service even when the app is
 * backgrounded or killed:
 *
 *   - `call.ring`        → report a Telecom incoming call + CallStyle
 *                          full-screen notification.
 *   - `call.ring_cancel` → tear the ring down (answered elsewhere,
 *                          caller hung up, or the ring timed out).
 *
 * Alert pushes should use FCM `notification` messages that Android renders
 * natively, so they never reach this service — no handling needed here.
 *
 * `onNewToken` covers mid-session FCM token rotation: the webview
 * re-registers the device (either via the live `fcm_token` plugin event or
 * on the next `registerForPush`).
 */
class VoipPushMessagingService : FirebaseMessagingService() {

    override fun onMessageReceived(message: RemoteMessage) {
        val ring = RingPayload.fromFcmData(message.data) ?: return
        // Telecom + notification APIs expect the main thread (FCM invokes
        // this on a background executor).
        Handler(Looper.getMainLooper()).post {
            when (ring.action) {
                "ring" -> IncomingCallManager.startRing(applicationContext, ring)
                "cancel" -> IncomingCallManager.cancelRing(applicationContext, ring.callId, ring.reason)
            }
        }
    }

    override fun onNewToken(token: String) {
        Log.i("voip-push", "FCM token rotated")
        // Live webview: hand the token straight to the webview so it
        // re-registers now. Dead webview: nothing to do — every launch's
        // registerForPush fetches the current token from FirebaseMessaging,
        // so the rotation is picked up on next open.
        VoipPushPlugin.instance?.emitFcmToken(token)
    }
}
