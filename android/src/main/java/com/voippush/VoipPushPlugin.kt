package com.voippush

import android.Manifest
import android.app.Activity
import android.content.Context
import android.os.Build
import android.provider.Settings
import android.webkit.WebView
import app.tauri.PermissionState
import app.tauri.annotation.Command
import app.tauri.annotation.InvokeArg
import app.tauri.annotation.Permission
import app.tauri.annotation.PermissionCallback
import app.tauri.annotation.TauriPlugin
import app.tauri.plugin.Invoke
import app.tauri.plugin.JSArray
import app.tauri.plugin.JSObject
import app.tauri.plugin.Plugin
import com.google.firebase.messaging.FirebaseMessaging

/**
 * FCM push-token acquisition + native incoming-call bridge — the Android
 * counterpart of ios/Sources/VoipPushPlugin.swift.
 *
 * Token flow: the webview invokes `plugin:voip-push|register_for_push`,
 * which lands here as `registerForPush`: request the POST_NOTIFICATIONS
 * runtime permission (Android 13+; auto-granted below), fetch the FCM
 * registration token, and resolve with `{ token, deviceId, appVersion,
 * platform }`. The app then sends the payload to its own backend to
 * register the device. Mid-session rotations surface via the `fcm_token`
 * plugin event (VoipPushMessagingService.onNewToken).
 *
 * Call flow: VoipPushMessagingService + IncomingCallManager ring the phone
 * natively (Telecom + CallStyle notification). Answer/Decline/End taps are
 * queued durably (CallStateStore) and announced with a `call_action` event;
 * the webview drains the queue via `drainPendingCallActions` — on the live
 * event when it's running, or after its own startup on a cold start —
 * exactly like iOS. `endCall` dismisses the native call when the webview
 * reports it over.
 *
 * Requires the consuming app module to have Firebase configured
 * (google-services.json + the Google Services Gradle plugin). Without it,
 * FirebaseMessaging throws and we reject, which the caller can swallow.
 */
@TauriPlugin(
    permissions = [
        Permission(strings = [Manifest.permission.POST_NOTIFICATIONS], alias = "notifications")
    ]
)
class VoipPushPlugin(activity: Activity) : Plugin(activity) {

    /**
     * Process-scoped context for everything that doesn't strictly need the
     * Activity — the static [instance] outlives Activity recreations, so
     * holding the application context avoids pinning a dead Activity's
     * resources.
     */
    private val appContext = activity.applicationContext

    override fun load(webView: WebView) {
        super.load(webView)
        instance = this
    }

    @Command
    fun registerForPush(invoke: Invoke) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            getPermissionState("notifications") != PermissionState.GRANTED
        ) {
            requestPermissionForAlias("notifications", invoke, "notificationPermissionCallback")
        } else {
            fetchToken(invoke)
        }
    }

    @PermissionCallback
    fun notificationPermissionCallback(invoke: Invoke) {
        if (getPermissionState("notifications") != PermissionState.GRANTED) {
            invoke.reject("notification permission denied")
            return
        }
        fetchToken(invoke)
    }

    /**
     * Return (and clear) the queued call actions. The webview calls this
     * after it boots on a cold start, and again whenever a `call_action`
     * event fires while it's alive — draining is what dedupes the two paths.
     */
    @Command
    fun drainPendingCallActions(invoke: Invoke) {
        val actions = CallStateStore(appContext).drainActions()
        val arr = JSArray()
        for (action in actions) {
            arr.put(
                JSObject().apply {
                    put("kind", action.kind)
                    put("callId", action.callId)
                    action.joinToken?.let { put("joinToken", it) }
                    action.from?.let { put("from", it) }
                    action.personName?.let { put("personName", it) }
                    action.lineName?.let { put("lineName", it) }
                },
            )
        }
        val result = JSObject()
        result.put("actions", arr)
        invoke.resolve(result)
    }

    /**
     * The webview reports the call is over (hangup, answered in-app, server
     * cancel) — dismiss the native call UI if it's still up.
     */
    @Command
    fun endCall(invoke: Invoke) {
        val args = invoke.parseArgs(EndCallArgs::class.java)
        if (args.callId.isNotEmpty()) {
            IncomingCallManager.endFromSpa(appContext, args.callId)
        }
        invoke.resolve()
    }

    /** A call action was queued natively — tell a live webview to drain. */
    fun emitCallAction() {
        try {
            trigger("call_action", JSObject())
        } catch (e: Exception) {
            /* webview not ready — the cold-start drain picks the action up */
        }
    }

    /** FCM token rotated mid-session — hand it to the webview so it re-registers. */
    fun emitFcmToken(token: String) {
        try {
            trigger("fcm_token", JSObject().put("token", token))
        } catch (e: Exception) {
            /* webview not ready — the next launch's registerForPush fetches
               the current (rotated) token anyway */
        }
    }

    private fun fetchToken(invoke: Invoke) {
        val messaging = try {
            FirebaseMessaging.getInstance()
        } catch (e: IllegalStateException) {
            // Firebase not configured (no google-services.json yet).
            invoke.reject("firebase not initialized: ${e.message}")
            return
        }
        messaging.token.addOnCompleteListener { task ->
            if (!task.isSuccessful) {
                invoke.reject("failed to get FCM token: ${task.exception?.message}")
                return@addOnCompleteListener
            }
            val result = JSObject()
            result.put("token", task.result)
            result.put("deviceId", stableDeviceId(appContext))
            result.put("appVersion", appVersion())
            result.put("platform", "android")
            invoke.resolve(result)
        }
    }

    private fun appVersion(): String = try {
        appContext.packageManager.getPackageInfo(appContext.packageName, 0).versionName ?: ""
    } catch (e: Exception) {
        ""
    }

    companion object {
        /** The live plugin instance, for native→webview events. Set on load. */
        @Volatile
        var instance: VoipPushPlugin? = null

        /** Stable per app-install+signing-key+user; the Android analog of iOS `identifierForVendor`. */
        fun stableDeviceId(context: Context): String =
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
                ?: "unknown"
    }
}

@InvokeArg
class EndCallArgs {
    var callId: String = ""
}
