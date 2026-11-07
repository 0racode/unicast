package com.unicast.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

@Serializable
data class Episode(
    val id: String,
    val title: String,
    @SerialName("file_path") val filePath: String,
    @SerialName("duration_seconds") val durationSeconds: Int? = null,
    @SerialName("created_at") val createdAt: String = "",
    @SerialName("play_count") val playCount: Int = 0,
    @SerialName("download_count") val downloadCount: Int = 0,
)

@Serializable
data class Session(
    @SerialName("access_token") val accessToken: String,
    @SerialName("refresh_token") val refreshToken: String,
    @SerialName("expires_in") val expiresIn: Long = 3600,
)

@Serializable
private data class NewEpisode(
    val title: String,
    @SerialName("file_path") val filePath: String,
    @SerialName("duration_seconds") val durationSeconds: Int,
)

@Serializable
data class LiveToken(
    val token: String,
    val url: String,
)

@Serializable
private data class LiveStatusRow(
    @SerialName("is_live") val isLive: Boolean = false,
)

class ApiException(message: String, val code: Int = 0) : Exception(message)

/** Talks to Supabase over plain HTTPS. All calls run off the main thread. */
object Api {
    private val json = Json { ignoreUnknownKeys = true }
    private val jsonType = "application/json".toMediaType()
    private val audioType = "audio/ogg".toMediaType()

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()

    fun publicUrl(filePath: String): String =
        "${Config.SUPABASE_URL}/storage/v1/object/public/episodes/$filePath"

    private fun Request.Builder.withKeys(token: String?): Request.Builder =
        header("apikey", Config.SUPABASE_ANON_KEY)
            .header("Authorization", "Bearer ${token ?: Config.SUPABASE_ANON_KEY}")

    private fun run(
        request: Request,
        loginCall: Boolean = false,
        showServerMessage: Boolean = false,
    ): String {
        try {
            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw ApiException(
                        friendlyError(response.code, loginCall, showServerMessage, body),
                        response.code,
                    )
                }
                return body
            }
        } catch (e: IOException) {
            throw ApiException("No internet connection. Please try again.")
        }
    }

    /** Pulls the human-readable reason out of a Supabase error response, if there is one. */
    private fun serverMessage(body: String): String? = runCatching {
        val obj = json.parseToJsonElement(body).jsonObject
        listOf("error_description", "msg", "message", "error")
            .firstNotNullOfOrNull { obj[it]?.jsonPrimitive?.contentOrNull }
    }.getOrNull()

    private fun friendlyError(
        code: Int,
        loginCall: Boolean,
        showServerMessage: Boolean,
        body: String,
    ): String {
        val server = serverMessage(body).orEmpty()
        return when {
            showServerMessage && code == 404 -> "Live streaming is not set up yet."
            showServerMessage && server.isNotEmpty() -> server
            loginCall && server.contains("not confirmed", ignoreCase = true) ->
                "This email is not confirmed yet. Confirm the user in Supabase, then try again."
            loginCall && code in 400..403 ->
                if (server.isEmpty() || server.contains("invalid login", ignoreCase = true)) {
                    "Wrong email or password."
                } else {
                    server
                }
            code == 401 || code == 403 -> "You are not allowed to do that. Please sign in again."
            code == 404 -> "Not found."
            code >= 500 -> "The server had a problem. Please try again soon."
            else -> "Something went wrong (error $code)."
        }
    }

    /** Asks the token server for a LiveKit token. The broadcaster token needs the admin's login. */
    suspend fun fetchLiveToken(broadcaster: Boolean, userToken: String?): LiveToken =
        withContext(Dispatchers.IO) {
            val payload = buildJsonObject {
                put("p_role", if (broadcaster) "broadcaster" else "listener")
            }.toString()
            val request = Request.Builder()
                .url("${Config.SUPABASE_URL}/rest/v1/rpc/livekit_token")
                .withKeys(userToken)
                .post(payload.toRequestBody(jsonType))
                .build()
            json.decodeFromString<LiveToken>(run(request, showServerMessage = true))
        }

    /** Adds one to an episode's play or download count. kind is "play" or "download". */
    suspend fun trackEpisode(id: String, kind: String) = withContext(Dispatchers.IO) {
        val payload = buildJsonObject {
            put("p_id", id)
            put("p_kind", kind)
        }.toString()
        val request = Request.Builder()
            .url("${Config.SUPABASE_URL}/rest/v1/rpc/track_episode")
            .withKeys(null)
            .post(payload.toRequestBody(jsonType))
            .build()
        run(request)
        Unit
    }

    suspend fun isLive(): Boolean = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("${Config.SUPABASE_URL}/rest/v1/live_status?select=is_live&id=eq.1")
            .withKeys(null)
            .get()
            .build()
        json.decodeFromString<List<LiveStatusRow>>(run(request)).firstOrNull()?.isLive ?: false
    }

    suspend fun setLive(token: String, live: Boolean) = withContext(Dispatchers.IO) {
        val payload = buildJsonObject { put("is_live", live) }.toString()
        val request = Request.Builder()
            .url("${Config.SUPABASE_URL}/rest/v1/live_status?id=eq.1")
            .withKeys(token)
            .header("Prefer", "return=minimal")
            .patch(payload.toRequestBody(jsonType))
            .build()
        run(request)
        Unit
    }

    suspend fun listEpisodes(): List<Episode> = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("${Config.SUPABASE_URL}/rest/v1/episodes?select=*&order=created_at.desc")
            .withKeys(null)
            .get()
            .build()
        json.decodeFromString<List<Episode>>(run(request))
    }

    suspend fun signIn(email: String, password: String): Session = withContext(Dispatchers.IO) {
        val payload = buildJsonObject {
            put("email", email)
            put("password", password)
        }.toString()
        val request = Request.Builder()
            .url("${Config.SUPABASE_URL}/auth/v1/token?grant_type=password")
            .withKeys(null)
            .post(payload.toRequestBody(jsonType))
            .build()
        json.decodeFromString<Session>(run(request, loginCall = true))
    }

    suspend fun refresh(refreshToken: String): Session = withContext(Dispatchers.IO) {
        val payload = buildJsonObject { put("refresh_token", refreshToken) }.toString()
        val request = Request.Builder()
            .url("${Config.SUPABASE_URL}/auth/v1/token?grant_type=refresh_token")
            .withKeys(null)
            .post(payload.toRequestBody(jsonType))
            .build()
        json.decodeFromString<Session>(run(request))
    }

    suspend fun uploadAudio(token: String, path: String, file: File) = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("${Config.SUPABASE_URL}/storage/v1/object/episodes/$path")
            .withKeys(token)
            .post(file.asRequestBody(audioType))
            .build()
        run(request)
        Unit
    }

    suspend fun insertEpisode(token: String, title: String, path: String, seconds: Int) =
        withContext(Dispatchers.IO) {
            val payload = json.encodeToString(NewEpisode(title, path, seconds))
            val request = Request.Builder()
                .url("${Config.SUPABASE_URL}/rest/v1/episodes")
                .withKeys(token)
                .header("Prefer", "return=minimal")
                .post(payload.toRequestBody(jsonType))
                .build()
            run(request)
            Unit
        }

    suspend fun deleteEpisode(token: String, episode: Episode) = withContext(Dispatchers.IO) {
        val row = Request.Builder()
            .url("${Config.SUPABASE_URL}/rest/v1/episodes?id=eq.${episode.id}")
            .withKeys(token)
            .delete()
            .build()
        run(row)

        // Remove the audio file too. If this fails the episode is already gone from the list.
        try {
            val file = Request.Builder()
                .url("${Config.SUPABASE_URL}/storage/v1/object/episodes/${episode.filePath}")
                .withKeys(token)
                .delete()
                .build()
            run(file)
        } catch (_: ApiException) {
        }
        Unit
    }
}
