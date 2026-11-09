package com.unicast.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException

@Serializable
private data class GhAsset(
    val name: String,
    @SerialName("browser_download_url") val url: String,
    val size: Long = 0,
)

@Serializable
private data class GhRelease(
    @SerialName("tag_name") val tag: String,
    val body: String? = null,
    val assets: List<GhAsset> = emptyList(),
)

data class UpdateInfo(
    val versionCode: Int,
    val notes: String,
    val url: String,
    val sizeBytes: Long,
)

/**
 * In-app updates. Each GitHub release is tagged build-N, and N is also the app's version code,
 * so "is there something newer?" is just a number comparison.
 */
object Updater {
    private const val LATEST_RELEASE = "https://api.github.com/repos/0racode/unicast/releases/latest"

    private val json = Json { ignoreUnknownKeys = true }
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    @Suppress("DEPRECATION")
    fun installedVersionCode(context: Context): Int {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        return if (Build.VERSION.SDK_INT >= 28) info.longVersionCode.toInt() else info.versionCode
    }

    fun installedVersionName(context: Context): String =
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""

    fun apkFile(context: Context): File = File(File(context.cacheDir, "updates"), "Unicast.apk")

    fun clearDownloads(context: Context) {
        runCatching { File(context.cacheDir, "updates").deleteRecursively() }
    }

    /** Chooses the smaller download made for this phone's processor, else any APK. */
    private fun pickApk(assets: List<GhAsset>): GhAsset? {
        val apks = assets.filter { it.name.endsWith(".apk") }
        for (abi in Build.SUPPORTED_ABIS) {
            val tag = when (abi) {
                "arm64-v8a" -> "arm64"
                "armeabi-v7a" -> "arm32"
                else -> continue
            }
            apks.firstOrNull { it.name.contains(tag) }?.let { return it }
        }
        return apks.firstOrNull()
    }

    /** Returns the newer version if there is one, otherwise null. Never throws. */
    suspend fun check(context: Context): UpdateInfo? = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(LATEST_RELEASE)
            .header("Accept", "application/vnd.github+json")
            .build()
        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val release = json.decodeFromString<GhRelease>(response.body?.string().orEmpty())
                val code = release.tag.removePrefix("build-").toIntOrNull() ?: return@withContext null
                val apk = pickApk(release.assets) ?: return@withContext null
                if (code <= installedVersionCode(context)) {
                    null
                } else {
                    UpdateInfo(code, release.body.orEmpty().trim(), apk.url, apk.size)
                }
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            null
        }
    }

    /** Downloads the APK, reporting progress as 0 to 100. */
    suspend fun download(context: Context, info: UpdateInfo, onProgress: (Int) -> Unit): File =
        withContext(Dispatchers.IO) {
            val target = apkFile(context)
            target.parentFile?.mkdirs()
            val partial = File(target.parentFile, "Unicast.part")
            val request = Request.Builder().url(info.url).build()
            try {
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        throw ApiException("Could not download the update (error ${response.code}).")
                    }
                    val body = response.body ?: throw ApiException("The download was empty.")
                    val total = if (body.contentLength() > 0) body.contentLength() else info.sizeBytes
                    body.byteStream().use { input ->
                        partial.outputStream().use { output ->
                            val buffer = ByteArray(64 * 1024)
                            var done = 0L
                            var lastPercent = -1
                            while (true) {
                                val n = input.read(buffer)
                                if (n < 0) break
                                output.write(buffer, 0, n)
                                done += n
                                if (total > 0) {
                                    val percent = (done * 100 / total).toInt().coerceIn(0, 100)
                                    if (percent != lastPercent) {
                                        lastPercent = percent
                                        onProgress(percent)
                                    }
                                }
                            }
                        }
                    }
                    if (total > 0 && partial.length() != total) {
                        partial.delete()
                        throw ApiException("The download was incomplete. Please try again.")
                    }
                }
            } catch (e: IOException) {
                partial.delete()
                throw ApiException("The download was interrupted. Check your internet and try again.")
            }
            target.delete()
            if (!partial.renameTo(target)) {
                throw ApiException("Could not save the update.")
            }
            target
        }

    fun canInstall(context: Context): Boolean =
        Build.VERSION.SDK_INT < 26 || context.packageManager.canRequestPackageInstalls()

    /** Opens Android's "allow from this source" screen for Unicast. */
    fun openInstallPermissionSettings(context: Context) {
        val intent = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    /** Hands the downloaded APK to Android's installer. The person taps Install. */
    fun install(context: Context, file: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}
