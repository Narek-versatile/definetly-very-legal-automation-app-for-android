package com.legal.automation.shizuku

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.util.Log
import com.legal.automation.BuildConfig
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull
import rikka.shizuku.Shizuku

/**
 * Thin wrapper around Shizuku that (a) reports availability/permission for the
 * UI and (b) provides the low-level primitives — launch app, type text,
 * coordinate tap — by running shell commands in the bound [ShizukuUserService].
 *
 * Everything degrades gracefully: if Shizuku isn't installed/running/granted,
 * [exec] returns null and callers fall back to accessibility-only behaviour.
 */
object ShizukuManager {

    private const val TAG = "ShizukuManager"
    const val PERMISSION_REQUEST_CODE = 4711

    enum class Status { UNAVAILABLE, NEEDS_PERMISSION, READY }

    private val _status = MutableStateFlow(Status.UNAVAILABLE)
    val status: StateFlow<Status> = _status.asStateFlow()

    private var userService: IUserService? = null
    private val permissionWaiters = mutableListOf<CompletableDeferred<Boolean>>()

    private val userServiceArgs by lazy {
        Shizuku.UserServiceArgs(
            ComponentName(BuildConfig.APPLICATION_ID, ShizukuUserService::class.java.name),
        )
            .daemon(false)
            .processNameSuffix("shizuku")
            .debuggable(BuildConfig.DEBUG)
            .version(BuildConfig.VERSION_CODE)
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            userService = binder?.let { IUserService.Stub.asInterface(it) }
            bindWaiter?.complete(userService != null)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            userService = null
        }
    }

    private var bindWaiter: CompletableDeferred<Boolean>? = null

    private val permissionListener =
        Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
            if (requestCode == PERMISSION_REQUEST_CODE) {
                val granted = grantResult == PackageManager.PERMISSION_GRANTED
                permissionWaiters.forEach { it.complete(granted) }
                permissionWaiters.clear()
                refreshStatus()
            }
        }

    fun init() {
        runCatching { Shizuku.addRequestPermissionResultListener(permissionListener) }
        refreshStatus()
    }

    fun refreshStatus() {
        _status.value = when {
            !isAvailable() -> Status.UNAVAILABLE
            !hasPermission() -> Status.NEEDS_PERMISSION
            else -> Status.READY
        }
    }

    fun isAvailable(): Boolean = runCatching { Shizuku.pingBinder() }.getOrDefault(false)

    fun hasPermission(): Boolean = runCatching {
        isAvailable() && !Shizuku.isPreV11() &&
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    /** Kicks off the system permission prompt and suspends for the result. */
    suspend fun requestPermission(): Boolean {
        if (!isAvailable()) return false
        if (hasPermission()) return true
        val waiter = CompletableDeferred<Boolean>()
        permissionWaiters += waiter
        runCatching { Shizuku.requestPermission(PERMISSION_REQUEST_CODE) }
            .onFailure {
                permissionWaiters -= waiter
                return false
            }
        return withTimeoutOrNull(60_000) { waiter.await() } ?: false
    }

    private suspend fun ensureBound(): IUserService? {
        userService?.let { return it }
        if (!hasPermission()) return null
        val waiter = CompletableDeferred<Boolean>()
        bindWaiter = waiter
        runCatching { Shizuku.bindUserService(userServiceArgs, connection) }
            .onFailure {
                Log.w(TAG, "bindUserService failed", it)
                return null
            }
        val ok = withTimeoutOrNull(10_000) { waiter.await() } ?: false
        return if (ok) userService else null
    }

    /** Runs a shell command; null if Shizuku is not usable. */
    suspend fun exec(command: String): String? {
        val svc = ensureBound() ?: return null
        return runCatching { svc.exec(command) }.getOrNull()
    }

    suspend fun launchApp(packageName: String): Boolean {
        val out = exec("monkey -p ${shArg(packageName)} -c android.intent.category.LAUNCHER 1")
            ?: return false
        return !out.contains("No activities found") && !out.startsWith("ERROR")
    }

    suspend fun typeText(text: String): Boolean {
        if (text.isEmpty()) return true
        val parts = text.split(" ")
        val cmds = buildList {
            parts.forEachIndexed { index, word ->
                if (index > 0) add("input keyevent 62") // space
                if (word.isNotEmpty()) add("input text ${shArg(word)}")
            }
        }
        val out = exec(cmds.joinToString(" && ")) ?: return false
        return !out.startsWith("ERROR")
    }

    suspend fun tap(x: Int, y: Int): Boolean {
        val out = exec("input tap $x $y") ?: return false
        return !out.startsWith("ERROR")
    }

    fun unbind() {
        runCatching { Shizuku.unbindUserService(userServiceArgs, connection, true) }
        userService = null
    }

    /** Wraps a value in single quotes for safe use inside `sh -c`. */
    private fun shArg(value: String): String = "'" + value.replace("'", "'\\''") + "'"
}
