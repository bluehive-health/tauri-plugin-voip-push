package com.bluehive.voippush

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.lang.ref.WeakReference

/**
 * Full-screen incoming-call surface, raised over the lock screen by the
 * notification's full-screen intent (the closest Android gets to the
 * CallKit lock-screen ring). Deliberately minimal: caller identity plus
 * Answer/Decline, both routed through [IncomingCallManager] like every
 * other surface. Built programmatically so the plugin needs no layout
 * resources.
 */
class IncomingCallActivity : Activity() {

    var callId: String? = null
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        callId = intent.getStringExtra(IncomingCallNotifier.EXTRA_CALL_ID)
        val ring = callId?.let { CallStateStore(this).getRing(it) }
        if (ring == null || CallStateStore(this).isAnswered(ring.callId)) {
            finish()
            return
        }
        IncomingCallManager.fullScreenActivity = WeakReference(this)
        showOverLockScreen()
        setContentView(buildUi(ring))
    }

    override fun onDestroy() {
        if (IncomingCallManager.fullScreenActivity?.get() === this) {
            IncomingCallManager.fullScreenActivity = null
        }
        super.onDestroy()
    }

    private fun showOverLockScreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON,
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun buildUi(ring: RingPayload): LinearLayout {
        val density = resources.displayMetrics.density
        fun dp(value: Int): Int = (value * density).toInt()

        val callerName = ring.callerDisplayName(this)

        val heading = TextView(this).apply {
            text = getString(R.string.voip_push_incoming_call)
            setTextColor(Color.WHITE)
            textSize = 18f
            gravity = Gravity.CENTER
        }
        val caller = TextView(this).apply {
            text = callerName
            setTextColor(Color.WHITE)
            textSize = 28f
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(8), dp(16), dp(48))
        }
        val answer = Button(this).apply {
            text = getString(R.string.voip_push_answer)
            setBackgroundColor(Color.parseColor("#16A34A"))
            setTextColor(Color.WHITE)
            setOnClickListener {
                callId?.let { id -> IncomingCallManager.answer(this@IncomingCallActivity, id) }
                finish()
            }
        }
        val decline = Button(this).apply {
            text = getString(R.string.voip_push_decline)
            setBackgroundColor(Color.parseColor("#DC2626"))
            setTextColor(Color.WHITE)
            setOnClickListener {
                callId?.let { id -> IncomingCallManager.decline(this@IncomingCallActivity, id) }
                finish()
            }
        }
        val buttonParams = LinearLayout.LayoutParams(dp(140), dp(56)).apply {
            setMargins(dp(16), 0, dp(16), 0)
        }
        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            addView(decline, buttonParams)
            addView(answer, buttonParams)
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#111827"))
            addView(heading)
            addView(caller)
            addView(buttons)
        }
    }
}
