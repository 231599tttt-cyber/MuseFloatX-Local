package com.example.musefloatx

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * 首次下载模型：只下一次，之后完全离线。
 *
 * - 下载地址就是 LocalConfig.MODEL_DOWNLOAD_URL（bartowski，带 Qwen_ 前缀的文件名）。
 * - 断点续传：下载写到 .part 文件，已有部分直接 Range 续传，断网/关 App 后再点下载会接着下，不从头来。
 * - WiFi 提醒在 MainActivity 里做：不是 WiFi 时先弹确认框，用户点了“继续”才调 download()。
 * - 完成后校验：大小必须接近 1.40GB；SHA256 也算一遍，不一致就删文件、报错重下，
 *   绝不拿一个损坏的模型去加载（加载 1.4GB 损坏文件只会闪退或出乱码）。
 * - 下载完把 .part 改名成正式文件名，之后 LocalServer.isModelDownloaded() 才会认。
 * - 下载本身需要网络，下载完断网也能生成——断网测试时不要删模型文件。
 */
object ModelDownloader {
    @Volatile var isDownloading: Boolean = false
        private set
    @Volatile var cancelRequested: Boolean = false
        private set

    fun cancel() { cancelRequested = true }

    fun modelFile(context: Context): File = LocalServer.modelFile(context)
    private fun partFile(context: Context): File {
        return File(File(context.filesDir, "models"), LocalConfig.MODEL_FILE_NAME + ".part")
    }

    suspend fun download(context: Context, onProgress: (downloaded: Long, total: Long) -> Unit): Result<String> =
        withContext(Dispatchers.IO) {
            if (isDownloading) return@withContext Result.failure(Exception("正在下载中，请勿重复点"))
            isDownloading = true
            cancelRequested = false
            try {
                val dir = File(context.filesDir, "models")
                dir.mkdirs()
                val part = partFile(context)
                var downloaded = if (part.exists()) part.length() else 0L
                // 如果 part 已经比预期还大，说明是旧的坏文件，删了重下。
                if (downloaded > LocalConfig.MODEL_EXPECTED_BYTES) {
                    part.delete()
                    downloaded = 0L
                }
                val conn = URL(LocalConfig.MODEL_DOWNLOAD_URL).openConnection() as HttpURLConnection
                conn.connectTimeout = 30_000
                conn.readTimeout = 30_000
                if (downloaded > 0) conn.setRequestProperty("Range", "bytes=$downloaded-")
                conn.connect()
                val code = conn.responseCode
                if (code != HttpURLConnection.HTTP_OK && code != HttpURLConnection.HTTP_PARTIAL) {
                    return@withContext Result.failure(Exception("下载失败：服务器返回 HTTP $code"))
                }
                // 服务器不支持续传时从头下。
                if (code == HttpURLConnection.HTTP_OK && downloaded > 0) {
                    part.delete()
                    downloaded = 0L
                }
                val total = LocalConfig.MODEL_EXPECTED_BYTES
                RandomAccessFile(part, "rw").use { raf ->
                    raf.seek(downloaded)
                    val buf = ByteArray(64 * 1024)
                    conn.inputStream.use { input ->
                        while (true) {
                            if (cancelRequested) {
                                return@withContext Result.failure(Exception("下载已取消，已下载的部分会保留，下次点下载可续传"))
                            }
                            val n = input.read(buf)
                            if (n < 0) break
                            raf.write(buf, 0, n)
                            downloaded += n
                            onProgress(downloaded, total)
                        }
                    }
                }
                conn.disconnect()
                if (downloaded < LocalConfig.MODEL_EXPECTED_BYTES * 99 / 100) {
                    return@withContext Result.failure(
                        Exception("下载不完整：已下 ${downloaded / 1024 / 1024}MB，预期约 1400MB，请检查网络后继续下载（可续传）")
                    )
                }
                val sha = sha256(part)
                if (!sha.equals(LocalConfig.MODEL_SHA256, ignoreCase = true)) {
                    part.delete()
                    return@withContext Result.failure(
                        Exception("下载文件 SHA256 不一致（可能下载损坏或源文件已更新），已删除不完整文件，请重新下载；如果反复出现，请检查 LocalConfig 里的 SHA 是否需要更新")
                    )
                }
                val target = modelFile(context)
                if (target.exists()) target.delete()
                if (!part.renameTo(target)) {
                    return@withContext Result.failure(Exception("下载完成但改名失败，请重试"))
                }
                Result.success("模型下载完成：${target.length() / 1024 / 1024}MB，之后断网也能用")
            } catch (e: Exception) {
                Result.failure(Exception("下载失败：${e.message}（已下载的部分会保留，下次可续传）"))
            } finally {
                isDownloading = false
            }
        }

    private fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buf = ByteArray(256 * 1024)
            var n: Int
            while (input.read(buf).also { n = it } >= 0) {
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
