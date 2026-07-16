package com.legal.automation.engine

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * A small draggable overlay bubble shown while an automation/sweep is running,
 * so it can be paused/resumed without switching back to this app or leaving
 * the vote page. A single tap toggles [RunControl]; a drag repositions it.
 * Silently does nothing if "Display over other apps" hasn't been granted —
 * the run itself is unaffected either way.
 */
class FloatingControlService : Service() {

    private var windowManager: WindowManager? = null
    private var bubble: FrameLayout? = null
    private var icon: ImageView? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (bubble == null && Settings.canDrawOverlays(this)) {
            showBubble()
            scope.launch { RunControl.paused.collect { updateIcon(it) } }
        }
        return START_STICKY
    }

    private fun showBubble() {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        windowManager = wm

        val density = resources.displayMetrics.density
        val size = (56 * density).toInt()

        val container = FrameLayout(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xCC1976D2.toInt())
            }
        }
        val iconView = ImageView(this).apply {
            setImageResource(android.R.drawable.ic_media_pause)
            setColorFilter(Color.WHITE)
            val pad = (14 * density).toInt()
            setPadding(pad, pad, pad, pad)
        }
        container.addView(iconView, FrameLayout.LayoutParams(size, size))
        icon = iconView

        val params = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 40
            y = (200 * density).toInt()
        }

        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var dragged = false
        container.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX; downY = event.rawY
                    startX = params.x; startY = params.y
                    dragged = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - downX).toInt()
                    val dy = (event.rawY - downY).toInt()
                    if (abs(dx) > 12 || abs(dy) > 12) dragged = true
                    params.x = startX + dx
                    params.y = startY + dy
                    runCatching { wm.updateViewLayout(container, params) }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!dragged) RunControl.togglePause()
                    true
                }
                else -> false
            }
        }

        runCatching { wm.addView(container, params) }
        bubble = container
    }

    private fun updateIcon(paused: Boolean) {
        icon?.setImageResource(if (paused) android.R.drawable.ic_media_play else android.R.drawable.ic_media_pause)
    }

    override fun onDestroy() {
        scope.cancel()
        bubble?.let { b -> windowManager?.let { wm -> runCatching { wm.removeView(b) } } }
        bubble = null
        super.onDestroy()
    }

    companion object {
        fun start(context: Context) {
            if (!Settings.canDrawOverlays(context)) return
            runCatching { context.startService(Intent(context, FloatingControlService::class.java)) }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, FloatingControlService::class.java)) }
        }
    }
}
