package com.bluehive.voippush

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.Person

/**
 * Incoming-call notification: a max-importance CallStyle notification with
 * Answer/Decline actions and a full-screen intent that raises
 * [IncomingCallActivity] over the lock screen. This is the app-provided
 * ring UI that self-managed Telecom requires (Connection.onShowIncomingCallUi),
 * and the entire ring surface when Telecom refuses the call.
 *
 * Android 14+ gates full-screen intents behind a user-manageable special
 * permission — when it's off, the same notification degrades to an
 * actionable heads-up banner, which is exactly the documented fallback.
 */
object IncomingCallNotifier {

    private const val CHANNEL_ID = "incoming_calls"

    const val ACTION_ANSWER = "com.bluehive.voippush.ACTION_ANSWER"
    const val ACTION_DECLINE = "com.bluehive.voippush.ACTION_DECLINE"
    const val EXTRA_CALL_ID = "call_id"

    fun show(context: Context, ring: RingPayload) {
        val manager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        ensureChannel(context, manager)

        val callerName = ring.callerDisplayName(context)
        val caller = Person.Builder().setName(callerName).setImportant(true).build()

        val answerIntent = actionIntent(context, ACTION_ANSWER, ring.callId)
        val declineIntent = actionIntent(context, ACTION_DECLINE, ring.callId)
        val fullScreen = fullScreenIntent(context, ring.callId)

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.sym_call_incoming)
            .setContentTitle(context.getString(R.string.voip_push_incoming_call))
            .setContentText(callerName)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setAutoCancel(false)
            .setStyle(NotificationCompat.CallStyle.forIncomingCall(caller, declineIntent, answerIntent))
            .addPerson(caller)
            .setContentIntent(fullScreen)
            .setFullScreenIntent(fullScreen, true)
        if (ring.expiresAt > 0) {
            val remaining = ring.expiresAt - System.currentTimeMillis()
            if (remaining > 0) builder.setTimeoutAfter(remaining)
        }

        manager.notify(notificationId(ring.callId), builder.build())
    }

    fun cancel(context: Context, callId: String) {
        val manager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.cancel(notificationId(callId))
    }

    private fun notificationId(callId: String): Int = callId.hashCode()

    private fun actionIntent(context: Context, action: String, callId: String): PendingIntent {
        val intent = Intent(context, CallActionReceiver::class.java)
            .setAction(action)
            .putExtra(EXTRA_CALL_ID, callId)
        return PendingIntent.getBroadcast(
            context,
            (action + callId).hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun fullScreenIntent(context: Context, callId: String): PendingIntent {
        val intent = Intent(context, IncomingCallActivity::class.java)
            .putExtra(EXTRA_CALL_ID, callId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION)
        return PendingIntent.getActivity(
            context,
            callId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun ensureChannel(context: Context, manager: NotificationManager) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.voip_push_incoming_calls_channel),
            NotificationManager.IMPORTANCE_HIGH,
        )
        channel.setSound(
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE),
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build(),
        )
        channel.enableVibration(true)
        channel.lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        manager.createNotificationChannel(channel)
    }
}
