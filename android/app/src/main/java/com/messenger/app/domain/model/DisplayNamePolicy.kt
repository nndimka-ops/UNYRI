package com.messenger.app.domain.model

/** Scalar write policy shared with server/src/display_name.rs.
 * Keep the tables and the order of these passes in sync with the server. */
object DisplayNamePolicy {
    const val maximumScalars = 32
    const val lengthError = "Display name must be 1-32 characters."
    const val reservedError = "This name is reserved for the service. Choose another name."
    const val linkError = "Display name must not contain URLs or domains."
    const val leadingAtError = "Display name must not start with '@'."

    private val brand = listOf("unyri", "юнири", "унири", "юнайри")
    private val staffExact = listOf(
        "support", "techsupport", "customersupport", "helpdesk", "admin", "administrator", "administration",
        "moderator", "official", "staff", "поддержка", "техподдержка", "службаподдержки", "администратор",
        "администрация", "модератор", "официальный", "підтримка", "службапідтримки", "адміністратор",
        "адміністрація",
        "служба безопасности", "безопасность", "security", "system", "система", "системное сообщение",
        "уведомление", "notification", "bank", "банк", "оплата", "payment", "verification", "верификация", "unyri team",
        "служба безпеки", "безпека", "системне повідомлення", "сповіщення", "платіж", "верифікація",
    )

    fun sanitize(raw: String): String {
        val input = Unicode17.nfd(raw).codePoints().toArray()
        val kept = ArrayList<Int>(input.size)
        input.forEachIndexed { index, scalar ->
            val c = if (isWhitespace(scalar)) 0x20 else scalar
            val previous = kept.lastOrNull()
            val next = input.getOrNull(index + 1)
            val keep = when {
                isTag(c) -> previous != null && (previous == 0x1F3F4 || isTag(previous))
                else -> !removed(c)
            }
            if (keep) kept.add(c)
        }
        val marked = ArrayList<Int>(kept.size)
        var run = 0
        for (c in kept) {
            if (isMark(c)) {
                if (marked.lastOrNull() == null || marked.lastOrNull() == 0x20 || run >= 4) continue
                run++
            } else run = 0
            marked.add(c)
        }
        return Unicode17.nfc(collapseSpaces(marked))
    }

    fun scalarCount(value: String): Int = value.codePointCount(0, value.length)

    /** The argument has already been sanitised, as on the server. */
    fun isValid(value: String): Boolean = scalarCount(value) in 1..maximumScalars && value.codePoints().anyMatch {
        it != 0x20 && !isMark(it) && it != 0x200C && it != 0x200D && !isVariationSelector(it) && !isTag(it)
    }

    fun key(value: String): String {
        val folded = Unicode17.nfd(Unicode17.nfkc(value))
        val out = StringBuilder()
        for (c in folded.codePoints().toArray()) {
            if (isMark(c)) continue
            val upper = mapUpper[c]
            if (upper != null) {
                pushSkeleton(out, upper)
                continue
            }
            for (lower in Unicode17.lowercase(c)) {
                val mapped = mapLower[lower] ?: lower
                pushSkeleton(out, if (mapped == 'l'.code) 'i'.code else mapped)
            }
        }
        return out.toString()
    }

    private val brandSkeletons by lazy { brand.map(::key) }
    private val staffSkeletons by lazy { staffExact.map(::key) }

    private val scamWords = listOf(
        "support", "techsupport", "customersupport", "helpdesk", "admin", "administrator", "administration",
        "moderator", "official", "staff", "security", "system", "notification", "bank", "payment", "verification", "service",
        "message", "notifications", "payments", "verifications",
        "поддержка", "техподдержка", "служба", "службы", "поддержки", "администратор", "администрация", "модератор",
        "официальный", "безопасность", "безопасности", "система", "системное", "сообщение", "уведомление", "уведомления",
        "банк", "банка", "банку", "оплата", "оплаты", "платеж", "платежа", "верификация", "верификации",
        "підтримка", "підтримки", "служби", "адміністратор", "адміністрація", "безпека", "безпеки",
        "системне", "повідомлення", "сповіщення", "сповіщень", "платіж", "платежу", "оплати", "верифікація", "верифікації",
    )
    private val scamSkeletons by lazy { scamWords.map(::key) }
    private fun onlyScamWords(value: String): Boolean {
        val counts = IntArray(value.length + 1) { -1 }
        counts[0] = 0
        for (i in value.indices) if (counts[i] >= 0) for (word in scamSkeletons) {
            if (value.startsWith(word, i)) counts[i + word.length] = maxOf(counts[i + word.length], counts[i] + 1)
        }
        return counts.last() >= 2
    }

    fun isReserved(name: String): Boolean {
        val lower = StringBuilder()
        for (c in name.codePoints().toArray()) for (mapped in Unicode17.lowercase(c)) lower.appendCodePoint(mapped)
        return listOf(key(name), key(lower.toString())).any { skeleton ->
            skeleton.isNotEmpty() && (brandSkeletons.any(skeleton::contains) || skeleton in staffSkeletons || onlyScamWords(skeleton))
        }
    }

    fun isReservedUsername(name: String): Boolean {
        if (brandSkeletons.any(key(name)::contains)) return true
        return name.split('_', '.').any { piece ->
            key(piece) in staffSkeletons || key(piece.trimEnd { it in '0'..'9' }) in staffSkeletons
        }
    }

    fun startsWithAt(name: String): Boolean = Unicode17.nfkc(sanitize(name)).startsWith("@")

    private fun linkForm(name: String): String {
        val normalized = StringBuilder()
        for (c in Unicode17.nfd(Unicode17.nfkc(sanitize(name))).codePoints().toArray()) {
            if (!isMark(c) && c != 0x200C && c != 0x200D && !isTag(c)) {
                for (lower in Unicode17.lowercase(c)) normalized.appendCodePoint(lower)
            }
        }
        val words = normalized.toString().replace(" dot ", ".").replace(" точка ", ".").replace(" крапка ", ".")
        val out = ArrayList<Int>()
        var afterDot = false
        fun separator(c: Int) = isWhitespace(c) || c in intArrayOf(91, 93, 40, 41, 123, 125)
        for (c in words.codePoints().toArray()) {
            if (c in intArrayOf(46, 0x2024, 0x3002, 0xFF61, 0xFE52, 0xB7, 0x2027, 0x2219, 0x22C5, 0xA4F8, 0x701, 0x6D4)) {
                while (out.lastOrNull()?.let(::separator) == true) out.removeAt(out.lastIndex)
                out.add(46); afterDot = true
            } else if (!(afterDot && separator(c))) {
                afterDot = false
                out.add(mapLower[c] ?: c)
            }
        }
        return String(out.toIntArray(), 0, out.size)
    }

    private val rootTlds by lazy {
        checkNotNull(DisplayNamePolicy::class.java.getResourceAsStream("/iana-root-tlds.txt"))
            .bufferedReader(Charsets.UTF_8).useLines { lines -> lines.filter { it.isNotEmpty() && !it.startsWith("#") }.map(::linkForm).toSet() }
    }
    private val scheme = Regex("[a-z]{2,10}://")
    private val ipv4Candidate = Regex("[0-9.]+")
    fun containsLink(name: String): Boolean {
        if (hasLink(linkForm(name))) return true
        val source = Unicode17.nfd(Unicode17.nfkc(name)).codePoints().toArray()
        val alternate = source.map { mapUpper[it] ?: it }.toIntArray()
        return hasLink(linkForm(String(alternate, 0, alternate.size)))
    }
    private fun hasLink(value: String): Boolean {
        if ("http" in value || "www." in value || "mailto:" in value || scheme.containsMatchIn(value)) return true
        if (ipv4Candidate.findAll(value).any { match ->
            val parts = match.value.trim('.').split('.')
            parts.size == 4 && parts.all { it.isNotEmpty() && (it.toIntOrNull() ?: -1) in 0..255 }
        }) return true
        val chars = value.codePoints().toArray()
        fun label(c: Int) = Unicode17.isAlphabetic(c) || Unicode17.isNumeric(c) || c == 45
        for (i in chars.indices) {
            if (chars[i] != 46 || i == 0) continue
            var start = i - 1
            while (start >= 0 && label(chars[start])) start--
            if (start == i - 1 || chars[start + 1] == 45 || chars[i - 1] == 45) continue
            var end = i + 1
            while (end < chars.size && label(chars[end])) end++
            val left = String(chars, start + 1, i - start - 1)
            val tld = String(chars, i + 1, end - i - 1)
            val title = left in listOf("dr", "mr", "mrs", "ms", "prof") && (end until chars.size).none { chars[it] in intArrayOf(47, 58, 63) }
            if (!title && tld in rootTlds && (!tld.startsWith("xn--") || tld.toByteArray(Charsets.UTF_8).size <= 63)) return true
        }
        return false
    }

    /** Warm on Dispatchers.Default at startup, before incoming peer decoration. */
    fun preload() { Unicode17.preload(); rootTlds.size; staffSkeletons.size; scamSkeletons.size }

    /** Write checks that apply to all roles. Reserved names remain server-authorized in the editor. */
    fun writeError(raw: String): String? {
        val name = sanitize(raw)
        if (!isValid(name)) return lengthError
        if (startsWithAt(name)) return leadingAtError
        if (containsLink(name)) return linkError
        return null
    }

    /** Used by policy contracts; the editor deliberately leaves role decisions to the server. */
    fun error(raw: String, official: Boolean): String? {
        val name = sanitize(raw)
        writeError(name)?.let { return it }
        if (!official && isReserved(name)) return reservedError
        return null
    }

    fun removesHiddenCharacters(raw: String): Boolean {
        val plain = collapseSpaces(raw.codePoints().toArray().map { if (isWhitespace(it)) 0x20 else it })
        return sanitize(raw) != Unicode17.nfc(plain)
    }

    /** Preserve legacy names byte for byte unless the name field was edited. */
    fun nameToSend(raw: String, initial: String): String = if (raw == initial) initial else sanitize(raw)
    fun editError(raw: String, initial: String): String? {
        return if (raw == initial) null else writeError(raw)
    }
    fun isValidEdit(raw: String, initial: String): Boolean = editError(raw, initial) == null

    /** Render untrusted peer names without invisible controls; raw owner names remain editable. */
    fun display(raw: String, fallback: String): String = DisplayNameSanitizer.display(raw, fallback)

    private fun collapseSpaces(scalars: Iterable<Int>): String {
        val out = StringBuilder()
        for (c in scalars) {
            if (c == 0x20 && (out.isEmpty() || out.last() == ' ')) continue
            out.appendCodePoint(c)
        }
        if (out.isNotEmpty() && out.last() == ' ') out.setLength(out.length - 1)
        return out.toString()
    }

    private fun isWhitespace(c: Int): Boolean = Unicode17.isWhitespace(c)
    private fun isMark(c: Int): Boolean = Unicode17.isMark(c)
    private fun isTag(c: Int): Boolean = c in 0xE0020..0xE007F
    private fun isVariationSelector(c: Int): Boolean = c == 0xFE0E || c == 0xFE0F
    private fun emojiLike(c: Int): Boolean = c == 0x00A9 || c == 0x00AE || c == 0x203C || c == 0x2049 ||
        c == 0x2122 || c == 0x2139 || c in 0x2194..0x21AA || c in 0x231A..0x23FF || c == 0x24C2 ||
        c in 0x25AA..0x25FE || c in 0x2600..0x27BF || c in 0x2934..0x2935 || c in 0x2B05..0x2B55 ||
        c == 0x3030 || c == 0x303D || c == 0x3297 || c == 0x3299 || c in 0x1F000..0x1FAFF || c == 0xFE0F || c == 0x20E3

    private fun joiningScript(c: Int): Boolean = c in 0x0600..0x06FF || c in 0x0700..0x074F ||
        c in 0x0750..0x077F || c in 0x08A0..0x08FF || c in 0x0900..0x0DFF || c in 0xFB50..0xFDFF || c in 0xFE70..0xFEFE

    private fun removed(c: Int): Boolean = c in 0xD800..0xDFFF || c in 0x0000..0x001F || c in 0x007F..0x009F ||
        c == 0x00AD || c == 0x034F || c in 0x0600..0x0605 || c == 0x061C || c == 0x06DD || c == 0x070F || c in 0x0890..0x0891 || c == 0x08E2 ||
        c == 0x115F || c == 0x1160 || c == 0x17B4 || c == 0x17B5 || c in 0x180B..0x180F ||
        c in 0x200B..0x200F || c in 0x202A..0x202E || c in 0x2060..0x206F ||
        c == 0x2800 || c == 0x3164 || c in 0xFE00..0xFE0F || c == 0xFEFF || c == 0xFFA0 || c in 0xFFF0..0xFFFC ||
        c == 0x110BD || c == 0x110CD || c in 0x13430..0x13440 || c in 0x1BCA0..0x1BCA3 || c in 0x1D173..0x1D17A ||
        c in 0xE0000..0xE0FFF ||
        c in 0xE000..0xF8FF || c in 0xF0000..0xFFFFD || c in 0x100000..0x10FFFD || c in 0xFDD0..0xFDEF || (c and 0xFFFE) == 0xFFFE

    private val mapUpper = mapOf('И'.code to 'n'.code, 'Υ'.code to 'y'.code, 'Η'.code to 'h'.code, 'Ν'.code to 'n'.code, 'Μ'.code to 'm'.code)
    private val mapLower = scalarMap(
        // Cyrillic
        "аaвbгrеeиuіiкkмmнhоoпnрpсcтtуyхxцuьbѕsјjԁdԛqԝwүyһhӏi" +
            // Greek
            "αaβbγyεeηnιiκkμuνvοoρpτtυuχxωw" +
            // Armenian
            "սuոnօoհh" +
            // Latin small capitals and look-alike letters
            "ıiȷjɪiʏyɴnʀrᴜuᴀaʙbᴄcᴅdᴇeɢgʜhᴊjᴋkʟlᴍmᴏoᴘpꜱsᴛtᴠvᴡwᴢzłløođdħhŧtſsƒf",
    )
    private val mapSymbol = scalarMap("0o1i3e4a5s7t8b!i|i¡i@a" + "$" + "s")
    private fun scalarMap(pairs: String): Map<Int, Int> = pairs.codePoints().toArray().toList().chunked(2).associate { it[0] to it[1] }
    private fun pushSkeleton(out: StringBuilder, c: Int) {
        val mapped = mapSymbol[c] ?: c
        if (Unicode17.isAlphabetic(mapped)) out.appendCodePoint(mapped)
    }
}
