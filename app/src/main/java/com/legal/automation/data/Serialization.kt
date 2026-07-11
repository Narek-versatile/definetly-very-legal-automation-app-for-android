package com.legal.automation.data

import kotlinx.serialization.json.Json

/**
 * Shared JSON config. `type` is the discriminator key that tells the parser
 * which [com.legal.automation.model.Step] subtype a JSON object is.
 */
val AppJson: Json = Json {
    prettyPrint = true
    ignoreUnknownKeys = true
    classDiscriminator = "type"
    encodeDefaults = true
}
