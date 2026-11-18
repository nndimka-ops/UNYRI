package com.messenger.app.data.attachment

import java.util.Locale

object FilePresentation {
    fun label(mime: String, filename: String?): String {
        val type = mime.lowercase(Locale.ROOT)
        val ext = filename?.substringAfterLast('.', "")?.lowercase(Locale.ROOT).orEmpty()
        return when {
            type == "application/pdf" || ext == "pdf" -> "PDF"
            type.contains("word") || ext in listOf("doc", "docx", "odt") -> "DOC"
            type.contains("sheet") || type.contains("excel") || ext in listOf("xls", "xlsx", "csv", "ods") -> "XLS"
            type.contains("presentation") || type.contains("powerpoint") || ext in listOf("ppt", "pptx", "odp") -> "PPT"
            type.contains("zip") || type.contains("compressed") || ext in listOf("zip", "rar", "7z", "tar", "gz") -> "ZIP"
            type.startsWith("video/") -> "VIDEO"
            type.startsWith("audio/") -> "AUDIO"
            type.startsWith("image/") -> "IMG"
            type.startsWith("text/") -> "TXT"
            ext.length in 1..6 && ext.all { it in 'a'..'z' || it in '0'..'9' } -> ext.uppercase(Locale.ROOT)
            else -> "FILE"
        }
    }

    fun duration(milliseconds: Long?): String {
        val total = (milliseconds ?: return "").coerceAtLeast(0) / 1000
        return if (total >= 3600) "%d:%02d:%02d".format(Locale.ROOT, total / 3600, total / 60 % 60, total % 60)
        else "%d:%02d".format(Locale.ROOT, total / 60, total % 60)
    }
}
