package com.legal.automation.engine

import android.app.SearchManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.legal.automation.data.SettingsStore
import com.legal.automation.model.Automation
import com.legal.automation.model.Step
import com.legal.automation.model.UrlTarget
import com.legal.automation.service.AutomationAccessibilityService
import com.legal.automation.shizuku.ShizukuManager
import kotlinx.coroutines.delay
import java.io.File
import java.util.UUID

/**
 * Executes an [Automation] step by step. Each step is retried per its
 * [com.legal.automation.model.RetryPolicy]; the first step that exhausts its
 * retries stops the run, captures a screenshot, and fires the failure alert.
 */
class AutomationEngine(
    private val context: Context,
    private val shizuku: ShizukuManager,
    private val alerts: AlertManager,
    private val logs: LogRepository,
    private val settings: SettingsStore,
) {

    private data class StepResult(val success: Boolean, val message: String)

    /**
     * True only when Shizuku should actually be used: the user hasn't switched
     * to non-Shizuku mode AND Shizuku is granted and ready.
     */
    private fun shizukuUsable(): Boolean =
        settings.useShizuku.value && shizuku.status.value == ShizukuManager.Status.READY

    suspend fun run(automation: Automation): RunLog {
        val startedAt = System.currentTimeMillis()
        val stepLogs = mutableListOf<StepLog>()
        var failureScreenshot: String? = null
        var success = true

        AutomationRunState.update {
            it.copy(
                running = true,
                automationName = automation.name,
                stepIndex = 0,
                totalSteps = automation.steps.size,
                lastMessage = "Starting",
            )
        }

        for ((index, step) in automation.steps.withIndex()) {
            AutomationRunState.update {
                it.copy(stepIndex = index, lastMessage = step.describe())
            }

            var attempt = 0
            var result = StepResult(false, "not run")
            while (attempt < step.retry.attempts.coerceAtLeast(1)) {
                attempt++
                result = executeStep(step)
                if (result.success) break
                if (attempt < step.retry.attempts) delay(step.retry.backoffMs)
            }

            stepLogs += StepLog(
                index = index,
                description = step.describe(),
                success = result.success,
                message = result.message,
                attempts = attempt,
            )

            if (!result.success) {
                success = false
                failureScreenshot = captureFailure(automation, index)
                alerts.alertFailure(
                    automationName = automation.name,
                    stepDescription = "Step ${index + 1}: ${step.describe()} — ${result.message}",
                    screenshotPath = failureScreenshot,
                )
                break
            }
        }

        if (success) alerts.alertSuccess(automation.name)

        val log = RunLog(
            id = UUID.randomUUID().toString(),
            automationId = automation.id,
            automationName = automation.name,
            startedAt = startedAt,
            finishedAt = System.currentTimeMillis(),
            success = success,
            steps = stepLogs,
            failureScreenshotPath = failureScreenshot,
        )
        logs.add(log)
        AutomationRunState.reset()
        return log
    }

    private val service: AutomationAccessibilityService?
        get() = AutomationAccessibilityService.instance

    private suspend fun executeStep(step: Step): StepResult = when (step) {
        is Step.LaunchApp -> launchApp(step)
        is Step.TapText -> requireService { svc ->
            if (svc.clickText(step.text, step.exact)) ok()
            else fail("no element with text “${step.text}”")
        }

        is Step.TapId -> requireService { svc ->
            if (svc.clickId(step.viewId)) ok() else fail("no element with id “${step.viewId}”")
        }

        is Step.InputText -> inputText(step)
        is Step.Scroll -> requireService { svc ->
            if (svc.scroll(step.direction)) ok() else fail("scroll gesture rejected")
        }

        is Step.WaitFor -> requireService { svc ->
            if (svc.waitForText(step.text, step.timeoutMs)) ok()
            else fail("“${step.text}” did not appear in ${step.timeoutMs}ms")
        }

        is Step.Verify -> requireService { svc ->
            val present = svc.isTextPresent(step.text)
            if (present == step.expectPresent) ok()
            else fail(
                if (step.expectPresent) "expected “${step.text}” but it was not shown"
                else "“${step.text}” was shown but should not be",
            )
        }

        is Step.Sleep -> {
            delay(step.ms)
            ok()
        }

        is Step.TapXy -> {
            if (shizuku.status.value == ShizukuManager.Status.READY && shizuku.tap(step.x, step.y)) {
                ok()
            } else {
                requireService { svc ->
                    if (svc.tap(step.x, step.y)) ok() else fail("tap gesture rejected")
                }
            }
        }

        is Step.OpenUrl -> openUrl(step)
        is Step.WebSearch -> webSearch(step)

        is Step.ManualStep -> {
            alerts.alertManual(resolve(step.message))
            delay(step.timeoutMs)
            ok()
        }
    }

    private fun openUrl(step: Step.OpenUrl): StepResult {
        val uri = Uri.parse(resolve(step.url))
        val view = Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val intent = when (step.target) {
            UrlTarget.GOOGLE_APP -> Intent(view).setPackage(GOOGLE_APP_PACKAGE)
            UrlTarget.SPECIFIC_APP ->
                Intent(view).setPackage(step.packageName ?: return fail("no target app set"))
            UrlTarget.DEFAULT_BROWSER -> view
            UrlTarget.CHOOSER ->
                Intent.createChooser(view, "Open with").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(intent)
            ok()
        } catch (e: ActivityNotFoundException) {
            // The chosen app can't open it — fall back to the default browser so
            // the run keeps going, and say what happened.
            if (step.target == UrlTarget.GOOGLE_APP || step.target == UrlTarget.SPECIFIC_APP) {
                try {
                    context.startActivity(view)
                    return fail("target app couldn't open the URL; used the default browser instead")
                } catch (_: ActivityNotFoundException) {
                }
            }
            fail("no app can open $uri")
        }
    }

    private fun webSearch(step: Step.WebSearch): StepResult {
        val query = resolve(step.query)
        val search = Intent(Intent.ACTION_WEB_SEARCH)
            .putExtra(SearchManager.QUERY, query)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(Intent(search).setPackage(GOOGLE_APP_PACKAGE))
            ok()
        } catch (e: ActivityNotFoundException) {
            try {
                context.startActivity(search)
                ok()
            } catch (_: ActivityNotFoundException) {
                fail("no app can run a web search")
            }
        }
    }

    /** Substitutes stored tokens like `{username}` into step text. */
    private fun resolve(text: String): String =
        text.replace("{username}", settings.username.value)

    private suspend fun launchApp(step: Step.LaunchApp): StepResult {
        // Prefer Shizuku so the launch works even from the background; in
        // non-Shizuku mode fall straight through to the normal launch intent.
        if (shizukuUsable() && shizuku.launchApp(step.packageName)) {
            return ok()
        }
        val intent = context.packageManager.getLaunchIntentForPackage(step.packageName)
            ?: return fail("“${step.packageName}” is not installed")
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            ok()
        } catch (t: Throwable) {
            fail("could not launch: ${t.message}")
        }
    }

    private suspend fun inputText(step: Step.InputText): StepResult {
        val svc = service ?: return fail("Accessibility service is not enabled")
        val hasTarget = step.intoText != null || step.intoId != null
        val value = resolve(step.text)

        // Focus the target field first, if one was named.
        val targetNode = if (hasTarget) {
            svc.focusField(step.intoText, step.intoId)?.also { delay(300) }
                ?: return fail("target field not found")
        } else {
            null
        }

        // Preferred path: Shizuku low-level typing (unless disabled/unavailable).
        if (shizukuUsable() && shizuku.typeText(value)) {
            return ok()
        }

        // Non-Shizuku fallback: accessibility set-text on the field.
        val applied = when {
            targetNode != null -> svc.setText(targetNode, value)
            else -> svc.setTextOnFocused(value)
        }
        if (applied) return ok()
        return fail(
            if (hasTarget) "could not set text on field"
            else "no focused text field — set intoId/intoText, or enable Shizuku",
        )
    }

    private suspend fun captureFailure(automation: Automation, stepIndex: Int): String? {
        val svc = service ?: return null
        val file = File(
            logs.screenshotDir,
            "${automation.id}_${stepIndex}_${System.currentTimeMillis()}.png",
        )
        val captured = runCatching { svc.screenshotTo(file) }.getOrDefault(false)
        return if (captured) file.absolutePath else null
    }

    private inline fun requireService(block: (AutomationAccessibilityService) -> StepResult): StepResult {
        val svc = service ?: return fail("Accessibility service is not enabled")
        return block(svc)
    }

    private fun ok() = StepResult(true, "ok")
    private fun fail(reason: String) = StepResult(false, reason)

    private companion object {
        const val GOOGLE_APP_PACKAGE = "com.google.android.googlequicksearchbox"
    }
}
