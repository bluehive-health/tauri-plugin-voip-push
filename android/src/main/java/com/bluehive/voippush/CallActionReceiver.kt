package com.bluehive.voippush

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Receives Answer / Decline taps from the incoming-call notification's
 * action buttons and routes them to [IncomingCallManager] — the same
 * handlers Telecom and the full-screen activity use, so every surface
 * produces identical queued actions for the webview.
 */
class CallActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val callId = intent.getStringExtra(IncomingCallNotifier.EXTRA_CALL_ID) ?: return
        when (intent.action) {
            IncomingCallNotifier.ACTION_ANSWER -> IncomingCallManager.answer(context, callId)
            IncomingCallNotifier.ACTION_DECLINE -> IncomingCallManager.decline(context, callId)
        }
    }
}
