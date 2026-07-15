package com.legal.automation.ui

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import androidx.activity.ComponentActivity
import com.legal.automation.engine.SweepRunnerService

/**
 * Invisible helper for the automatic (scheduled) sweep: turns the screen on and
 * shows over the lock screen, asks to dismiss the keyguard (only works for a
 * non-secure lock — Swipe/None; a PIN/pattern/password can't be bypassed),
 * kicks off the sweep, then finishes. If the lock is secure the sweep still
 * starts but the vote pages won't be reachable until the phone is unlocked.
 */
class WakeActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        window.addFlags(
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD,
        )

        val keyguard = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && keyguard.isKeyguardLocked) {
            runCatching { keyguard.requestDismissKeyguard(this, null) }
        }

        // Give the wake/keyguard-dismiss a moment before launching the sweep.
        Handler(Looper.getMainLooper()).postDelayed({
            runCatching { SweepRunnerService.start(this) }
            finish()
        }, 2500)
    }

    companion object {
        fun intent(context: Context) = Intent(context, WakeActivity::class.java)
    }
}
