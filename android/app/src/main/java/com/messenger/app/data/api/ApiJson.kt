package com.messenger.app.data.api

import kotlinx.serialization.json.Json

/** Production REST configuration, also used by native integration fixtures. */
internal val ApiJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}
