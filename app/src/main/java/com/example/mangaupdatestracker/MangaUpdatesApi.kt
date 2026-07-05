package com.example.mangaupdatestracker

import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

private const val API_ROOT = "https://api.mangaupdates.com/v1"

class MangaUpdatesApi {
    suspend fun login(username: String, password: String): String {
        val response = request(
            path = "/account/login",
            method = "PUT",
            body = JSONObject()
                .put("username", username)
                .put("password", password)
        )
        return findToken(response.json)
            ?: throw MangaUpdatesException("Login succeeded, but no bearer token was found in the response.")
    }

    suspend fun retrieveLists(token: String): List<UserList> {
        val response = request(path = "/lists", token = token)
        return response.array.mapObjects {
            UserList(
                id = it.optInt("list_id"),
                title = it.optString("title").ifBlank { it.optString("type") },
                type = it.optString("type")
            )
        }.filter { it.id >= 0 }
    }

    suspend fun searchSeries(query: String, token: String?): SeriesResult {
        val response = request(
            path = "/series/search",
            method = "POST",
            token = token,
            body = JSONObject()
                .put("search", query)
                .put("stype", "title")
                .put("page", 1)
                .put("perpage", 5)
        )
        val first = response.json
            .optJSONArray("results")
            ?.optJSONObject(0)
            ?: throw MangaUpdatesException("No MangaUpdates result found for \"$query\".")
        val record = first.optJSONObject("record")
            ?: throw MangaUpdatesException("MangaUpdates returned a malformed search result.")
        val image = record.optJSONObject("image")?.optJSONObject("url")
        val seriesId = record.optString("series_id")
            .ifBlank { record.optString("id") }
        if (seriesId.isBlank()) {
            throw MangaUpdatesException("MangaUpdates returned a result without a valid series id.")
        }
        return SeriesResult(
            id = seriesId,
            title = record.optString("title").ifBlank { query },
            coverUrl = image?.optString("thumb")?.ifBlank { image.optString("original") }
                ?: image?.optString("original")
        )
    }

    suspend fun retrieveListSeries(seriesId: String, token: String): ListEntry? {
        val response = requestOrNull(path = "/lists/series/$seriesId", token = token) ?: return null
        return response.json.toListEntry()
    }

    suspend fun retrieveRating(seriesId: String, token: String): Double? {
        val response = requestOrNull(path = "/series/$seriesId/rating", token = token) ?: return null
        return response.json.optDoubleOrNull("rating")
    }

    suspend fun retrieveMyComment(seriesId: String, token: String): SeriesComment? {
        val response = requestOrNull(path = "/series/$seriesId/comments/my_comment", token = token) ?: return null
        val json = response.json
        val id = json.optInt("id", 0)
        return if (id > 0) {
            SeriesComment(id = id, content = json.optString("content"))
        } else {
            null
        }
    }

    suspend fun saveListEntry(
        series: SeriesResult,
        listId: Int,
        chapter: Int?,
        token: String,
        updateExisting: Boolean
    ) {
        if (!updateExisting) {
            runCatching {
                addSeriesToList(series, listId, chapter, token)
            }.getOrElse {
                updateSeriesListItem(series, listId, chapter, token)
            }
            return
        }

        updateSeriesListItem(series, listId, chapter, token)
    }

    private suspend fun addSeriesToList(series: SeriesResult, listId: Int, chapter: Int?, token: String) {
        request(
            path = "/lists/series",
            method = "POST",
            token = token,
            body = JSONArray().put(listSeriesPayload(series, listId, chapter))
        ).throwIfApiFailure("POST /lists/series")
    }

    private fun listSeriesPayload(series: SeriesResult, listId: Int, chapter: Int?): JSONObject {
        val item = JSONObject()
            .put(
                "series",
                JSONObject()
                    .put("id", series.id.toJsonId())
                    .put("title", series.title)
            )
            .put("list_id", listId)
        if (chapter != null) {
            item.put("status", JSONObject().put("chapter", chapter))
        }
        return item
    }

    private suspend fun updateSeriesListItem(series: SeriesResult, listId: Int, chapter: Int?, token: String) {
        request(
            path = "/lists/series/update",
            method = "POST",
            token = token,
            body = JSONArray().put(listSeriesPayload(series, listId, chapter))
        ).throwIfApiFailure("POST /lists/series/update")
    }

    suspend fun saveRating(seriesId: String, rating: Double?, token: String) {
        if (rating == null) {
            requestOrNull(path = "/series/$seriesId/rating", method = "DELETE", token = token)
            return
        }
        request(
            path = "/series/$seriesId/rating",
            method = "PUT",
            token = token,
            body = JSONObject().put("rating", rating)
        )
    }

    suspend fun saveComment(seriesId: String, title: String, comment: String, existingCommentId: Int?, token: String) {
        val body = JSONObject()
            .put("subject", title.take(80))
            .put("content", comment)
        if (existingCommentId != null) {
            request(
                path = "/series/$seriesId/comments/$existingCommentId",
                method = "PATCH",
                token = token,
                body = body
            )
        } else if (comment.isNotBlank()) {
            request(
                path = "/series/$seriesId/comments",
                method = "POST",
                token = token,
                body = body
            )
        }
    }

    suspend fun loadImage(url: String): ImageBitmap? = withContext(Dispatchers.IO) {
        runCatching {
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = 15_000
            connection.readTimeout = 15_000
            connection.inputStream.use { BitmapFactory.decodeStream(it)?.asImageBitmap() }
        }.getOrNull()
    }

    private suspend fun requestOrNull(
        path: String,
        method: String = "GET",
        token: String? = null,
        body: Any? = null
    ): ApiPayload? = try {
        request(path, method, token, body)
    } catch (error: MangaUpdatesException) {
        if (error.code == 404) null else throw error
    }

    private suspend fun request(
        path: String,
        method: String = "GET",
        token: String? = null,
        body: Any? = null
    ): ApiPayload = withContext(Dispatchers.IO) {
        val connection = (URL("$API_ROOT$path").openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 20_000
            readTimeout = 20_000
            setRequestProperty("Accept", "application/json")
            if (!token.isNullOrBlank()) {
                setRequestProperty("Authorization", "Bearer $token")
            }
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
        }

        if (body != null) {
            connection.outputStream.use { output ->
                output.write(body.toString().toByteArray(Charsets.UTF_8))
            }
        }

        val code = connection.responseCode
        val text = readResponse(connection, code)
        if (code !in 200..299) {
            val reason = apiReason(text).ifBlank { "MangaUpdates returned HTTP $code." }
            throw MangaUpdatesException("$method $path failed: $reason", code)
        }
        ApiPayload(text)
    }

    private fun readResponse(connection: HttpURLConnection, code: Int): String {
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        return stream?.use {
            BufferedReader(InputStreamReader(it)).readText()
        }.orEmpty()
    }

    private fun apiReason(text: String): String {
        if (text.isBlank()) return ""
        val body = runCatching { JSONObject(text) }.getOrNull() ?: return text
        return body.optString("reason")
            .ifBlank { body.optString("message") }
            .ifBlank { body.optString("error") }
            .ifBlank { text }
    }

    private fun findToken(json: JSONObject): String? {
        val directKeys = listOf("session_token", "token", "jwt", "auth_token", "access_token")
        directKeys.firstNotNullOfOrNull { key -> json.optString(key).takeIf { it.isNotBlank() } }?.let { return it }
        val context = json.optJSONObject("context")
        if (context != null) {
            directKeys.firstNotNullOfOrNull { key -> context.optString(key).takeIf { it.isNotBlank() } }?.let { return it }
            val nested = context.optJSONObject("token") ?: context.optJSONObject("session")
            if (nested != null) {
                directKeys.firstNotNullOfOrNull { key -> nested.optString(key).takeIf { it.isNotBlank() } }?.let { return it }
            }
        }
        (json.opt("context") as? String)?.takeIf { it.looksLikeToken() }?.let { return it }
        json.optString("reason").takeIf { it.looksLikeToken() }?.let { return it }
        return null
    }
}

class MangaUpdatesException(message: String, val code: Int? = null) : Exception(message)

data class UserList(
    val id: Int,
    val title: String,
    val type: String
) {
    val displayTitle: String
        get() = DefaultTrackerList.fromApiType(type)?.label ?: title
}

data class SeriesResult(
    val id: String,
    val title: String,
    val coverUrl: String?
)

data class ListEntry(
    val listId: Int,
    val listType: String,
    val chapter: Int?
)

data class SeriesComment(
    val id: Int,
    val content: String
)

enum class DefaultTrackerList(val apiType: String, val label: String) {
    WISHLIST("wish", "Wishlist"),
    READING("read", "Reading list"),
    COMPLETE("complete", "Complete list"),
    UNFINISHED("unfinished", "Unfinished list"),
    ON_HOLD("hold", "On hold list");

    companion object {
        fun fromApiType(type: String): DefaultTrackerList? = entries.firstOrNull { it.apiType == type }
    }
}

private class ApiPayload(private val text: String) {
    val json: JSONObject
        get() = if (text.isBlank()) JSONObject() else JSONObject(text)

    val array: JSONArray
        get() = if (text.isBlank()) JSONArray() else JSONArray(text)

    fun throwIfApiFailure(operation: String) {
        if (text.isBlank()) return
        val body = runCatching { JSONObject(text) }.getOrNull() ?: return
        val status = body.optString("status")
        if (status.isBlank() || status.equals("success", ignoreCase = true) || status.equals("ok", ignoreCase = true)) {
            return
        }
        val reason = body.optString("reason")
            .ifBlank { body.optString("message") }
            .ifBlank { body.optString("error") }
            .ifBlank { "MangaUpdates reported $status." }
        throw MangaUpdatesException("$operation failed: $reason")
    }
}

private fun JSONArray.mapObjects(block: (JSONObject) -> UserList): List<UserList> {
    val items = mutableListOf<UserList>()
    for (index in 0 until length()) {
        optJSONObject(index)?.let { items += block(it) }
    }
    return items
}

private fun JSONObject.toListEntry(): ListEntry {
    val status = optJSONObject("status")
    return ListEntry(
        listId = optInt("list_id"),
        listType = optString("list_type"),
        chapter = status?.optInt("chapter", -1)?.takeIf { it >= 0 }
    )
}

private fun JSONObject.optDoubleOrNull(name: String): Double? =
    if (has(name) && !isNull(name)) optDouble(name) else null

private fun String.looksLikeToken(): Boolean =
    isNotBlank() && length > 20 && !contains(' ')

private fun String.toJsonId(): Any =
    toLongOrNull() ?: this
