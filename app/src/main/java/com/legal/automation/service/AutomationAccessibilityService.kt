package com.legal.automation.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.util.DisplayMetrics
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.legal.automation.App
import com.legal.automation.engine.AlertManager
import com.legal.automation.model.ScrollDirection
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.io.FileOutputStream
import kotlin.coroutines.resume

/**
 * The high-level automation engine's hands and eyes. Finds nodes by text or
 * resource-id, taps/scrolls via gesture dispatch, verifies on-screen content
 * and captures failure screenshots.
 *
 * A single instance is exposed statically so the [com.legal.automation.engine]
 * package can drive it while the user is in another app.
 */
class AutomationAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: AutomationAccessibilityService? = null
            private set

        val isRunning: Boolean get() = instance != null

        private const val ACTION_SCAN = "com.legal.automation.action.SCAN_SCREEN"
        private const val CONTROL_NOTIF_ID = 7
    }

    // Fired from the ongoing notification's "Scan screen" action: dumps every
    // readable text/id on the current screen so you can see what the engine
    // can (and can't) target — the key diagnostic for web pages.
    private val scanReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            App.instance.alerts.showScreenDump(dumpScreenText())
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        ContextCompat.registerReceiver(
            this,
            scanReceiver,
            IntentFilter(ACTION_SCAN),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        postControlNotification()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        runCatching { unregisterReceiver(scanReceiver) }
        runCatching { NotificationManagerCompat.from(this).cancel(CONTROL_NOTIF_ID) }
        super.onDestroy()
    }

    // We don't react to events; the engine polls the tree on demand.
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    private fun postControlNotification() {
        val scan = PendingIntent.getBroadcast(
            this,
            0,
            Intent(ACTION_SCAN).setPackage(packageName),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(this, AlertManager.CHANNEL_RUNNER)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("Automation engine active")
            .setContentText("Tap “Scan screen” over any app to list its text & ids")
            .addAction(android.R.drawable.ic_menu_search, "Scan screen", scan)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        runCatching { NotificationManagerCompat.from(this).notify(CONTROL_NOTIF_ID, notification) }
    }

    /** Every text / contentDescription / view-id visible across all windows. */
    fun dumpScreenText(): List<String> {
        val out = LinkedHashSet<String>()
        for (root in candidateRoots()) collectText(root, out)
        return out.toList()
    }

    private fun collectText(node: AccessibilityNodeInfo?, out: MutableSet<String>) {
        if (node == null) return
        node.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { out.add("“$it”") }
        node.contentDescription?.toString()?.trim()?.takeIf { it.isNotEmpty() }
            ?.let { out.add("desc: $it") }
        node.viewIdResourceName?.takeIf { it.isNotBlank() }?.let { out.add("id: $it") }
        for (i in 0 until node.childCount) collectText(node.getChild(i), out)
    }

    // --- Finding -----------------------------------------------------------

    /**
     * Every window we can read, not just the active one. Browsers render the
     * page in their own window and there are often overlays (ad "✕" buttons,
     * PiP, keyboards) on top; searching only [rootInActiveWindow] misses the
     * web content. Requires flagRetrieveInteractiveWindows (set in config).
     */
    private fun candidateRoots(): List<AccessibilityNodeInfo> {
        val roots = ArrayList<AccessibilityNodeInfo>()
        rootInActiveWindow?.let { roots.add(it) }
        try {
            windows?.forEach { window -> window.root?.let { roots.add(it) } }
        } catch (_: Exception) {
            // Some OEM builds throw when enumerating windows; the active root
            // is still in the list.
        }
        return roots
    }

    fun findByText(text: String, exact: Boolean): AccessibilityNodeInfo? {
        for (root in candidateRoots()) {
            val matches = root.findAccessibilityNodeInfosByText(text) ?: continue
            val hit = if (exact) {
                matches.firstOrNull {
                    it.text?.toString() == text || it.contentDescription?.toString() == text
                }
            } else {
                matches.firstOrNull()
            }
            if (hit != null) return hit
        }
        return null
    }

    fun findById(viewId: String): AccessibilityNodeInfo? {
        for (root in candidateRoots()) {
            root.findAccessibilityNodeInfosByViewId(viewId)?.firstOrNull()?.let { return it }
        }
        return null
    }

    fun isTextPresent(text: String): Boolean =
        candidateRoots().any { (it.findAccessibilityNodeInfosByText(text)?.isNotEmpty()) == true }

    suspend fun waitForText(text: String, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (isTextPresent(text)) return true
            delay(250)
        }
        return false
    }

    // --- Acting ------------------------------------------------------------

    suspend fun clickText(text: String, exact: Boolean): Boolean {
        val node = findByText(text, exact) ?: return false
        return clickNode(node)
    }

    suspend fun clickId(viewId: String): Boolean {
        val node = findById(viewId) ?: return false
        return clickNode(node)
    }

    /** Focuses a field and returns it, so callers can then set/type text. */
    fun focusField(intoText: String?, intoId: String?): AccessibilityNodeInfo? {
        val node = when {
            intoId != null -> findById(intoId)
            intoText != null -> findByText(intoText, exact = false)
            else -> null
        } ?: return null
        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        return node
    }

    /** Accessibility fallback for typing when Shizuku is unavailable. */
    fun setText(node: AccessibilityNodeInfo, text: String): Boolean {
        val args = android.os.Bundle().apply {
            putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                text,
            )
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    /**
     * Types into whatever editable field currently has input focus — the
     * non-Shizuku path when no explicit target field was given.
     */
    fun setTextOnFocused(text: String): Boolean {
        for (root in candidateRoots()) {
            val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                ?: root.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)
                ?: continue
            if (focused.isEditable) return setText(focused, text)
        }
        return false
    }

    private suspend fun clickNode(node: AccessibilityNodeInfo): Boolean {
        var target: AccessibilityNodeInfo? = node
        while (target != null && !target.isClickable) {
            target = target.parent
        }
        if (target != null && target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            return true
        }
        // Fall back to a gesture tap at the node's centre.
        val bounds = Rect().also { node.getBoundsInScreen(it) }
        if (bounds.width() <= 0 || bounds.height() <= 0) return false
        return tap(bounds.centerX(), bounds.centerY())
    }

    suspend fun tap(x: Int, y: Int): Boolean {
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 60)
        return dispatchAwait(GestureDescription.Builder().addStroke(stroke).build())
    }

    suspend fun scroll(direction: ScrollDirection): Boolean {
        val metrics = displayMetrics()
        val w = metrics.widthPixels
        val h = metrics.heightPixels
        val cx = w / 2f
        val cy = h / 2f
        // Swipe opposite to the reading direction of travel.
        val (startX, startY, endX, endY) = when (direction) {
            ScrollDirection.DOWN -> listOf(cx, h * 0.72f, cx, h * 0.28f)
            ScrollDirection.UP -> listOf(cx, h * 0.28f, cx, h * 0.72f)
            ScrollDirection.LEFT -> listOf(w * 0.72f, cy, w * 0.28f, cy)
            ScrollDirection.RIGHT -> listOf(w * 0.28f, cy, w * 0.72f, cy)
        }
        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, 300)
        return dispatchAwait(GestureDescription.Builder().addStroke(stroke).build())
    }

    private suspend fun dispatchAwait(gesture: GestureDescription): Boolean =
        suspendCancellableCoroutine { cont ->
            val ok = dispatchGesture(
                gesture,
                object : GestureResultCallback() {
                    override fun onCompleted(description: GestureDescription?) {
                        if (cont.isActive) cont.resume(true)
                    }

                    override fun onCancelled(description: GestureDescription?) {
                        if (cont.isActive) cont.resume(false)
                    }
                },
                null,
            )
            if (!ok && cont.isActive) cont.resume(false)
        }

    // --- Screenshots -------------------------------------------------------

    /** Saves a screenshot of the current screen to [file]; false on failure. */
    suspend fun screenshotTo(file: File): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
        return suspendCancellableCoroutine { cont ->
            takeScreenshot(
                android.view.Display.DEFAULT_DISPLAY,
                mainExecutor,
                object : TakeScreenshotCallback {
                    override fun onSuccess(result: ScreenshotResult) {
                        val bitmap = try {
                            Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace)
                        } finally {
                            result.hardwareBuffer.close()
                        }
                        val ok = bitmap?.let {
                            runCatching {
                                FileOutputStream(file).use { out ->
                                    it.copy(Bitmap.Config.ARGB_8888, false)
                                        .compress(Bitmap.CompressFormat.PNG, 90, out)
                                }
                            }.isSuccess
                        } ?: false
                        if (cont.isActive) cont.resume(ok)
                    }

                    override fun onFailure(errorCode: Int) {
                        if (cont.isActive) cont.resume(false)
                    }
                },
            )
        }
    }

    @Suppress("DEPRECATION")
    private fun displayMetrics(): DisplayMetrics {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        wm.defaultDisplay.getRealMetrics(metrics)
        return metrics
    }
}
