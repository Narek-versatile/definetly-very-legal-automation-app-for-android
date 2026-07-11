package com.legal.automation.shizuku

import kotlin.system.exitProcess

/**
 * Runs inside the Shizuku-spawned process (adb-shell or root uid). Anything
 * here executes with those elevated privileges, which is what lets us launch
 * apps and inject text/taps that a normal app cannot.
 *
 * Shizuku instantiates this via its no-arg constructor.
 */
class ShizukuUserService : IUserService.Stub() {

    override fun destroy() {
        exitProcess(0)
    }

    override fun exec(command: String): String {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
            val out = process.inputStream.bufferedReader().readText()
            val err = process.errorStream.bufferedReader().readText()
            val code = process.waitFor()
            buildString {
                append(out)
                if (err.isNotBlank()) {
                    if (isNotEmpty()) append('\n')
                    append(err)
                }
                if (code != 0) {
                    if (isNotEmpty()) append('\n')
                    append("[exit $code]")
                }
            }.trim()
        } catch (t: Throwable) {
            "ERROR: ${t.message}"
        }
    }
}
