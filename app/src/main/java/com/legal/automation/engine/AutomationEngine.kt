package com.legal.automation.engine

import android.content.Context
import android.content.Intent
import com.legal.automation.model.Automation
import com.legal.automation.model.Step
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
) {

    private data class StepResult(val success: Boolean, val message: String)

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
    }

    private suspend fun launchApp(step: Step.LaunchApp): StepResult {
        // Prefer Shizuku so the launch works even from the background.
        if (shizuku.status.value == ShizukuManager.Status.READY && shizuku.launchApp(step.packageName)) {
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
        val svc = service
        val hasTarget = step.intoText != null || step.intoId != null
        var focused = false
        if (hasTarget) {
            val node = svc?.focusField(step.intoText, step.intoId)
                ?: return fail("target field not found")
            focused = true
            delay(300)
            // Try Shizuku first (honours the "type via low-level input" preference).
            if (shizuku.status.value == ShizukuManager.Status.READY && shizuku.typeText(step.text)) {
                return ok()
            }
            return if (svc.setText(node, step.text)) ok() else fail("could not set text on field")
        }
        // No explicit target: type into whatever currently has focus.
        if (shizuku.status.value == ShizukuManager.Status.READY && shizuku.typeText(step.text)) {
            return ok()
        }
        return fail(
            "typing needs Shizuku (or a target field). " +
                (if (!focused) "Set intoId/intoText, or grant Shizuku." else ""),
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
}
