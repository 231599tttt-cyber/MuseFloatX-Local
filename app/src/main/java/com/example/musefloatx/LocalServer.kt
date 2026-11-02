package com.example.musefloatx

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * 本地 llama-server 进程管理。
 *
 * 加载策略（用户明确要求，不要常驻占内存）：
 * - 悬浮球启动时加载：FloatingService.onCreate 里调 ensureStarted()。
 * - 悬浮球关闭时卸载：FloatingService.onDestroy / STOP 里调 stop()。
 * - 10 分钟无生成自动卸载：每次生成结束调 markUsed()，之后 10 分钟
 *   没有新生成就自动 stop()，释放内存；下次点球再重新加载。
 * - mmap 加载：llama-server 默认就是 mmap，启动参数里显式传 --mmap，
 *   让系统可以在内存紧张时回收模型页，而不是一直钉死 1.6GB。
 *
 * 聊天模板（用户纠正的第 2 点）：
 * - 不传 --chat-template、不用自定义模板，让 llama-server 直接用模型
 *   自带的聊天模板。Qwen3.5-2B 官方默认就是非思考模式，模板本身
 *   会处理好（模板里 assistant 开头是空的思考段，模型接着直接输出正文），
 *   不要自己写一个会触发思考的模板。事后过滤 <think> 只在 ApiClient 里作兜底。
 *
 * 打包方式：
 * - GitHub Actions 里用 CMake + Android NDK 编 llama-server（arm64-v8a），
 *   产物放在 app/src/main/assets/llama-server，APK 里带上。
 * - 首次使用时把 assets 里的 llama-server 复制到 filesDir/bin/llama-server
 *   并 chmod +x，然后 ProcessBuilder 启动它。assets 里的文件不能直接执行，
 *   必须先复制出来，这一步是必须的，不是可选项。
 * - 如果 assets 里没有 llama-server（例如本地只编了 APK、没编 native），
 *   ensureStarted() 会直接报错，绝不静默回退到任何远程服务——
 *   本地版没有远程服务可回退。
 */
object LocalServer {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private var process: Process? = null
    private var idleJob: Job? = null
    @Volatile var isRunning: Boolean = false
        private set
    @Volatile var lastError: String? = null
        private set

    fun modelFile(context: Context): File {
        return File(File(context.filesDir, "models"), LocalConfig.MODEL_FILE_NAME)
    }

    fun isModelDownloaded(context: Context): Boolean {
        val f = modelFile(context)
        // 大小校验：必须接近 1.40GB，差 1% 以上算没下完或损坏。
        return f.exists() && f.length() > LocalConfig.MODEL_EXPECTED_BYTES * 99 / 100 &&
            f.length() < LocalConfig.MODEL_EXPECTED_BYTES * 101 / 100
    }

    private fun serverBinary(context: Context): File {
        return File(File(context.filesDir, "bin"), "llama-server")
    }

    /** 把 assets 里的 llama-server 复制到可执行位置，返回 true 表示可用。 */
    private fun installBinary(context: Context): Boolean {
        return try {
            val out = serverBinary(context)
            if (out.exists() && out.canExecute() && out.length() > 0) return true
            out.parentFile?.mkdirs()
            context.assets.open("llama-server").use { input ->
                FileOutputStream(out).use { output -> input.copyTo(output) }
            }
            out.setExecutable(true)
            out.exists() && out.canExecute()
        } catch (e: Exception) {
            lastError = "安装本地推理程序失败：${e.message}"
            false
        }
    }

    /** 悬浮球启动时调：模型没下好直接报错，不启动空服务。 */
    suspend fun ensureStarted(context: Context, contextTokens: Int): Boolean = mutex.withLock {
        if (isRunning && process?.isAlive == true) {
            cancelIdleUnloadLocked()
            return true
        }
        if (!isModelDownloaded(context)) {
            lastError = "模型还没下载好，请先回主界面下载模型（约 1.40GB）"
            return false
        }
        if (!installBinary(context)) return false
        return try {
            val ctx = contextTokens.coerceIn(512, 4096)
            val args = listOf(
                serverBinary(context).absolutePath,
                "-m", modelFile(context).absolutePath,
                "--alias", LocalConfig.LOCAL_MODEL_NAME,
                "--host", "127.0.0.1",
                "--port", LocalConfig.LOCAL_PORT.toString(),
                "-c", ctx.toString(),
                "-t", LocalConfig.THREADS.toString(),
                "--mmap",
                "-b", "512",
                "--log-disable"
                // 注意：故意不传 --chat-template，让模型自带模板决定非思考模式。
                // 也故意不传 --api-key：只绑 127.0.0.1 回环，外面连不进来。
            )
            process = ProcessBuilder(args)
                .redirectErrorStream(true)
                .start()
            // 等 /health 变 200，最长 180 秒；进程提前死了就直接报。
            val deadline = System.currentTimeMillis() + LocalConfig.SERVER_START_TIMEOUT_MS
            var ok = false
            while (System.currentTimeMillis() < deadline) {
                if (process?.isAlive != true) break
                if (isHealthy()) { ok = true; break }
                delay(500)
            }
            isRunning = ok
            if (!ok) {
                lastError = "本地模型加载超时或启动失败：请确认手机剩余内存足够（6GB 机器请先关掉大游戏），再点一次悬浮球"
                try { process?.destroyForcibly() } catch (_: Exception) {}
                process = null
            } else {
                lastError = null
            }
            ok
        } catch (e: Exception) {
            lastError = "本地模型启动失败：${e.message}"
            false
        }
    }

    private fun isHealthy(): Boolean {
        return try {
            val conn = URL("http://127.0.0.1:${LocalConfig.LOCAL_PORT}/health").openConnection() as HttpURLConnection
            conn.connectTimeout = 1000
            conn.readTimeout = 1000
            conn.requestMethod = "GET"
            val code = conn.responseCode
            conn.disconnect()
            code == 200
        } catch (_: Exception) {
            false
        }
    }

    /** 每次生成结束调一次，重置 10 分钟闲置计时。 */
    fun markUsed() {
        idleJob?.cancel()
        idleJob = scope.launch {
            delay(LocalConfig.IDLE_UNLOAD_MS)
            // 10 分钟没人再调 markUsed，说明没生成了，卸载释放内存。
            stop()
        }
    }

    private fun cancelIdleUnloadLocked() {
        idleJob?.cancel()
        idleJob = null
    }

    /** 悬浮球关闭 / 10 分钟闲置时调：杀掉 llama-server，释放模型内存。 */
    fun stop() {
        idleJob?.cancel()
        idleJob = null
        try { process?.destroy() } catch (_: Exception) {}
        try {
            // 给 2 秒优雅退出，退不掉再强制杀。
            Thread.sleep(2000)
        } catch (_: Exception) {}
        try {
            if (process?.isAlive == true) process?.destroyForcibly()
        } catch (_: Exception) {}
        process = null
        isRunning = false
    }

    /** 供 MainActivity 测试用：不经过 FloatingService 也能起服务，测完同样会闲置卸载。 */
    suspend fun ensureStartedForTest(context: Context, contextTokens: Int): Boolean {
        return ensureStarted(context, contextTokens)
    }

    /** 给 ApiClient 用：生成前确保服务在跑，否则返回失败原因。 */
    suspend fun ensureStartedForGenerate(context: Context, contextTokens: Int): String? {
        val ok = ensureStarted(context, contextTokens)
        return if (ok) null else (lastError ?: "本地模型未启动")
    }
}
