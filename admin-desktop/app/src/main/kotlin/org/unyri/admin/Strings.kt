package org.unyri.admin

import java.io.InputStreamReader
import java.util.Properties

class Strings(val language: String) {
    private val values = Properties().apply {
        val stream = Strings::class.java.getResourceAsStream("/strings_$language.properties") ?: error("Missing locale")
        InputStreamReader(stream, Charsets.UTF_8).use(::load)
    }
    operator fun get(key: String): String = values.getProperty(key) ?: error("Missing resource: $key")
    fun format(key: String, vararg arguments: String): String {
        var index = 0
        return Regex("%@").replace(get(key)) { arguments[index++] }
    }
    fun doctor(row: org.unyri.admin.core.ConnectionDoctorPresentation.Row): String {
        // Replace placeholders in the template only; values can contain literal percent signs.
        return format(row.key, *row.arguments.toTypedArray())
    }
}
