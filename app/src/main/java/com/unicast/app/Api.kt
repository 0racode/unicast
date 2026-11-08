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
    val description: String? = null,
)

@Serializable
data class SpeakRequest(
    val id: String,
    val identity: String,
    val name: String,
    val status: String,
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
    val description: String? = null,
)

@Serializable
data class LiveToken(
    val token: String,
    val url: String,
    val identity: String = "",
)

@Serializable
private data class LiveStatusRow(
    @SerialName("is_live") val isLive: Boolean = false,
)

@Serializable
private data class UsageRow(
    @SerialName("participant_seconds") val participantSeconds: Long = 0,
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

    /** Asks the notify-live server function to send the "Unicast is live" push. Admin only. */
    suspend fun notifyLive(token: String) = withContext(Dispatchers.IO) {
        sendNotification(token, "{}")
    }

    /** Tells listeners' phones that a new episode was uploaded. */
    suspend fun notifyEpisode(token: String, title: String) = withContext(Dispatchers.IO) {
        val payload = buildJsonObject {
            put("type", "episode")
            put("title", title)
        }.toString()
        sendNotification(token, payload)
    }

    private fun sendNotification(token: String, payload: String) {
        val request = Request.Builder()
            .url("${Config.SUPABASE_URL}/functions/v1/notify-live")
            .withKeys(token)
            .post(payload.toRequestBody(jsonType))
            .build()
        run(request)
        Unit
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

    suspend fun getUsageSeconds(token: String, month: String): Long = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("${Config.SUPABASE_URL}/rest/v1/usage_minutes?select=participant_seconds&month=eq.$month")
            .withKeys(token)
            .get()
            .build()
        json.decodeFromString<List<UsageRow>>(run(request)).firstOrNull()?.participantSeconds ?: 0L
    }

    suspend fun addUsage(token: String, month: String, seconds: Int) = withContext(Dispatchers.IO) {
        val payload = buildJsonObject {
            put("p_month", month)
            put("p_seconds", seconds)
        }.toString()
        val request = Request.Builder()
            .url("${Config.SUPABASE_URL}/rest/v1/rpc/add_usage")
            .withKeys(token)
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

    suspend fun updateEpisode(token: String, id: String, title: String, description: String) =
        withContext(Dispatchers.IO) {
            val payload = buildJsonObject {
                put("title", title)
                put("description", description)
            }.toString()
            val request = Request.Builder()
                .url("${Config.SUPABASE_URL}/rest/v1/episodes?id=eq.$id")
                .withKeys(token)
                .header("Prefer", "return=minimal")
                .patch(payload.toRequestBody(jsonType))
                .build()
            run(request)
            Unit
        }

    // ----- speak requests (listeners asking to talk during a live show) -----

    private fun rpc(name: String, payload: String, token: String?): Request =
        Request.Builder()
            .url("${Config.SUPABASE_URL}/rest/v1/rpc/$name")
            .withKeys(token)
            .post(payload.toRequestBody(jsonType))
            .build()

    suspend fun raiseHand(identity: String, name: String): String = withContext(Dispatchers.IO) {
        val payload = buildJsonObject {
            put("p_identity", identity)
            put("p_name", name)
        }.toString()
        json.parseToJsonElement(run(rpc("raise_hand", payload, null), showServerMessage = true))
            .jsonPrimitive.content
    }

    suspend fun mySpeakStatus(id: String): String = withContext(Dispatchers.IO) {
        val payload = buildJsonObject { put("p_id", id) }.toString()
        json.parseToJsonElement(run(rpc("my_speak_status", payload, null))).jsonPrimitive.content
    }

    suspend fun finishSpeaking(id: String) = withContext(Dispatchers.IO) {
        val payload = buildJsonObject { put("p_id", id) }.toString()
        run(rpc("finish_speaking", payload, null))
        Unit
    }

    suspend fun listSpeakRequests(token: String): List<SpeakRequest> = withContext(Dispatchers.IO) {
        json.decodeFromString<List<SpeakRequest>>(run(rpc("list_speak_requests", "{}", token)))
    }

    /** action is one of approve_speaker, end_speaker, decline_speaker. */
    suspend fun speakerAction(token: String, action: String, id: String) =
        withContext(Dispatchers.IO) {
            val payload = buildJsonObject { put("p_id", id) }.toString()
            run(rpc(action, payload, token), showServerMessage = true)
            Unit
        }

    suspend fun clearSpeakRequests(token: String) = withContext(Dispatchers.IO) {
        run(rpc("clear_speak_requests", "{}", token))
        Unit
    }

    suspend fun insertEpisode(
        token: String,
        title: String,
        description: String,
        path: String,
        seconds: Int,
    ) =
        withContext(Dispatchers.IO) {
            val payload = json.encodeToString(
                NewEpisode(title, path, seconds, description.ifBlank { null }),
            )
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
