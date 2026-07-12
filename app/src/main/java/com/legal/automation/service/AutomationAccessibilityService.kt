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
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.legal.automation.App
import com.legal.automation.engine.AlertManager
import com.legal.automation.model.ScrollDirection
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
    // can (and can't) target — the key diagnostic for web pages. We collapse
    // the notification shade first and wait a beat, otherwise the scan just
    // reads the shade/System UI instead of the app underneath.
    private val scanReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                runCatching { performGlobalAction(GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE) }
            }
            Handler(Looper.getMainLooper()).postDelayed({ performScan() }, 1800)
        }
    }

    private fun performScan() {
        val items = dumpScreenText()
        val pkg = rootInActiveWindow?.packageName?.toString() ?: "screen"
        val label = appLabelFor(pkg)
        App.instance.applicationScope.launch { App.instance.scans.add(label, items) }
        App.instance.alerts.showScreenDump(label, items)
    }

    private fun appLabelFor(pkg: String): String = runCatching {
        val pm = packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)

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

    /**
     * Depth-first walk over every candidate root, returning the first node the
     * predicate accepts. We walk children by hand instead of using
     * findAccessibilityNodeInfos*byText/ViewId because those framework search
     * APIs do NOT reliably descend into Chrome's web-content virtual tree —
     * the manual walk (same one the screen scan uses) does, so it finds text
     * and ids inside web pages.
     */
    private fun findFirst(match: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        for (root in candidateRoots()) {
            searchNode(root, match, 0)?.let { return it }
        }
        return null
    }

    private fun searchNode(
        node: AccessibilityNodeInfo?,
        match: (AccessibilityNodeInfo) -> Boolean,
        depth: Int,
    ): AccessibilityNodeInfo? {
        if (node == null || depth > 80) return null
        if (runCatching { match(node) }.getOrDefault(false)) return node
        for (i in 0 until node.childCount) {
            searchNode(node.getChild(i), match, depth + 1)?.let { return it }
        }
        return null
    }

    fun findByText(text: String, exact: Boolean): AccessibilityNodeInfo? = findFirst { node ->
        val t = node.text?.toString()
        val d = node.contentDescription?.toString()
        if (exact) {
            t == text || d == text
        } else {
            t?.contains(text, ignoreCase = true) == true ||
                d?.contains(text, ignoreCase = true) == true
        }
    }

    fun findById(viewId: String): AccessibilityNodeInfo? = findFirst { node ->
        val id = node.viewIdResourceName
        id == viewId || id?.endsWith("/$viewId") == true
    }

    fun isTextPresent(text: String): Boolean = findByText(text, exact = false) != null

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
        // Prefer a node that's actually clickable (the button), not e.g. a
        // matching heading/breadcrumb with the same word.
        val node = findClickableByText(text, exact) ?: findByText(text, exact) ?: return false
        return clickNode(node)
    }

    suspend fun clickId(viewId: String): Boolean {
        val node = findById(viewId) ?: return false
        return clickNode(node)
    }

    private fun findClickableByText(text: String, exact: Boolean): AccessibilityNodeInfo? =
        findFirst { node ->
            val t = node.text?.toString()
            val d = node.contentDescription?.toString()
            val matches = if (exact) {
                t == text || d == text
            } else {
                t?.contains(text, ignoreCase = true) == true ||
                    d?.contains(text, ignoreCase = true) == true
            }
            matches && isClickableChain(node)
        }

    /** True if the node or a near ancestor is clickable (web buttons expose the
     *  text on a child of the clickable element). */
    private fun isClickableChain(node: AccessibilityNodeInfo): Boolean {
        var n: AccessibilityNodeInfo? = node
        var hops = 0
        while (n != null && hops < 4) {
            if (n.isClickable) return true
            n = n.parent
            hops++
        }
        return false
    }

    /**
     * Focuses a text field and returns the editable node, so callers can type
     * into it. When targeting by text we deliberately prefer an actual editable
     * input (matched via its placeholder/hint, or resolved from a nearby label)
     * over the label itself — clicking a bare label doesn't reliably focus its
     * input, which left fields empty on some sites.
     */
    fun focusField(intoText: String?, intoId: String?): AccessibilityNodeInfo? {
        val match = when {
            intoId != null -> findById(intoId)
            intoText != null -> findEditableByText(intoText) ?: findByText(intoText, exact = false)
            else -> null
        } ?: return null

        val target = when {
            match.isEditable -> match
            else -> findNearbyEditable(match) ?: match
        }
        target.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        return target
    }

    /** An editable node whose text/description/hint matches — i.e. the input,
     *  not its label (placeholders show up as hintText/contentDescription). */
    private fun findEditableByText(text: String): AccessibilityNodeInfo? = findFirst { node ->
        if (!node.isEditable) return@findFirst false
        val candidates = listOf(
            node.text?.toString(),
            node.contentDescription?.toString(),
            node.hintText?.toString(),
        )
        candidates.any { it?.contains(text, ignoreCase = true) == true }
    }

    /** From a matched label/text node, find the closest editable field by
     *  climbing a few ancestors and searching each subtree. */
    private fun findNearbyEditable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var ancestor = node.parent
        var hops = 0
        while (ancestor != null && hops < 3) {
            searchNode(ancestor, { it.isEditable }, 0)?.let { return it }
            ancestor = ancestor.parent
            hops++
        }
        return null
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
        // Prefer a REAL gesture tap at the element's on-screen centre. For web
        // buttons, accessibility ACTION_CLICK frequently reports success without
        // firing the button's JavaScript, whereas a genuine touch does. Only
        // fall back to ACTION_CLICK when the node has no usable on-screen bounds
        // (e.g. off-screen / zero-size), where a coordinate tap can't work.
        val metrics = displayMetrics()
        fun onScreen(b: Rect) = b.width() > 0 && b.height() > 0 &&
            b.centerX() in 1 until metrics.widthPixels &&
            b.centerY() in 1 until metrics.heightPixels

        var bounds = Rect().also { node.getBoundsInScreen(it) }
        if (!onScreen(bounds)) {
            // Button below the fold: scroll it into view, then re-read bounds.
            runCatching { node.performAction(AccessibilityNodeInfo.ACTION_SHOW_ON_SCREEN) }
            delay(350)
            runCatching { node.refresh() }
            bounds = Rect().also { node.getBoundsInScreen(it) }
        }
        if (onScreen(bounds) && tap(bounds.centerX(), bounds.centerY())) return true

        var target: AccessibilityNodeInfo? = node
        while (target != null && !target.isClickable) {
            target = target.parent
        }
        return target?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
    }

    suspend fun tap(x: Int, y: Int): Boolean {
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 60)
        return dispatchAwait(GestureDescription.Builder().addStroke(stroke).build())
    }

    /** Closes the soft keyboard, but only if one is actually showing (so the
     *  Back action can't accidentally navigate the page back). */
    fun hideKeyboard(): Boolean {
        val imeOpen = runCatching {
            windows?.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD } == true
        }.getOrDefault(false)
        return if (imeOpen) performGlobalAction(GLOBAL_ACTION_BACK) else false
    }

    /** A short vertical swipe by [pixels] from screen centre. */
    suspend fun swipeSmall(down: Boolean, pixels: Int): Boolean {
        val metrics = displayMetrics()
        val cx = metrics.widthPixels / 2f
        val cy = metrics.heightPixels / 2f
        val half = pixels / 2f
        val startY = if (down) cy - half else cy + half
        val endY = if (down) cy + half else cy - half
        val path = Path().apply {
            moveTo(cx, startY)
            lineTo(cx, endY)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, 200)
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
