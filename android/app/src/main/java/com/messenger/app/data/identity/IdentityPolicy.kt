package com.messenger.app.data.identity

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Locale

/** Pure rules shared by forms, QR ingress and the REST boundary. */
object IdentityPolicy {
    fun username(raw: String): String? = raw.takeIf {
        it.matches(Regex("[A-Za-z][A-Za-z0-9_]{4,31}")) && "__" !in it
    }?.lowercase(Locale.ROOT)
    fun number(raw: String): String? = raw.takeIf { it.matches(Regex("[1-9][0-9]{0,8}")) }
    /** Canonical nine-digit numbers group like iOS; vanity aliases stay unchanged. */
    fun formattedNumber(raw: String): String = if (raw.length == 9 && number(raw) != null)
        raw.chunked(3).joinToString("\u202f") else raw
    data class Target(val parameter: String, val value: String)
    data class Link(val origin: String, val target: Target)
    fun input(raw: String): Target? {
        val text = raw.trim()
        return if (text.startsWith("@")) username(text.drop(1))?.let { Target("username", it) }
        else number(text.filterNot { it == ' ' || it == '\u202f' || it == '\u00a0' })?.let { Target("number", it) }
    }
    fun origin(raw: String): String? = runCatching {
        require(raw.length <= 2048 && raw.none { it.isWhitespace() || it.isISOControl() || it in "\\%@" })
        val uri = URI(if ("://" in raw) raw else "https://$raw")
        require(uri.scheme in listOf("https", "http") && uri.host != null && uri.rawUserInfo == null &&
            uri.rawQuery == null && uri.rawFragment == null && uri.rawPath in listOf("", "/") &&
            (uri.port == -1 || uri.port in 1..65535) && !uri.rawAuthority.endsWith(":"))
        val host = uri.host.lowercase(Locale.ROOT)
        val lastLabel = host.substringAfterLast('.')
        if ((lastLabel.all(Char::isDigit) || lastLabel.startsWith("0x")) && !host.startsWith("[")) require(host.split('.').let { parts ->
            parts.size == 4 && parts.all { it.matches(Regex("0|[1-9][0-9]{0,2}")) && it.toInt() <= 255 }
        })
        val port = uri.port.takeUnless { it == -1 || (uri.scheme == "https" && it == 443) || (uri.scheme == "http" && it == 80) }
        "${uri.scheme}://$host${port?.let { ":$it" }.orEmpty()}"
    }.getOrNull()
    fun parse(raw: String): Link? = runCatching {
        require(raw.length <= 4096 && raw.none { it <= ' ' || it == '\u007f' || it == '\\' })
        val uri = URI(raw)
        require(uri.scheme.equals("unyri", true) && uri.rawAuthority.equals("u", true) && uri.rawFragment == null)
        val path = uri.rawPath
        val target = when {
            path.startsWith("/n/") -> number(path.drop(3))?.let { Target("number", it) }
            path.startsWith("/@") -> username(path.drop(2))?.let { Target("username", it) }
            else -> null
        } ?: error("Invalid target")
        val query = uri.rawQuery ?: error("Missing origin")
        require(query.startsWith("server=") && '&' !in query && '+' !in query)
        Link(origin(URLDecoder.decode(query.drop(7), "UTF-8")) ?: error("Invalid origin"), target)
    }.getOrNull()
    fun link(number: String, server: String): String? {
        val n = number(number) ?: return null
        val origin = origin(server) ?: return null
        return "unyri://u/n/$n?server=" + URLEncoder.encode(origin.removePrefix("https://"), "UTF-8")
    }
    fun lookup(raw: String, server: String): Target? = if (raw.trim().startsWith("unyri:", true))
        parse(raw.trim())?.takeIf { it.origin == origin(server) }?.target else input(raw)
    fun passwordAllowed(password: String) = password.codePointCount(0, password.length) in 8..256
    /**
     * The server's sign-up denylist (server/src/auth/common_passwords.txt, same normalization:
     * trimmed, lower-cased). Checked while the password is typed so the sign-up step says it at
     * once; the server stays the authority (B-048: it refused only at the name step, where the
     * password field is gone). IdentityContractsTest keeps this set equal to the server file.
     */
    val commonPasswords: Set<String> = setOf(
        "12345678", "123456789", "1234567890", "123123123", "11111111", "00000000", "87654321",
        "password", "password1", "password123", "password1234", "password123!", "qwertyui", "qwertyuiop",
        "qwerty123", "qwerty12345", "1q2w3e4r", "1qaz2wsx", "1234qwer", "abcd1234", "abc12345", "iloveyou",
        "sunshine", "princess", "football", "baseball", "superman", "trustno1", "letmein1", "welcome1",
        "welcome123", "admin123", "admin1234", "administrator", "passw0rd", "пароль123",
    )
    fun isCommonPassword(password: String) = password.trim().lowercase() in commonPasswords
    /** Guidance only: long passphrases need no forced punctuation. */
    fun strength(password: String): Int = when {
        !passwordAllowed(password) -> 0
        password.length >= 20 -> 3
        password.length >= 12 && listOf(password.any(Char::isUpperCase), password.any(Char::isLowerCase),
            password.any(Char::isDigit), password.any { !it.isLetterOrDigit() }).count { it } >= 3 -> 3
        password.length >= 12 -> 2
        else -> 1
    }
    fun recoveryParts(key: String): List<String>? = key.takeIf {
        it.matches(Regex("R1(?:-[0-9A-F]{5}){8}"))
    }?.split('-')?.drop(1)
    val confirmationIndices = listOf(1, 3, 6)
    fun confirms(key: String, answers: List<String>): Boolean = recoveryParts(key)?.let { parts ->
        answers.size == 3 && confirmationIndices.map { parts[it] } == answers.map { it.trim().uppercase(Locale.ROOT) }
    } == true
    fun unavailable(status: Int?, code: String) = status == 409 && code == "unavailable"
    fun legacy(status: Int?) = status == 404 || status == 405
    // A person-not-found 404 must never enable directory discovery on an identity server.
    fun legacyLookup(status: Int?, code: String) = status == 405 ||
        status == 404 && code in setOf("route_not_found", "http_error")
}

enum class AuthStep { SignIn, SignUp, DisplayName }
data class AuthForm(val step: AuthStep = AuthStep.SignIn) {
    fun toggle() = AuthForm(if (step == AuthStep.SignIn) AuthStep.SignUp else AuthStep.SignIn)
    fun next(login: String, password: String, confirmation: String, accepted: Boolean): AuthForm =
        if (step == AuthStep.SignUp && login.matches(Regex("[A-Za-z0-9_.]{3,32}")) &&
            IdentityPolicy.passwordAllowed(password) && !IdentityPolicy.isCommonPassword(password) &&
            password == confirmation && accepted) copy(step = AuthStep.DisplayName) else this
    /** Back from the name step returns to the password step (B-048), from sign-up to sign-in. */
    fun back(): AuthForm = when (step) {
        AuthStep.DisplayName -> AuthForm(AuthStep.SignUp)
        AuthStep.SignUp -> AuthForm(AuthStep.SignIn)
        AuthStep.SignIn -> this
    }
    /**
     * A sign-up refusal about the login or the password is shown on the step that has those
     * fields (B-048: «too common» and «login taken» appeared on the name step with no way back).
     * Display-name refusals stay on the name step.
     */
    fun afterRefusal(code: String?): AuthForm =
        if (step == AuthStep.DisplayName && code in signUpFieldRefusals) AuthForm(AuthStep.SignUp) else this

    private companion object {
        val signUpFieldRefusals = setOf("common_password", "username_taken", "username_reserved", "username_link")
    }
}
