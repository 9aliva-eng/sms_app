package com.smsapp

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import androidx.core.content.FileProvider
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class UpdateChecker(private val context: Context) {

    private val client = OkHttpClient()

    // Текущая версия — читается автоматически из манифеста
    private val currentVersion: String = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "1.0"
    } catch (e: Exception) {
        "1.0"
    }

    companion object {
        // Ссылка на API GitHub для получения последнего релиза
        private const val GITHUB_API_URL =
            "https://api.github.com/repos/9aliva-eng/sms_app/releases/latest"
    }

    /**
     * Проверяет, есть ли новая версия на GitHub.
     * @return Pair(versionName, apkUrl) или null, если обновлений нет.
     */
    suspend fun checkForUpdate(): Pair<String, String>? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(GITHUB_API_URL)
                .header("Accept", "application/vnd.github.v3+json")
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) return@withContext null

            val json = JSONObject(response.body!!.string())
            val latestVersion = json.getString("tag_name").removePrefix("v")

            // Сравниваем версии (простое строковое сравнение)
            if (latestVersion <= currentVersion) return@withContext null

            // Ищем APK в assets релиза
            val assets = json.getJSONArray("assets")
            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                val name = asset.getString("name")
                if (name.endsWith(".apk")) {
                    return@withContext Pair(latestVersion, asset.getString("browser_download_url"))
                }
            }
            null
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Скачивает APK и возвращает URI через FileProvider для установки.
     */
    suspend fun downloadApk(apkUrl: String): Uri? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url(apkUrl).build()
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) return@withContext null

            val body = response.body ?: return@withContext null
            val inputStream = body.byteStream()

            val downloadsDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            val apkFile = File(downloadsDir, "SMS_Volna_update.apk")

            FileOutputStream(apkFile).use { output ->
                inputStream.copyTo(output)
            }

            // Используем FileProvider для генерации URI
            FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                apkFile
            )
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Запускает установку APK.
     */
    fun installApk(apkUri: Uri) {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(apkUri, "application/vnd.android.package-archive")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
        }
        context.startActivity(intent)
    }
}
