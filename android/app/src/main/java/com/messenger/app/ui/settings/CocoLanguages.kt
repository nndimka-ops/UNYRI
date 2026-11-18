package com.messenger.app.ui.settings

import java.util.Locale
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.messenger.app.R

/** The supported languages (Этап N.1 §18): 20 languages + System default.
 *  Display names are always in their own language (standard UX). */
data class CocoLanguage(val tag: String?, val displayName: String)

@Composable
internal fun CocoLanguage.localizedName(): String = if (tag == null) stringResource(R.string.language_system_default) else displayName

object CocoLanguages {
    val all: List<CocoLanguage> = listOf(
        CocoLanguage(null, "System default"),
        CocoLanguage("en", "English"),
        CocoLanguage("uk", "Українська"),
        CocoLanguage("ru", "Русский"),
        CocoLanguage("ro", "Română"),
        CocoLanguage("de", "Deutsch"),
        CocoLanguage("pl", "Polski"),
        CocoLanguage("fr", "Français"),
        CocoLanguage("es", "Español"),
        CocoLanguage("it", "Italiano"),
        CocoLanguage("pt", "Português"),
        CocoLanguage("tr", "Türkçe"),
        CocoLanguage("bg", "Български"),
        CocoLanguage("el", "Ελληνικά"),
        CocoLanguage("cs", "Čeština"),
        CocoLanguage("nl", "Nederlands"),
        CocoLanguage("hu", "Magyar"),
        CocoLanguage("ar", "العربية"),
        CocoLanguage("zh-CN", "简体中文"),
        CocoLanguage("ja", "日本語"),
        CocoLanguage("ko", "한국어"),
    )

    fun localeFor(tag: String?): Locale? = tag?.let(Locale::forLanguageTag)
}
