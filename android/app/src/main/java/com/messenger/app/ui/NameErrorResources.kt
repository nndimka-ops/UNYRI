package com.messenger.app.ui

import com.messenger.app.R

/** Stable API codes, independent of server wording or language. */
fun nameErrorResource(code: String?): Int? = when (code) {
    "display_name_reserved" -> R.string.profile_name_reserved
    "display_name_link" -> R.string.profile_name_link
    "display_name_leading_at" -> R.string.profile_name_leading_at
    "username_reserved" -> R.string.profile_username_reserved
    "username_link" -> R.string.profile_username_link
    "validation_error" -> R.string.profile_validation_error
    else -> null
}
