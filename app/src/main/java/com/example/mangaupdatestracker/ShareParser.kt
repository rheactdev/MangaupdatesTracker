package com.example.mangaupdatestracker

import android.content.Intent
import android.net.Uri

fun extractSharedText(intent: Intent?): String? {
    if (intent == null) return null
    val text = when (intent.action) {
        Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
        Intent.ACTION_VIEW -> intent.dataString
        else -> intent.getStringExtra(Intent.EXTRA_TEXT) ?: intent.dataString
    }
    return text?.trim()?.takeIf { it.isNotBlank() }
}

fun titleQueryFromSharedText(sharedText: String): String {
    val url = Regex("""https?://\S+""").find(sharedText)?.value?.trimEnd(')', ']', '.', ',')
        ?: sharedText
    return titleQueryFromUrl(url) ?: sharedText.trim()
}

private fun titleQueryFromUrl(url: String): String? {
    val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return null
    val host = uri.host.orEmpty().removePrefix("www.")
    if (host == "mangaupdates.com") {
        val segments = uri.pathSegments
        val seriesIndex = segments.indexOf("series")
        if (seriesIndex >= 0 && seriesIndex + 2 < segments.size) {
            return segments[seriesIndex + 2].toSearchTitle()
        }
        return null
    }

    if (host != "mangago.me") return null

    val segments = uri.pathSegments
    val mangaIndex = segments.indexOfFirst { it == "read-manga" || it == "recommend-manga" }
    if (mangaIndex < 0 || mangaIndex + 1 >= segments.size) return null

    return segments[mangaIndex + 1].toSearchTitle()
}

private fun String.toSearchTitle(): String? =
    this
        .replace('_', ' ')
        .replace('-', ' ')
        .trim()
        .replace(Regex("""\s+"""), " ")
        .takeIf { it.isNotBlank() }
