package com.sitandtalk.core.network

import kotlinx.serialization.json.Json

/** One JSON configuration for every request and response. Unknown server fields never crash the app. */
val AppJson: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    coerceInputValues = true
    encodeDefaults = true
}
