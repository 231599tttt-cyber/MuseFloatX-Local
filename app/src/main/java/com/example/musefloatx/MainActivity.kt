package com.example.musefloatx

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import java.io.File

/**
 * 本地版主界面：不再有服务器地址、密钥、模型名输入框。
 * 只有三件事：模型状态 + 下载、上下文（默认 1024，可调）、悬浮球启动。
 * 生成全部在手机本地，下载完断网也能用；下载本身需要网络，约 1.40GB。
 */
class MainActivity : ComponentActivity() {
    private fun isWifi(): Boolean {
        return try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val net = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(net) ?: return false
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
        } catch (_: Exception) {
            false
        }
    }

    private fun modelStatusText(): String {
        val f = LocalServer.modelFile(this)
        return when {
            LocalServer.isModelDownloaded(this) ->
                "已下载：${f.length() / 1024 / 1024}MB，之后断网也能生成"
            f.exists() ->
                "未下载完成：已有 ${f.length() / 1024 / 1024}MB（正式文件，大小不对请删掉重下）"
            File(File(filesDir, "models"), LocalConfig.MODEL_FILE_NAME + ".part").exists() ->
                "下载中断过：已有部分文件，点下载可续传"
            else -> "未下载：首次使用需要下载模型，约 1.40GB"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val store = SettingsStore(this)
        setContent {
            MaterialTheme {
                val scope = rememberCoroutineScope()
                var style by remember { mutableStateOf(store.style) }
                var contextText by remember { mutableStateOf(store.contextTokens.toString()) }
                var status by remember { mutableStateOf(modelStatusText()) }
                var downloadProgress by remember { mutableStateOf("") }
                // 下载进度条：0f~1f。原来只有一行小字“下载中：xxMB / xxMB”，
                // 还在 IO 线程里每 64KB 就刷一次状态，用户根本看不到进度，
                // 所以改成带进度条 + 百分比 + 速度 + 剩余时间的明显显示。
                var downloadFraction by remember { mutableStateOf(0f) }
                var testResult by remember { mutableStateOf("") }
                var diagnostic by remember { mutableStateOf("") }
                var engineTest by remember { mutableStateOf("") }
                // 测试引擎输出折叠状态：成功（退出码 0）时默认收起，只看结论；
                // 失败时默认展开，方便把完整输出发回来定位。用户可随时手动展开/收起。
                var engineOutputExpanded by remember { mutableStateOf(false) }
                var showWifiDialog by remember { mutableStateOf(false) }
                var tick by remember { mutableStateOf(0) }
                @Suppress("UNUSED_VARIABLE")
                val unusedTick = tick
                val overlayOk = Settings.canDrawOverlays(this)

                if (showWifiDialog) {
                    AlertDialog(
                        onDismissRequest = { showWifiDialog = false },
                        title = { Text("当前不是 WiFi") },
                        text = { Text("模型约 1.40GB，用流量下载会很耗流量。建议连 WiFi 再下载，确定现在就用流量继续吗？") },
                        confirmButton = {
                            TextButton(onClick = {
                                showWifiDialog = false
                                startDownload(scope, { p, f -> downloadProgress = p; downloadFraction = f }, { s -> status = s; tick += 1 })
                            }) { Text("继续下载") }
                        },
                        dismissButton = {
                            TextButton(onClick = { showWifiDialog = false }) { Text("先不下") }
                        }
                    )
                }

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Text("MuseFloatX 本地版", fontSize = 24.sp, fontWeight = FontWeight.Bold)
                    Text("版本：本地版 1.0（基于第 38 版悬浮球改，AI 引擎已换成本地模型）", fontSize = 13.sp)
                    Text("只在 X 里用的悬浮回复助手：你在 X 里复制帖子，点悬浮球，面板读剪贴板生成回复建议，你自己复制粘贴，不自动发送。不读屏、不截图、不需要无障碍。生成全部在手机本地，下载完断网也能用，不用电脑、不用服务器。", fontSize = 14.sp)
                    Spacer(Modifier.height(16.dp))

                    Text("1. 权限", fontWeight = FontWeight.Bold)
                    Text("悬浮窗：" + if (overlayOk) "已开启" else "未开启")
                    Row {
                        Button(
                            onClick = {
                                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                            },
                            modifier = Modifier.padding(end = 8.dp)
                        ) { Text("开悬浮窗") }
                    }
                    OutlinedButton(onClick = { tick += 1 }) { Text("刷新权限状态") }
                    Spacer(Modifier.height(16.dp))

                    Text("先做：测试本地引擎（模型还没下载也能点，先做这一步）", fontWeight = FontWeight.Bold)
                    Text("顺序必须是：先点下面这个按钮，确认底层引擎能启动，再下载模型。点它只会跑 llama-server --help，不带模型、不启动服务端口、不需要模型文件。--help 能跑通（有帮助输出、退出码 0），才说明可执行文件和依赖库能正常启动；跑不通（比如 Permission denied、找不到某个 .so）就不要先去下载 1.4GB 模型，先把这段输出发回来。", fontSize = 13.sp)
                    Button(onClick = {
                        engineTest = "正在运行 llama-server --help…（应该几秒内出结果）"
                        engineOutputExpanded = false
                        scope.launch {
                            val result = LocalServer.testEngine(this@MainActivity)
                            engineTest = result
                            // 成功时默认收起（--help 原文很长，不用一屏全摊开）；
                            // 失败时默认展开，方便把完整输出发回来定位。
                            engineOutputExpanded = !result.contains("结论：--help 跑通了")
                        }
                    }) { Text("测试本地引擎（llama-server --help）") }
                    if (engineTest.isNotBlank()) {
                        if (engineTest.startsWith("正在运行")) {
                            Text(engineTest, fontSize = 12.sp)
                        } else {
                            // 摘要行：只取退出码、耗时、结论三行，成功时先只看这个。
                            val summaryLines = engineTest.lines().filter { line ->
                                line.startsWith("退出码") || line.startsWith("耗时") || line.startsWith("结论")
                            }
                            if (summaryLines.isNotEmpty()) {
                                Text(summaryLines.joinToString("\n"), fontSize = 12.sp)
                            }
                            OutlinedButton(onClick = { engineOutputExpanded = !engineOutputExpanded }) {
                                Text(if (engineOutputExpanded) "收起完整输出" else "展开查看完整输出（--help 原文很长）")
                            }
                            if (engineOutputExpanded) Text(engineTest, fontSize = 12.sp)
                        }
                    }
                    Spacer(Modifier.height(16.dp))

                    Text("2. 本地模型：Qwen3.5-2B Q4_K_M", fontWeight = FontWeight.Bold)
                    Text("来源：bartowski/Qwen_Qwen3.5-2B-GGUF（原模型 Qwen/Qwen3.5-2B，本身就是后训练对话版本，不是 Base 版），文件 Qwen3.5-2B-Q4_K_M.gguf，约 1.40GB。首次下载一次，之后完全离线。", fontSize = 13.sp)
                    Text(status, fontSize = 13.sp)
                    if (downloadProgress.isNotBlank()) {
                        Text(downloadProgress, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(4.dp))
                        LinearProgressIndicator(
                            progress = { downloadFraction },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(4.dp))
                    }
                    Row {
                        Button(
                            onClick = {
                                if (LocalServer.isModelDownloaded(this@MainActivity)) {
                                    status = modelStatusText()
                                    return@Button
                                }
                                if (!isWifi()) {
                                    showWifiDialog = true
                                } else {
                                    startDownload(scope, { p, f -> downloadProgress = p; downloadFraction = f }, { s -> status = s; tick += 1 })
                                }
                            },
                            modifier = Modifier.padding(end = 8.dp)
                        ) { Text("下载模型") }
                        OutlinedButton(
                            onClick = { ModelDownloader.cancel() },
                            modifier = Modifier.padding(end = 8.dp)
                        ) { Text("取消下载") }
                        OutlinedButton(onClick = {
                            status = modelStatusText()
                            tick += 1
                        }) { Text("刷新状态") }
                    }
                    OutlinedButton(onClick = {
                        // 删模型：正式文件和 .part 一起删，删完回未下载状态。
                        try { LocalServer.modelFile(this@MainActivity).delete() } catch (_: Exception) {}
                        try { File(File(filesDir, "models"), LocalConfig.MODEL_FILE_NAME + ".part").delete() } catch (_: Exception) {}
                        LocalServer.stop()
                        status = modelStatusText()
                        tick += 1
                    }) { Text("删除已下载模型") }
                    Spacer(Modifier.height(8.dp))
                    Text("模型加载策略：悬浮球启动时加载，关闭时卸载；10 分钟没有生成也自动卸载，释放内存；用 mmap 加载，不常驻占着 1.6GB 不放。", fontSize = 13.sp)
                    Spacer(Modifier.height(16.dp))

                    Text("3. 上下文（可调，第一版默认 1024）", fontWeight = FontWeight.Bold)
                    Text("不要固定死 512：第一版先按 1024 测试。6GB 手机如果卡，可以改成 512；改完下次加载模型生效。", fontSize = 13.sp)
                    OutlinedTextField(
                        value = contextText,
                        onValueChange = {
                            contextText = it
                            it.toIntOrNull()?.let { v -> store.contextTokens = v }
                        },
                        label = { Text("上下文 tokens，例如 1024") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(16.dp))

                    Text("4. 风格（默认风格，面板里也能换）", fontWeight = FontWeight.Bold)
                    Row {
                        listOf("自然", "幽默", "简短").forEach { s ->
                            if (style == s) {
                                Button(onClick = { style = s; store.style = s }, modifier = Modifier.padding(end = 8.dp)) { Text(s) }
                            } else {
                                OutlinedButton(onClick = { style = s; store.style = s }, modifier = Modifier.padding(end = 8.dp)) { Text(s) }
                            }
                        }
                    }
                    Button(onClick = {
                        testResult = "本地测试中…（首次要先加载模型，可能要十几秒）"
                        scope.launch {
                            val r = ApiClient.testLocal(this@MainActivity, store.contextTokens)
                            testResult = r.fold({ "本地测试成功：$it" }, { "本地测试失败：${it.message}" })
                        }
                    }) { Text("测试本地生成") }
                    if (testResult.isNotBlank()) Text(testResult, fontSize = 13.sp)
                    Spacer(Modifier.height(16.dp))

                    Text("4b. 本地服务启动诊断（启动失败时看这里）", fontWeight = FontWeight.Bold)
                    Text("如果测试失败或点球后没反应，不要只说“失败”：点下面按钮，这里会显示真实报错原文（比如 Permission denied、找不到某个 .so、进程提前退出、加载超时）和进程日志尾部。把这段全文截图或发回来，才能定位是权限问题、缺库问题还是内存不够。", fontSize = 13.sp)
                    Row {
                        Button(
                            onClick = {
                                diagnostic = "正在尝试启动本地服务并收集诊断…"
                                scope.launch {
                                    val ok = LocalServer.ensureStartedForTest(this@MainActivity, store.contextTokens)
                                    diagnostic = "启动结果：" + if (ok) "成功" else "失败" + "\n" + LocalServer.diagnosticInfo(this@MainActivity)
                                }
                            },
                            modifier = Modifier.padding(end = 8.dp)
                        ) { Text("检查本地服务启动") }
                        OutlinedButton(onClick = {
                            diagnostic = LocalServer.diagnosticInfo(this@MainActivity)
                        }) { Text("只看当前诊断（不启动）") }
                    }
                    if (diagnostic.isNotBlank()) Text(diagnostic, fontSize = 12.sp)
                    Spacer(Modifier.height(16.dp))

                    Text("5. 悬浮球", fontWeight = FontWeight.Bold)
                    Text("启动悬浮球时会加载本地模型（首次加载要等一会儿）；关闭悬浮球时会卸载模型、释放内存。", fontSize = 13.sp)
                    Row {
                        Button(onClick = {
                            if (!Settings.canDrawOverlays(this@MainActivity)) {
                                Toast.makeText(this@MainActivity, "请先开悬浮窗权限", Toast.LENGTH_SHORT).show()
                                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                                return@Button
                            }
                            if (!LocalServer.isModelDownloaded(this@MainActivity)) {
                                Toast.makeText(this@MainActivity, "请先下载模型", Toast.LENGTH_SHORT).show()
                                return@Button
                            }
                            val intent = Intent(this@MainActivity, FloatingService::class.java).apply { action = "START" }
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
                        }, modifier = Modifier.padding(end = 8.dp)) { Text("启动悬浮球") }
                        OutlinedButton(onClick = {
                            val intent = Intent(this@MainActivity, FloatingService::class.java).apply { action = "STOP" }
                            startService(intent)
                        }) { Text("停止（同时卸载模型）") }
                    }
                    Spacer(Modifier.height(16.dp))
                    Text("使用方法：先在 X 里复制帖子，再点悬浮球，就按上次选的风格生成一次；想换风格，点另一个风格就切换一次；同风格想换一句，点重新生成。点复制后去 X 粘贴，不自动发送。没复制就点球，面板会是空的；自动读不到时，关面板、重新复制、再点球。不需要无障碍，不截图，不 OCR。面板里生成时会显示“本地 / 离线”，表示这次是手机本地模型生成的，不是服务器。", fontSize = 13.sp)
                }
            }
        }
    }

    private fun startDownload(
        scope: kotlinx.coroutines.CoroutineScope,
        onProgress: (String, Float) -> Unit,
        onDone: (String) -> Unit
    ) {
        scope.launch {
            onProgress("开始下载…", 0f)
            // 为什么原来“根本看不到”：下载器每读 64KB 就回调一次，
            // 一秒能刷几百次状态，界面根本来不及画；而且回调在 IO 线程，
            // 不是主线程更新。所以这里节流到每 500 毫秒刷一次，
            // 并且用 scope.launch 发到主线程更新，并算速度和剩余时间。
            val startMs = System.currentTimeMillis()
            var firstDownloaded: Long? = null
            var lastUpdateMs = 0L
            val r = ModelDownloader.download(this@MainActivity) { downloaded, total ->
                if (firstDownloaded == null) firstDownloaded = downloaded
                val now = System.currentTimeMillis()
                val fraction = if (total > 0) (downloaded.toFloat() / total).coerceIn(0f, 1f) else 0f
                if (now - lastUpdateMs < 500 && fraction < 1f) return@download
                lastUpdateMs = now
                val elapsedSec = ((now - startMs) / 1000.0).coerceAtLeast(1.0)
                val deltaMB = ((downloaded - (firstDownloaded ?: downloaded)) / 1024.0 / 1024.0)
                val speedMBs = deltaMB / elapsedSec
                val percent = (fraction * 100).toInt()
                val etaText = if (speedMBs > 0.1) {
                    val remainSec = ((total - downloaded) / 1024.0 / 1024.0 / speedMBs).toLong()
                    if (remainSec >= 60) "，还剩约 ${remainSec / 60} 分 ${remainSec % 60} 秒" else "，还剩约 $remainSec 秒"
                } else ""
                val speedText = if (speedMBs > 0.1) "，${"%.1f".format(speedMBs)}MB/秒" else ""
                val text = "下载中：$percent% · ${downloaded / 1024 / 1024}MB / ${total / 1024 / 1024}MB$speedText$etaText"
                scope.launch { onProgress(text, fraction) }
            }
            r.fold(
                { onDone(it); onProgress("", 1f) },
                { onDone(it.message ?: "下载失败") }
            )
        }
    }
}
