package com.legal.automation.model

import kotlinx.serialization.Serializable
import java.util.UUID

/** A named, ordered list of steps. This is what gets saved as a `.json` file. */
@Serializable
data class Automation(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val description: String = "",
    val steps: List<Step> = emptyList(),
)
