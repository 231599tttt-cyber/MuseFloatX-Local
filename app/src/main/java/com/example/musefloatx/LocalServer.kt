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
 * - mmap 加载：新版 llama-server 里 mmap 是默认开启的，
 *   只有 --no-mmap 这个反向开关，没有 --mmap 这个参数；
 *   2026-11-02 真机实测：传 --mmap 会直接报
 *   error: invalid argument: --mmap、进程 exitCode=1 提前退出，
 *   所以这里故意不传任何 mmap 参数，就是默认 mmap，
 *   系统可以在内存紧张时回收模型页，而不是一直钉死 1.6GB。
 *
 * 聊天模板（用户纠正的第 2 点）：
 * - 不传 --chat-template、不用自定义模板，让 llama-server 直接用模型
 *   自带的聊天模板。Qwen3.5-2B 官方默认就是非思考模式，模板本身
 *   会处理好，不要自己写一个会触发思考的模板。事后过滤 <think>
 *   只在 ApiClient 里作兜底。
 *
 * 打包与启动方式（2026-11-02 修复，针对 targetSdk 34 / Android 10+ W^X 限制）：
 * - 旧方式（已废弃，不要再用）：把程序复制到应用私有目录再执行。
 *   Android 10 及以上对应用私有目录有 W^X 限制，那个目录里的文件
 *   即使 chmod +x 也很可能报 Permission denied，GitHub 编译成功
 *   不等于真机能跑。所以旧方式已彻底删掉，本文件里不再出现它。
 * - 新方式（过渡方案，见 README 的 JNI 评估）：llama-server 以原生库
 *   形式打包，文件在 APK 的 lib/arm64-v8a/libllama_server.so，
 *   安装时系统把它解压到应用的 nativeLibraryDir，那个目录是可执行的。
 *   启动时直接执行 nativeLibraryDir 里的这个文件，并把
 *   LD_LIBRARY_PATH 设成 nativeLibraryDir，这样它要找的动态库
 *   （如果有的话）也能在同一个目录里找到。
 * - 打包要求（工作流里强制检查，任一项不过就构建失败）：
 *   1) APK 里必须有 lib/arm64-v8a/libllama_server.so；
 *   2) 它不能是约 7KB 的动态 stub，必须是大于 1MB 的完整程序
 *      （静态编译，或动态编译但所有依赖 .so 都一起打进了 jniLibs）；
 *   3) 用 readelf -d 查它的 DT_NEEDED，凡是不是系统库
 *      （libc / libm / libdl / liblog / libandroid）的依赖，
 *      都必须在同一个 jniLibs 目录里有对应的 .so，否则构建失败；
 *   4) 本文件里不再出现旧的私有目录执行路径。
 * - 最终合规方案是 JNI 直接加载（见 README），不是一直用进程执行
 *   .so。本文件只是过渡方案，跑通真机测试后再做 JNI。
 *
 * 启动失败诊断：
 * - 绝不静默失败：进程提前退出、超时、找不到文件、Permission denied
 *   都会把真实原因写进 lastError，并把进程日志尾部一起带上。
 * - 主界面有“检查本地服务启动”按钮，直接显示 diagnosticInfo()，
 *   用户把那段原文发回来就能定位，不要只报“失败”两个字。
 * - 本地版没有远程服务可回退，启动失败就是启动失败，不会偷偷
 *   连到别的服务器。
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

    /** 当前选用的模型规格（SettingsStore.selectedModel，默认 2B）。 */
    fun selectedModel(context: Context): LocalModel {
        return try {
            SettingsStore(context).selectedModel
        } catch (_: Exception) {
            LocalConfig.MODEL_2B
        }
    }

    /**
     * 当前选用的思考模式（SettingsStore.reasoningMode，默认 "off"）。
     * 绝不能在这里写死 "off"：思考模式是用户可选的，见 MainActivity
     * 的“思考模式”选择器；改动后要先 stop() 再启动才会生效。
     * runningReasoningMode 记录的是当前正在跑的服务用的值，
     * 如果和用户新选的值不一致，ensureStarted() 会先杀掉旧服务再重起。
     */
    fun reasoningMode(context: Context): String {
        return try {
            SettingsStore(context).reasoningMode
        } catch (_: Exception) {
            "off"
        }
    }

    @Volatile private var runningReasoningMode: String? = null
        private set

    /** 指定模型的落盘文件；不传模型时用当前选用模型。 */
    fun modelFile(context: Context, model: LocalModel = selectedModel(context)): File {
        return File(File(context.filesDir, "models"), model.fileName)
    }

    /** 指定模型是否已下载：文件存在且大小在官方大小 ±1% 内才算。 */
    fun isModelDownloaded(context: Context, model: LocalModel = selectedModel(context)): Boolean {
        val f = modelFile(context, model)
        // 大小校验：必须接近官方大小，差 1% 以上算没下完或损坏。
        return f.exists() && f.length() > model.expectedBytes * 99 / 100 &&
            f.length() < model.expectedBytes * 101 / 100
    }

    /** 原生库目录里的推理程序：APK 安装时从 lib/arm64-v8a/ 解压到这里。 */
    fun serverBinary(context: Context): File {
        return File(context.applicationInfo.nativeLibraryDir, "libllama_server.so")
    }

    private fun serverLogFile(context: Context): File {
        return File(context.cacheDir, "llama-server.log")
    }

    /** 进程日志尾部（最多约 1500 字），启动失败时用来看真实原因。 */
    fun serverLogTail(context: Context): String {
        return try {
            val f = serverLogFile(context)
            if (!f.exists()) return "（暂无进程日志文件）"
            val text = f.readText()
            if (text.isBlank()) "（进程日志为空）" else text.takeLast(1500)
        } catch (e: Exception) {
            "（读进程日志失败：${e.message}）"
        }
    }

    /** 主界面诊断用：原生库路径、文件大小、进程状态、最后一次真实报错、日志尾部。 */
    fun diagnosticInfo(context: Context): String {
        val bin = serverBinary(context)
        val sb = StringBuilder()
        sb.append("原生库目录：").append(context.applicationInfo.nativeLibraryDir).append("\n")
        sb.append("推理程序：").append(bin.absolutePath).append("\n")
        sb.append("文件存在：").append(if (bin.exists()) "是" else "否").append("\n")
        if (bin.exists()) {
            sb.append("文件大小：").append(bin.length() / 1024 / 1024).append("MB")
            if (bin.length() < 1024 * 1024) sb.append("（小于 1MB，很可能是没带依赖的 stub，请重装修复后的 APK）")
            sb.append("\n")
            sb.append("可执行标记：").append(if (bin.canExecute()) "是" else "否（系统解压时一般自带可执行权限，否也不一定就是失败原因，看下面报错原文）").append("\n")
        }
        val selected = selectedModel(context)
        sb.append("当前选用模型：").append(selected.displayName).append("\n")
        sb.append("思考模式（用户选择）：").append(reasoningMode(context))
            .append("（off=关闭快，auto=自动，on=开启慢但可能更准）\n")
        sb.append("正在跑的服务用的思考模式：").append(runningReasoningMode ?: "（服务没在跑）").append("\n")
        sb.append("当前模型文件：").append(modelFile(context, selected).absolutePath).append("\n")
        sb.append("当前模型已下载：").append(if (isModelDownloaded(context, selected)) "是" else "否").append("\n")
        for (m in LocalConfig.MODELS) {
            sb.append("模型 ").append(m.displayName).append(" 已下载：")
                .append(if (isModelDownloaded(context, m)) "是" else "否").append("\n")
        }
        sb.append("服务在跑：").append(if (isRunning && process?.isAlive == true) "是" else "否").append("\n")
        sb.append("最后一次报错：").append(lastError ?: "（无）").append("\n")
        sb.append("进程日志尾部：\n").append(serverLogTail(context))
        return sb.toString()
    }

    /**
     * 测试本地引擎（主界面“测试本地引擎”按钮用，模型还没下载也能点）。
     *
     * 只做一件事：从 nativeLibraryDir 里取 libllama_server.so，执行
     * `llama-server --help`，不带模型参数、不启动 8080 服务端口、不需要
     * 模型文件。等进程退出后，把标准输出、标准错误、退出码、耗时
     * 全部原样返回给界面显示，不要只报“成功/失败”两个字。
     *
     * 顺序要求：--help 能跑通（有帮助输出、退出码正常），才说明可执行
     * 文件及其依赖库能正常启动；--help 跑不通（Permission denied、
     * 找不到某个 .so、依赖缺失、进程起不来），就不要让用户先去下载
     * 1.4GB 模型，先把这段输出发回来定位。现有的“检查本地服务启动”
     * 和“测试本地生成”都要求模型已下载，不能替代这个功能。
     */
    suspend fun testEngine(context: Context): String = withContext(Dispatchers.IO) {
        val bin = serverBinary(context)
        val sb = StringBuilder()
        sb.append("原生库目录：").append(context.applicationInfo.nativeLibraryDir).append("\n")
        sb.append("推理程序：").append(bin.absolutePath).append("\n")
        sb.append("文件存在：").append(if (bin.exists()) "是" else "否").append("\n")
        if (!bin.exists()) {
            sb.append("结论：文件不存在，无法测试。请确认装的是修复后的新版 APK（APK 里应有 lib/arm64-v8a/libllama_server.so），而不是旧版。\n")
            return@withContext sb.toString()
        }
        sb.append("文件大小：").append(bin.length() / 1024 / 1024).append("MB")
        if (bin.length() < 1024 * 1024) {
            sb.append("（小于 1MB，很可能是没带依赖的 stub，下面的 --help 很可能跑不通或缺库）")
        }
        sb.append("\n")
        sb.append("可执行标记：").append(if (bin.canExecute()) "是" else "否").append("\n")
        sb.append("执行命令：").append(bin.absolutePath).append(" --help（不带模型、不启动服务）\n")
        try { bin.setExecutable(true) } catch (_: Exception) {}
        val outFile = File(context.cacheDir, "engine-help-stdout.log")
        val errFile = File(context.cacheDir, "engine-help-stderr.log")
        try { if (outFile.exists()) outFile.delete() } catch (_: Exception) {}
        try { if (errFile.exists()) errFile.delete() } catch (_: Exception) {}
        val startMs = System.currentTimeMillis()
        try {
            val pb = ProcessBuilder(listOf(bin.absolutePath, "--help"))
            pb.redirectOutput(outFile)
            pb.redirectError(errFile)
            val env = pb.environment()
            val nativeDir = context.applicationInfo.nativeLibraryDir
            val oldLd = env["LD_LIBRARY_PATH"]
            env["LD_LIBRARY_PATH"] = if (oldLd.isNullOrBlank()) nativeDir else "$nativeDir:$oldLd"
            val proc = pb.start()
            // --help 应该几秒内退出；超过 30 秒还没退出就判超时、强制杀掉，
            // 避免界面一直卡住（--help 本来就不该常驻）。
            val finished = proc.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)
            val elapsedMs = System.currentTimeMillis() - startMs
            sb.append("耗时：").append(elapsedMs).append(" 毫秒\n")
            if (!finished) {
                try { proc.destroyForcibly() } catch (_: Exception) {}
                sb.append("退出码：（30 秒超时，未退出，已强制结束）\n")
            } else {
                sb.append("退出码：").append(proc.exitValue()).append("\n")
            }
            val stdout = try { outFile.readText() } catch (e: Exception) { "（读标准输出失败：${e.message}）" }
            val stderr = try { errFile.readText() } catch (e: Exception) { "（读标准错误失败：${e.message}）" }
            sb.append("---- 标准输出（原样） ----\n")
            sb.append(if (stdout.isBlank()) "（标准输出为空）\n" else stdout.take(20000) + if (stdout.length > 20000) "\n（输出过长，已截断到 20000 字）\n" else "\n")
            sb.append("---- 标准错误（原样） ----\n")
            sb.append(if (stderr.isBlank()) "（标准错误为空）\n" else stderr.take(20000) + if (stderr.length > 20000) "\n（输出过长，已截断到 20000 字）\n" else "\n")
            if (finished && proc.exitValue() == 0 && stdout.isNotBlank()) {
                sb.append("结论：--help 跑通了，可执行文件和依赖库能正常启动，可以下一步下载模型。\n")
            } else if (!finished) {
                sb.append("结论：--help 30 秒都没退出，不正常。请把上面全文发回来。\n")
            } else {
                sb.append("结论：--help 没跑通，不要先下载模型。请把上面全文（特别是标准错误和退出码）发回来定位。\n")
            }
        } catch (e: Exception) {
            // 这就是要原样显示的真实错误：Permission denied、找不到文件、
            // 依赖库加载失败等，启动时抛出的原文就在这里。
            val elapsedMs = System.currentTimeMillis() - startMs
            sb.append("耗时：").append(elapsedMs).append(" 毫秒\n")
            sb.append("启动时直接抛错，没有跑出 --help：\n")
            sb.append(e.javaClass.simpleName).append(": ").append(e.message ?: "（无错误信息）").append("\n")
            sb.append("结论：引擎起不来，不要先下载模型。请把上面全文发回来定位。\n")
        }
        sb.toString()
    }

    /** 悬浮球启动时调：模型没下好直接报错，不启动空服务。 */
    suspend fun ensureStarted(context: Context, contextTokens: Int): Boolean = mutex.withLock {
        val wantedReasoning = reasoningMode(context)
        if (isRunning && process?.isAlive == true) {
            if (runningReasoningMode == wantedReasoning) {
                cancelIdleUnloadLocked()
                return true
            }
            // 思考模式被用户改了：只改设置不重启是不会生效的，
            // 这里先杀掉用旧模式跑的服务，下面按新模式重新启动。
            // stop() 里有 2 秒等待，但它不抢 mutex，不会死锁。
            try { process?.destroyForcibly() } catch (_: Exception) {}
            process = null
            isRunning = false
            runningReasoningMode = null
        }
        // 关键：按当前选用模型加载，不再写死 2B。切换模型后这里读到的
        // 就是新模型的文件和 alias；旧模型文件还留在手机里，不会被删。
        val selected = selectedModel(context)
        if (!isModelDownloaded(context, selected)) {
            lastError = "模型还没下载好：当前选用的是 ${selected.displayName}，请先回主界面下载它（${selected.approxSizeLabel}），或先切换回已下载的模型"
            return false
        }
        val bin = serverBinary(context)
        if (!bin.exists()) {
            lastError = "找不到原生推理程序：${bin.absolutePath} 不存在。APK 里应该有 lib/arm64-v8a/libllama_server.so，请确认装的是修复后的新版 APK，而不是旧版（旧版用的是已被废弃的启动方式）"
            return false
        }
        if (bin.length() < 1024 * 1024) {
            lastError = "原生推理程序只有 ${bin.length()} 字节，是没带依赖的 stub，不是完整程序。请删除旧 APK，重新下载修复后的 APK 再装"
            return false
        }
        return try {
            // 原生库目录里的文件一般自带可执行权限；这里再尝试一次，失败也不直接
            // 判死刑，真正的结果看进程能不能起来、报错原文是什么。
            try { bin.setExecutable(true) } catch (_: Exception) {}
            val logFile = serverLogFile(context)
            try { if (logFile.exists()) logFile.delete() } catch (_: Exception) {}
            val ctx = contextTokens.coerceIn(512, 4096)
            val args = listOf(
                bin.absolutePath,
                "-m", modelFile(context, selected).absolutePath,
                "--alias", selected.alias,
                "--host", "127.0.0.1",
                "--port", LocalConfig.LOCAL_PORT.toString(),
                "-c", ctx.toString(),
                "-t", LocalConfig.THREADS.toString(),
                // 注意：不要传 --mmap，新版 llama-server 不认这个参数，
                // 传了就报 invalid argument: --mmap、exitCode=1 提前退出。
                // mmap 本来就是默认的，不传就是 mmap。
                // 推理开关（2026-11-02 实测验证，非假设；2026-11-02 后改为用户可选）：
                // 当前工作流克隆的最新 llama.cpp 里 common/arg.cpp 明确有
                // {"-rea", "--reasoning"}, "[on|off|auto]",
                // "Use reasoning/thinking in the chat ('on', 'off', or 'auto',
                // default: 'auto' (detect from template))"，
                // 所以 --reasoning 是本版 llama-server 真实支持的参数，
                // 不是猜的。值绝不能在这里写死 "off"：它来自
                // reasoningMode(context)，即用户在主界面选的思考模式
                // （off=关闭快，auto=自动，on=开启慢但可能更准）。
                // 用户明确要求不要优先用 --chat-template-kwargs，
                // 因此这里只用 --reasoning，不传 --chat-template-kwargs。
                // 如果将来 llama.cpp 删掉这个参数，启动会像 --mmap 那样
                // 直接报 invalid argument、exitCode=1，工作流里有
                // grep 校验（见 .github/workflows/build-local-apk.yml），
                // 缺了这个参数定义就先构建失败，不会静默发一个坏 APK。
                "--reasoning", wantedReasoning,
                "-b", "512",
                "--log-disable"
                // 注意：故意不传 --chat-template，让模型自带模板决定非思考模式。
                // 也故意不传 --api-key：只绑 127.0.0.1 回环，外面连不进来。
            )
            val pb = ProcessBuilder(args)
            pb.redirectOutput(logFile)
            pb.redirectErrorStream(true)
            // 动态库查找路径指向原生库目录：动态编译时依赖的 .so 都打在同一个目录里。
            val env = pb.environment()
            val nativeDir = context.applicationInfo.nativeLibraryDir
            val oldLd = env["LD_LIBRARY_PATH"]
            env["LD_LIBRARY_PATH"] = if (oldLd.isNullOrBlank()) nativeDir else "$nativeDir:$oldLd"
            process = pb.start()
            // 等 /health 变 200，最长 180 秒；进程提前死了就直接报真实原因。
            val deadline = System.currentTimeMillis() + LocalConfig.SERVER_START_TIMEOUT_MS
            var ok = false
            var exitCode: Int? = null
            while (System.currentTimeMillis() < deadline) {
                val p = process
                if (p == null || !p.isAlive) {
                    try { exitCode = p?.exitValue() } catch (_: Exception) {}
                    break
                }
                if (isHealthy()) { ok = true; break }
                delay(500)
            }
            isRunning = ok
            runningReasoningMode = if (ok) wantedReasoning else null
            if (!ok) {
                val tail = serverLogTail(context)
                lastError = if (exitCode != null) {
                    "本地模型进程提前退出（exitCode=$exitCode）。真实原因见进程日志尾部：$tail。如果日志里有 Permission denied，说明原生库执行权限有问题；如果日志里有找不到某个 .so，说明 APK 里缺依赖库。请把主界面诊断全文发回来。"
                } else {
                    "本地模型加载超时（180 秒没等到 /health）。请确认手机剩余内存足够（6GB 机器请先关掉大游戏），再点一次悬浮球。进程日志尾部：$tail"
                }
                try { process?.destroyForcibly() } catch (_: Exception) {}
                process = null
            } else {
                lastError = null
            }
            ok
        } catch (e: Exception) {
            // 这里就是用户要看的真实报错原文：不要只写“启动失败”，要带异常类型和原文。
            lastError = "本地模型启动失败：${e.javaClass.simpleName}: ${e.message}。原生库路径：${bin.absolutePath}。如果原文是 Permission denied，说明系统不允许执行这个文件，请把主界面诊断全文发回来。"
            try { process?.destroyForcibly() } catch (_: Exception) {}
            process = null
            isRunning = false
            runningReasoningMode = null
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
        runningReasoningMode = null
    }

    /** 供 MainActivity 测试/诊断用：不经过 FloatingService 也能起服务，测完同样会闲置卸载。 */
    suspend fun ensureStartedForTest(context: Context, contextTokens: Int): Boolean {
        return ensureStarted(context, contextTokens)
    }

    /** 给 ApiClient 用：生成前确保服务在跑，否则返回失败原因（带真实报错原文）。 */
    suspend fun ensureStartedForGenerate(context: Context, contextTokens: Int): String? {
        val ok = ensureStarted(context, contextTokens)
        return if (ok) null else (lastError ?: "本地模型未启动")
    }
}
