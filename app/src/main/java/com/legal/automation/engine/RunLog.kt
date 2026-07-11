package com.legal.automation.engine

import kotlinx.serialization.Serializable

@Serializable
data class StepLog(
    val index: Int,
    val description: String,
    val success: Boolean,
    val message: String,
    val attempts: Int,
)

/** The record of one automation run — the "log + screenshot" alerting output. */
@Serializable
data class RunLog(
    val id: String,
    val automationId: String,
    val automationName: String,
    val startedAt: Long,
    val finishedAt: Long,
    val success: Boolean,
    val steps: List<StepLog>,
    val failureScreenshotPath: String? = null,
)
