package com.opirl.app.engine.whip

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

internal fun parseWhipUrl(url: String): HttpUrl {
    val trimmed = url.trim()
    val lower = trimmed.lowercase()
    val converted = when {
        lower.startsWith("whips://") -> "https://" + trimmed.substring("whips://".length)
        lower.startsWith("whip://") -> "http://" + trimmed.substring("whip://".length)
        lower.startsWith("https://") || lower.startsWith("http://") -> trimmed
        else -> throw IllegalArgumentException("A WHIP URL starts with whip://, whips://, http:// or https://")
    }
    val parsed = converted.toHttpUrlOrNull() ?: throw IllegalArgumentException("Invalid WHIP URL")
    if (parsed.host.isEmpty()) {
        throw IllegalArgumentException("Invalid WHIP URL")
    }
    return parsed
}
