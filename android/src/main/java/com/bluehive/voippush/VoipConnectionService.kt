package com.bluehive.voippush

import android.telecom.Connection
import android.telecom.ConnectionRequest
import android.telecom.ConnectionService
import android.telecom.DisconnectCause
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager

/**
 * Self-managed Telecom ConnectionService — the Android counterpart of
 * CXProvider in ios/Sources/VoipPushPlugin.swift. Telecom binds to this
 * when [IncomingCallManager.startRing] calls `addNewIncomingCall`, and
 * routes system-level call events (answer from a watch/Bluetooth,
 * emergency-call pre-emption) through the [VoipConnection] we return.
 */
class VoipConnectionService : ConnectionService() {

    override fun onCreateIncomingConnection(
        connectionManagerPhoneAccount: PhoneAccountHandle?,
        request: ConnectionRequest,
    ): Connection {
        val callId = callIdFrom(request)
        val connection = VoipConnection(applicationContext, callId)
        connection.connectionProperties = Connection.PROPERTY_SELF_MANAGED
        connection.audioModeIsVoip = true
        val ring = callId?.let { CallStateStore(applicationContext).getRing(it) }
        if (ring != null) {
            if (ring.from.isNotEmpty()) {
                connection.setAddress(request.address, TelecomManager.PRESENTATION_ALLOWED)
            }
            connection.setCallerDisplayName(
                ring.callerDisplayName(applicationContext),
                TelecomManager.PRESENTATION_ALLOWED,
            )
        }
        connection.setRinging()
        if (callId != null) IncomingCallManager.onConnectionCreated(callId, connection)
        return connection
    }

    override fun onCreateIncomingConnectionFailed(
        connectionManagerPhoneAccount: PhoneAccountHandle?,
        request: ConnectionRequest?,
    ) {
        // Telecom refused the call (constraints, ongoing carrier call…).
        // Fall back to the notification-only ring so the user still sees it.
        val callId = request?.let { callIdFrom(it) } ?: return
        IncomingCallManager.showIncomingCallUi(applicationContext, callId)
    }

    private fun callIdFrom(request: ConnectionRequest): String? {
        val direct = request.extras?.getString(EXTRA_CALL_ID)
        if (!direct.isNullOrEmpty()) return direct
        // Depending on the OS version our extras may arrive nested under
        // EXTRA_INCOMING_CALL_EXTRAS instead of flattened.
        return request.extras
            ?.getBundle(TelecomManager.EXTRA_INCOMING_CALL_EXTRAS)
            ?.getString(EXTRA_CALL_ID)
    }

    companion object {
        const val EXTRA_CALL_ID = "com.bluehive.voippush.CALL_ID"
    }
}

/**
 * One incoming call as Telecom sees it. User intent flows to
 * [IncomingCallManager]; state transitions (`setActive`,
 * `setDisconnected`) flow back from the manager.
 */
class VoipConnection(
    private val appContext: android.content.Context,
    private val callId: String?,
) : Connection() {

    /** Telecom wants us to show the ring UI (self-managed contract). */
    override fun onShowIncomingCallUi() {
        callId?.let { IncomingCallManager.showIncomingCallUi(appContext, it) }
    }

    override fun onAnswer() {
        callId?.let { IncomingCallManager.answer(appContext, it) }
    }

    override fun onAnswer(videoState: Int) {
        onAnswer()
    }

    override fun onReject() {
        callId?.let { IncomingCallManager.decline(appContext, it) }
            ?: run {
                setDisconnected(DisconnectCause(DisconnectCause.REJECTED))
                destroy()
            }
    }

    override fun onDisconnect() {
        callId?.let { IncomingCallManager.telecomDisconnect(appContext, it) }
            ?: run {
                setDisconnected(DisconnectCause(DisconnectCause.LOCAL))
                destroy()
            }
    }
}
