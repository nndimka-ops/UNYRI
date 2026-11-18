package com.messenger.app.ui.admin

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.messenger.app.R
import kotlinx.serialization.json.*

internal data class SelfCheckField(val group: String, val field: String, val label: Int, val fix: Int)
internal val selfCheckFields = listOf(
    SelfCheckField("setup", "available", R.string.sh_check_setup, R.string.sh_fix_setup),
    SelfCheckField("public_url", "url", R.string.sh_check_url, R.string.sh_fix_url),
    SelfCheckField("public_url", "configured", R.string.sh_check_url_config, R.string.sh_fix_url),
    SelfCheckField("public_url", "tls", R.string.sh_check_tls, R.string.sh_probe),
    SelfCheckField("turn", "external_ip_set", R.string.sh_check_ip, R.string.sh_fix_turn),
    SelfCheckField("turn", "secret_set", R.string.sh_check_secret, R.string.sh_fix_turn),
    SelfCheckField("turn", "urls_sane", R.string.sh_check_turn_urls, R.string.sh_fix_turn),
    SelfCheckField("turn", "relay_ports_sane", R.string.sh_check_ports, R.string.sh_fix_turn),
    SelfCheckField("turn", "sane", R.string.sh_check_turn, R.string.sh_fix_turn),
    SelfCheckField("turn", "relay_min_port", R.string.sh_check_min, R.string.sh_fix_turn),
    SelfCheckField("turn", "relay_max_port", R.string.sh_check_max, R.string.sh_fix_turn),
    SelfCheckField("turn", "reachability", R.string.sh_check_turn_reach, R.string.sh_probe),
    SelfCheckField("rooms", "configured", R.string.sh_check_rooms, R.string.sh_fix_rooms),
    SelfCheckField("rooms", "status", R.string.sh_check_rooms_status, R.string.sh_fix_rooms),
    SelfCheckField("rooms", "reachability", R.string.sh_check_rooms_reach, R.string.sh_probe),
    SelfCheckField("push", "offline", R.string.sh_check_offline, R.string.sh_fix_push),
    SelfCheckField("push", "fcm_configured", R.string.sh_check_fcm, R.string.sh_fix_push),
    SelfCheckField("push", "active_providers", R.string.sh_check_providers, R.string.sh_fix_push),
    SelfCheckField("push", "status", R.string.sh_check_push, R.string.sh_fix_push),
    SelfCheckField("push", "delivery", R.string.sh_check_delivery, R.string.sh_probe),
    SelfCheckField("storage", "reachable", R.string.sh_check_storage, R.string.sh_fix_storage),
    SelfCheckField("storage", "client_uploads_ready", R.string.sh_check_uploads, R.string.sh_fix_storage),
    SelfCheckField("disk", "available_bytes", R.string.sh_check_bytes, R.string.sh_fix_disk),
    SelfCheckField("disk", "status", R.string.sh_check_disk, R.string.sh_fix_disk),
    SelfCheckField("disk", "low_space", R.string.sh_check_low, R.string.sh_fix_disk),
    SelfCheckField("server", "version", R.string.sh_check_version, R.string.sh_fix_version),
    SelfCheckField("server", "migration_level", R.string.sh_check_migration, R.string.sh_fix_version),
)

@Composable
internal fun SelfCheckSentences(json: JsonObject) {
    for (item in selfCheckFields) {
        val value = (json[item.group] as? JsonObject)?.get(item.field)
        val primitive = value as? JsonPrimitive
        val raw = primitive?.contentOrNull
        val text = when {
            primitive?.booleanOrNull != null -> stringResource(if (primitive.boolean) R.string.sh_yes else R.string.sh_no)
            raw == "not_probed" -> stringResource(R.string.sh_not_probed)
            raw in listOf("configured", "ok") -> stringResource(R.string.sh_ready)
            raw in listOf("not_configured", "incomplete", "offline") -> stringResource(R.string.sh_not_ready)
            item.field == "active_providers" && value is JsonArray -> value.take(8).mapNotNull {
                (it as? JsonPrimitive)?.content?.takeIf { it in setOf("fcm", "apns", "apns_voip") }
            }.joinToString(", ").ifBlank { stringResource(R.string.sh_unknown) }
            item.field == "url" -> runCatching { require(raw != null && raw.length <= 2048); com.messenger.app.data.api.ServerEndpoint.normalize(raw, false) }.getOrNull() ?: stringResource(R.string.sh_unknown)
            item.field == "version" && raw?.matches(Regex("[0-9A-Za-z.+-]{1,48}")) == true -> raw
            primitive?.longOrNull?.let { it >= 0 } == true -> raw.orEmpty()
            else -> stringResource(R.string.sh_unknown)
        }
        Text(stringResource(R.string.sh_check_sentence, stringResource(item.label), text, stringResource(item.fix)))
    }
}
