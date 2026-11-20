package com.messenger.app.data.auth

import android.content.Context
import android.os.Build
import android.provider.Settings

/** Read only when registering or drawing the current device; no background observation. */
object SystemDeviceName {
    fun read(context: Context): String = DeviceNamePolicy.choose(
        runCatching { Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME) }.getOrNull(),
        runCatching { Settings.System.getString(context.contentResolver, "device_name") }.getOrNull(),
        Build.MANUFACTURER, Build.MODEL,
    )
}

internal object DeviceNamePolicy {
    fun choose(global: String?, system: String?, manufacturer: String, model: String): String =
        global?.trim()?.takeIf { it.isNotEmpty() }
            ?: system?.trim()?.takeIf { it.isNotEmpty() }
            ?: fallback(manufacturer, model)

    fun fallback(manufacturer: String, model: String): String {
        val maker = manufacturer.trim()
        val name = model.trim()
        return if (name.startsWith(maker, ignoreCase = true)) name else listOf(maker, name).filter { it.isNotEmpty() }.joinToString(" ")
    }
}
