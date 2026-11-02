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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 本地版主界面：不再有服务器地址、密钥、模型名输入框。
 * 只有三件事：模型状态 + 下载、上下文（默认 1024，可调）、悬浮球启动。
 * 生成全部在手机本地，下载完断网也能用；下载本身需要网络。
 *
 * 模型切换（2026-11-02 新增，只改代码、未编包）：
 * - 2B 和 4B 各是一张卡片，各存各的文件，切换不删任何一个。
 * - 当前选用模型存在 SettingsStore.selectedModelId（默认 2B）；
 *   切换时先在 IO 线程停掉正在跑的服务、再改选用值，
 *   下次启动/生成时 LocalServer 按新值加载新模型。
 * - 选了一个还没下载的模型时不崩：状态栏写“未下载”，
 *   点启动悬浮球/测试生成时会明确说“请先下载这个模型”。
 *
 * 按钮反馈（2026-11-02 用户投诉后统一改，之前“点了非常卡、
 * 不知道删没删、像摆设”的直接原因有两个）：
 * 1) 旧删除按钮在主线程里直接调 LocalServer.stop()，而 stop() 里
 *    有约 2 秒优雅退出等待，主线程一卡就是“点了没反应”；
 *    现在删除、切换等耗时活一律走 IO 线程（ModelDownloader.deleteModel
 *    内部就是 withContext(Dispatchers.IO)，切换也在 IO 里先停服务）。
 * 2) 旧按钮干完只变一行小字状态，没有明确结果。现在统一规则：
 *    点一下立刻变“正在…”，同时禁用（防重复点）；干完后状态文字
 *    和 Toast 两处同时给明确结果（成功写“已删除…”/“已切换…”、
 *    失败写失败原因），绝不只留一行小字让人猜。
 *    删除模型因为是 1.4GB/3.01GB 的文件，动手前先弹确认框防手滑。
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

    /** 指定模型的状态文字（读磁盘，界面每次重绘时重新算，刷新按钮靠 tick 触发重绘）。 */
    private fun modelStatusText(model: LocalModel): String {
        val f = LocalServer.modelFile(this, model)
        return when {
            LocalServer.isModelDownloaded(this, model) ->
                "已下载：${f.length() / 1024 / 1024}MB，之后断网也能生成"
            f.exists() ->
                "未下载完成：已有 ${f.length() / 1024 / 1024}MB（正式文件，大小不对请删掉重下）"
            File(File(filesDir, "models"), model.fileName + ".part").exists() ->
                "下载中断过：已有部分文件，点下载可续传"
            else -> "未下载：首次使用需要下载模型，${model.approxSizeLabel}"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val store = SettingsStore(this)
        setContent {
            MaterialTheme {
                val scope = rememberCoroutineScope()
                var style by remember { mutableStateOf(store.style) }
                /** 思考模式界面状态（和 SettingsStore.reasoningMode 同步，默认 "off"）。 */
                var reasoningMode by remember { mutableStateOf(store.reasoningMode) }
                var contextText by remember { mutableStateOf(store.contextTokens.toString()) }
                var downloadProgress by remember { mutableStateOf("") }
                // 下载进度条：0f~1f。原来只有一行小字“下载中：xxMB / xxMB”，
                // 还在 IO 线程里每 64KB 就刷一次状态，用户根本看不到进度，
                // 所以改成带进度条 + 百分比 + 速度 + 剩余时间的明显显示。
                var downloadFraction by remember { mutableStateOf(0f) }
                /** 当前正在下载哪个模型（下载进度条就画在那张卡片下面）。 */
                var downloadModelId by remember { mutableStateOf<String?>(null) }
                var testResult by remember { mutableStateOf("") }
                var diagnostic by remember { mutableStateOf("") }
                var engineTest by remember { mutableStateOf("") }
                // 测试引擎输出折叠状态：成功（退出码 0）时默认收起，只看结论；
                // 失败时默认展开，方便把完整输出发回来定位。用户可随时手动展开/收起。
                var engineOutputExpanded by remember { mutableStateOf(false) }
                /** 非 WiFi 时弹确认框，要下的是哪个模型（不是简单的开/关）。 */
                var showWifiDialogFor by remember { mutableStateOf<LocalModel?>(null) }
                /** 删除确认框：要删的是哪个模型，null = 不弹框。 */
                var showDeleteConfirm by remember { mutableStateOf<LocalModel?>(null) }
                var tick by remember { mutableStateOf(0) }
                @Suppress("UNUSED_VARIABLE")
                val unusedTick = tick
                /** 当前选用模型 id（界面状态，和 SettingsStore.selectedModelId 同步）。 */
                var selectedModelId by remember { mutableStateOf(store.selectedModelId) }
                /** 当前选用模型规格；天生非思考模型时思考开关要禁用（见 4a）。 */
                val selectedModelSpec = LocalConfig.modelForId(selectedModelId)
                val isNonThinkingModel = selectedModelSpec.isNonThinking
                /**
                 * 按钮反馈核心状态：
                 * - busyButton：当前正在干活的按钮 key（如“删除模型”），
                 *   非 null 时所有主按钮禁用（取消下载除外，它就是用来打断下载的），
                 *   干活的按钮文字改成“正在…”，绝不出现“点了没反应”。
                 * - actionMessage：最近一次操作的明确结果，状态文字 + Toast 两处都显示。
                 */
                var busyButton by remember { mutableStateOf<String?>(null) }
                var actionMessage by remember { mutableStateOf("") }

                fun toastShort(msg: String) {
                    Toast.makeText(this@MainActivity, msg, Toast.LENGTH_SHORT).show()
                }

                fun toastLong(msg: String) {
                    Toast.makeText(this@MainActivity, msg, Toast.LENGTH_LONG).show()
                }

                /**
                 * 通用带反馈动作：点一下先变“正在…”、禁用按钮，干完后
                 * 状态文字 + Toast 同时给结果，失败带原因。work 里耗时
                 * 部分必须自己切到 IO 线程（见各调用处），不要在主线程
                 * 里睡 2 秒或删大文件。
                 */
                fun runFeedback(
                    key: String,
                    busyText: String,
                    work: suspend () -> String,
                    onResult: ((String) -> Unit)? = null
                ) {
                    if (busyButton != null) {
                        toastShort("正在忙：$busyButton，请等它干完再点")
                        return
                    }
                    busyButton = key
                    actionMessage = busyText
                    scope.launch {
                        // 修复（2026-11-02 思考切换卡住）：busyButton 必须在
                        // finally 里清掉，否则 work 一旦被取消/异常中断，
                        // 按钮会永久停在“正在切换…”、测试按钮一直变灰。
                        // work 本身只做停服务 + 改设置，应在几秒内完成；
                        // 若真超时，finally 仍会放行按钮并显示失败原因。
                        try {
                            val result = try {
                                work()
                            } catch (e: Exception) {
                                "失败：${e.message ?: "未知错误"}"
                            }
                            onResult?.invoke(result)
                            actionMessage = result
                            toastLong(result)
                        } finally {
                            busyButton = null
                            tick += 1
                        }
                    }
                }

                /** 下载指定模型（带反馈：下载按钮变“正在下载…”，干完 Toast + 状态刷新）。 */
                fun startDownloadFor(model: LocalModel) {
                    if (ModelDownloader.isDownloading) {
                        toastShort("正在下载中，请等它下完或点取消下载")
                        return
                    }
                    if (LocalServer.isModelDownloaded(this@MainActivity, model)) {
                        actionMessage = "${model.displayName} 已经下载好了，不用再下"
                        toastShort(actionMessage)
                        tick += 1
                        return
                    }
                    busyButton = "下载模型"
                    downloadModelId = model.id
                    actionMessage = "开始下载 ${model.displayName}…"
                    startDownload(
                        model,
                        scope,
                        { p, f -> downloadProgress = p; downloadFraction = f },
                        { s ->
                            busyButton = null
                            downloadModelId = null
                            actionMessage = s
                            toastLong(s)
                            tick += 1
                        }
                    )
                }

                val wifiModel = showWifiDialogFor
                if (wifiModel != null) {
                    AlertDialog(
                        onDismissRequest = { showWifiDialogFor = null },
                        title = { Text("当前不是 WiFi") },
                        text = { Text("${wifiModel.displayName} 约 ${wifiModel.approxSizeLabel}，用流量下载会很耗流量。建议连 WiFi 再下载，确定现在就用流量继续吗？") },
                        confirmButton = {
                            TextButton(onClick = {
                                showWifiDialogFor = null
                                startDownloadFor(wifiModel)
                            }) { Text("继续下载") }
                        },
                        dismissButton = {
                            TextButton(onClick = { showWifiDialogFor = null }) { Text("先不下") }
                        }
                    )
                }

                val deleteModel = showDeleteConfirm
                if (deleteModel != null) {
                    AlertDialog(
                        onDismissRequest = { showDeleteConfirm = null },
                        title = { Text("删除模型？") },
                        text = { Text("${deleteModel.displayName} 约 ${deleteModel.approxSizeLabel}，删掉后要重新下载才能用，另一个模型不受影响。确定要删吗？") },
                        confirmButton = {
                            TextButton(onClick = {
                                showDeleteConfirm = null
                                // 删除走 ModelDownloader.deleteModel（内部 IO 线程，
                                // 先停服务再删文件），不在主线程卡 2 秒。
                                // 删除忙碌 key 必须带模型 id：之前 key 是共用的
                                // “删除模型”，两张卡片的删除按钮文字都会同时
                                // 变成“正在删除…”，看着就像两个按钮绑在一起。
                                // 改成按卡片分开后，只有被删的那张卡片显示
                                // “正在删除…”，另一张只变灰禁用（防重复点）。
                                runFeedback(
                                    key = "删除模型-${deleteModel.id}",
                                    busyText = "正在删除 ${deleteModel.displayName}…",
                                    work = {
                                        ModelDownloader.deleteModel(this@MainActivity, deleteModel)
                                            .fold({ it }, { it.message ?: "删除失败" })
                                    }
                                )
                            }) { Text("确定删除") }
                        },
                        dismissButton = {
                            TextButton(onClick = { showDeleteConfirm = null }) { Text("先不删") }
                        }
                    )
                }

                val overlayOk = Settings.canDrawOverlays(this)

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
                                actionMessage = "正在打开悬浮窗权限设置…"
                                toastShort(actionMessage)
                                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                            },
                            modifier = Modifier.padding(end = 8.dp)
                        ) { Text("开悬浮窗") }
                    }
                    OutlinedButton(
                        onClick = {
                            actionMessage = "权限状态已刷新：悬浮窗=" + if (Settings.canDrawOverlays(this@MainActivity)) "已开启" else "未开启"
                            toastShort(actionMessage)
                            tick += 1
                        },
                        enabled = busyButton == null
                    ) { Text("刷新权限状态") }
                    Spacer(Modifier.height(16.dp))

                    Text("先做：测试本地引擎（模型还没下载也能点，先做这一步）", fontWeight = FontWeight.Bold)
                    Text("顺序必须是：先点下面这个按钮，确认底层引擎能启动，再下载模型。点它只会跑 llama-server --help，不带模型、不启动服务端口、不需要模型文件。--help 能跑通（有帮助输出、退出码 0），才说明可执行文件和依赖库能正常启动；跑不通（比如 Permission denied、找不到某个 .so）就不要先去下载模型，先把这段输出发回来。", fontSize = 13.sp)
                    Button(
                        onClick = {
                            if (busyButton != null) {
                                toastShort("正在忙：$busyButton，请等它干完再点")
                                return@Button
                            }
                            busyButton = "测试本地引擎"
                            actionMessage = "正在运行 llama-server --help…（应该几秒内出结果）"
                            engineTest = actionMessage
                            engineOutputExpanded = false
                            scope.launch {
                                val result = LocalServer.testEngine(this@MainActivity)
                                engineTest = result
                                // 成功时默认收起（--help 原文很长，不用一屏全摊开）；
                                // 失败时默认展开，方便把完整输出发回来定位。
                                engineOutputExpanded = !result.contains("结论：--help 跑通了")
                                actionMessage = if (result.contains("结论：--help 跑通了")) {
                                    "引擎测试通过：--help 跑通了，可以下一步下载模型"
                                } else {
                                    "引擎测试失败：见下面完整输出，请把全文发回来定位"
                                }
                                toastLong(actionMessage)
                                busyButton = null
                                tick += 1
                            }
                        },
                        enabled = busyButton == null
                    ) { Text(if (busyButton == "测试本地引擎") "测试本地引擎中…" else "测试本地引擎（llama-server --help）") }
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

                    Text("2. 本地模型：可以切换 2B / 4B", fontWeight = FontWeight.Bold)
                    Text("两个模型各存各的文件，切换不删任何一个；切换后下次启动/生成时加载新模型。2B 约 1.40GB、快、省内存；4B 约 3.01GB、可能质量更好但更慢更占内存，12GB 手机没问题，6GB 手机可能卡，先想好再下。", fontSize = 13.sp)
                    if (actionMessage.isNotBlank()) {
                        Text("操作反馈：$actionMessage", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                    // 两张模型卡片：每张都有名字、大小、下载状态、下载/取消、
                    // 刷新、设为当前模型、删除，状态和按钮反馈都按卡片分开显示。
                    for (model in LocalConfig.MODELS) {
                        val isSelected = selectedModelId == model.id
                        val isDownloadingThis = downloadModelId == model.id
                        Spacer(Modifier.height(8.dp))
                        Text(
                            model.displayName + if (isSelected) "（当前使用中）" else "",
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            "来源：${model.repo}（原模型 ${if (model.id == "4B") "Qwen/Qwen3.5-4B" else "Qwen/Qwen3-4B-Instruct-2507"}，本身就是后训练对话版本，不是 Base 版），文件 ${model.fileName}，${model.approxSizeLabel}。首次下载一次，之后完全离线。" +
                                if (model.isNonThinking) "这个模型天生就是非思考模型，思考开关对它不起作用，已固定关闭。" else "",
                            fontSize = 13.sp
                        )
                        Text(modelStatusText(model), fontSize = 13.sp)
                        if (isDownloadingThis && downloadProgress.isNotBlank()) {
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
                                    if (!isWifi()) {
                                        showWifiDialogFor = model
                                    } else {
                                        startDownloadFor(model)
                                    }
                                },
                                enabled = busyButton == null,
                                modifier = Modifier.padding(end = 8.dp)
                            ) { Text(if (isDownloadingThis) "正在下载…" else "下载模型") }
                            OutlinedButton(
                                onClick = {
                                    if (ModelDownloader.isDownloading) {
                                        ModelDownloader.cancel()
                                        actionMessage = "已请求取消下载，已下载的部分会保留，下次点下载可续传"
                                    } else {
                                        actionMessage = "当前没有在下载，不用取消"
                                    }
                                    toastShort(actionMessage)
                                    tick += 1
                                }
                                // 取消下载就是用来打断下载的，下载进行中时也不能禁用它。
                            ) { Text("取消下载") }
                            OutlinedButton(
                                onClick = {
                                    actionMessage = "模型状态已刷新：${model.displayName}，" + modelStatusText(model)
                                    toastShort("模型状态已刷新")
                                    tick += 1
                                },
                                enabled = busyButton == null,
                                modifier = Modifier.padding(start = 8.dp)
                            ) { Text("刷新状态") }
                        }
                        Row {
                            if (isSelected) {
                                OutlinedButton(
                                    onClick = {
                                        actionMessage = "${model.displayName} 已经是当前模型"
                                        toastShort(actionMessage)
                                    },
                                    enabled = busyButton == null
                                ) { Text("当前使用中") }
                            } else {
                                Button(
                                    onClick = {
                                        // 切换模型：先在 IO 线程停掉正在跑的服务（stop 里有
                                        // 约 2 秒等待，主线程调会卡），再改选用值；
                                        // 旧模型文件不动，新模型没下好时只提示、不崩。
                                        runFeedback(
                                            key = "切换模型",
                                            busyText = "正在切换到 ${model.displayName}…",
                                            work = {
                                                withContext(Dispatchers.IO) {
                                                    LocalServer.stop()
                                                }
                                                store.selectedModelId = model.id
                                                selectedModelId = model.id
                                                if (LocalServer.isModelDownloaded(this@MainActivity, model)) {
                                                    "已切换到 ${model.displayName}，下次启动/生成时加载它"
                                                } else {
                                                    "已切换到 ${model.displayName}，但它还没下载，请先下载它（${model.approxSizeLabel}）"
                                                }
                                            }
                                        )
                                    },
                                    enabled = busyButton == null,
                                    modifier = Modifier.padding(end = 8.dp)
                                ) { Text(if (busyButton == "切换模型") "正在切换…" else "设为当前模型") }
                            }
                            OutlinedButton(
                                onClick = { showDeleteConfirm = model },
                                enabled = busyButton == null,
                                modifier = Modifier.padding(start = 8.dp)
                            ) { Text(if (busyButton == "删除模型-${model.id}") "正在删除…" else "删除已下载模型") }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text("模型加载策略：悬浮球启动时加载，关闭时卸载；10 分钟没有生成也自动卸载，释放内存；用 mmap 加载，不常驻占着内存不放。", fontSize = 13.sp)
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
                        listOf("自然", "幽默", "简短", "友好").forEach { s ->
                            if (style == s) {
                                Button(
                                    onClick = {
                                        style = s
                                        store.style = s
                                        // 风格按钮的即时反馈就是按钮本身变实心 + 这行状态文字，
                                        // 不再额外弹 Toast（风格切换很频繁，弹多了烦）。
                                        actionMessage = "风格已切换为 $s"
                                    },
                                    modifier = Modifier.padding(end = 8.dp)
                                ) { Text(s) }
                            } else {
                                OutlinedButton(
                                    onClick = {
                                        style = s
                                        store.style = s
                                        actionMessage = "风格已切换为 $s"
                                    },
                                    modifier = Modifier.padding(end = 8.dp)
                                ) { Text(s) }
                            }
                        }
                    }
                    Text("4a. 思考模式（可选，默认关闭最快）", fontWeight = FontWeight.Bold)
                    Text(
                        "关闭（快）：和现在一样，生成非常快，2B 直答容易套话。自动：让模型模板自己决定是否思考。开启（慢但可能更准）：强制思考，第一次生成会明显变慢，质量不保证一定更好，只是给你一个可以自己试的开关。思考开/自动时，max_tokens 会自动抬高（自然/幽默 600、简短 300），防止思考把 token 预算耗完、正文变空；思考关时还是 180/80。改动后会先停掉正在跑的服务，下次启动/生成时按新模式重起，只改选项、不重启是不会生效的。",
                        fontSize = 13.sp
                    )
                    if (isNonThinkingModel) {
                        Text(
                            "当前选用的是 ${selectedModelSpec.displayName}，它本身就是非思考模型，思考开关对它不起作用，已固定关闭，不用再切自动/开启。切回旧 4B 卡片才能再用思考开关。",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Row {
                        listOf(
                            "off" to "关闭（快）",
                            "auto" to "自动",
                            "on" to "开启（慢但可能更准）"
                        ).forEach { (mode, label) ->
                            // 修复（2026-11-02 截图假象）：旧 key 是共用的
                            // “切换思考模式”，切一个模式时三个按钮里未选中的
                            // 两个都会同时显示“正在切换…”，看着像卡住了。
                            // 改成按模式分开的 key，只有被点的那个按钮变
                            // “正在切换…”，其他按钮只变灰禁用。
                            val buttonText = if (busyButton == "切换思考模式-$mode") "正在切换…" else label
                            if (reasoningMode == mode) {
                                Button(
                                    onClick = {
                                        actionMessage = "思考模式已经是$label，不用重复切换"
                                        toastShort(actionMessage)
                                    },
                                    enabled = busyButton == null && !isNonThinkingModel,
                                    modifier = Modifier.padding(end = 8.dp)
                                ) { Text(label) }
                            } else {
                                OutlinedButton(
                                    onClick = {
                                        // 取消 + 切回关闭（2026-11-02 用户要求）：
                                        // 测试本地生成进行中时，思考“关闭”不能是
                                        // 永久灰着点不了的状态，否则用户被卡在思考
                                        // 模式里、只能干等 60 秒超时。点关闭时先
                                        // 取消这次生成，再切回关闭：取消是立刻断
                                        // HTTP 的，测试那通很快就会以“已取消生成”
                                        // 收尾、busyButton 清掉，这里等它清掉（最
                                        // 多 5 秒）再走正常切换；等不到就提示再点
                                        // 一次，不要静默吞掉这次点击。取消只是取消
                                        // 这次生成，不删模型、不停服务（切换本身
                                        // 会停一次服务、那是切换的正常步骤）。
                                        if (mode == "off" && busyButton == "测试本地生成") {
                                            ApiClient.cancelGeneration()
                                            toastShort("已取消这次生成，正在切回思考关闭…")
                                            scope.launch {
                                                var waited = 0
                                                while (busyButton != null && waited < 50) {
                                                    delay(100)
                                                    waited += 1
                                                }
                                                if (busyButton == null) {
                                                    runFeedback(
                                                        key = "切换思考模式-off",
                                                        busyText = "正在切换思考模式到关闭（快）…",
                                                        work = {
                                                            withContext(Dispatchers.IO) {
                                                                LocalServer.stop()
                                                            }
                                                            store.reasoningMode = "off"
                                                            reasoningMode = "off"
                                                            "已切换思考模式为关闭（快），下次启动/生成时按新模式运行（最快）"
                                                        }
                                                    )
                                                } else {
                                                    toastShort("取消后仍在忙，请再点一次关闭")
                                                }
                                            }
                                            return@OutlinedButton
                                        }
                                        // 切换思考模式：先在 IO 线程停掉正在跑的服务
                                        // （stop 里有约 2 秒等待，主线程调会卡），
                                        // 再改选用值；LocalServer.ensureStarted()
                                        // 下次启动时会按新值传 --reasoning。
                                        // 旧服务没停干净时，ensureStarted() 里还有
                                        // 一层模式不一致就杀旧重起的兜底。
                                        runFeedback(
                                            key = "切换思考模式-$mode",
                                            busyText = "正在切换思考模式到$label…",
                                            work = {
                                                withContext(Dispatchers.IO) {
                                                    LocalServer.stop()
                                                }
                                                store.reasoningMode = mode
                                                reasoningMode = mode
                                                "已切换思考模式为$label，下次启动/生成时按新模式运行" +
                                                    if (mode == "off") "（最快）" else "（会变慢，质量不保证一定更好）"
                                            }
                                        )
                                    },
                                    // 只有思考“关闭”在测试进行中也可点（上面那条
                                    // 取消 + 切回关闭的路）；其他思考按钮在忙时仍禁用，
                                    // 避免在测试中途又去切自动/开启、把状态搅乱。
                                    // 天生非思考模型时全部禁用（上面已写明固定关闭）。
                                    enabled = !isNonThinkingModel && (busyButton == null || (mode == "off" && busyButton == "测试本地生成")),
                                    modifier = Modifier.padding(end = 8.dp)
                                ) { Text(buttonText) }
                            }
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    Row {
                        Button(
                            onClick = {
                                runFeedback(
                                    key = "测试本地生成",
                                    busyText = "本地测试中…（首次要先加载模型，可能要十几秒）",
                                    work = {
                                        val r = ApiClient.testLocal(this@MainActivity, store.contextTokens)
                                        r.fold({ "本地测试成功：$it" }, { "本地测试失败：${it.message}" })
                                    },
                                    onResult = { result -> testResult = result }
                                )
                            },
                            enabled = busyButton == null,
                            modifier = Modifier.padding(end = 8.dp)
                        ) { Text(if (busyButton == "测试本地生成") "测试本地生成中…" else "测试本地生成") }
                        // 取消（2026-11-02 用户要求）：只在测试进行中出现，
                        // 其他按钮都禁用时它仍可点（和取消下载同例）。
                        // 点取消立刻断这次 HTTP 请求，ApiClient 见取消标记
                        // 就直接报“已取消生成”、不再重试，runFeedback 的
                        // finally 会把按钮恢复空闲，不用等 60 秒超时。
                        // 只取消这次生成，不改思考模式、不删模型、不停服务。
                        if (busyButton == "测试本地生成") {
                            OutlinedButton(
                                onClick = {
                                    ApiClient.cancelGeneration()
                                    toastShort("已取消这次生成")
                                }
                            ) { Text("取消") }
                        }
                    }
                    if (testResult.isNotBlank()) Text(testResult, fontSize = 13.sp)
                    Spacer(Modifier.height(16.dp))

                    Text("4b. 本地服务启动诊断（启动失败时看这里）", fontWeight = FontWeight.Bold)
                    Text("如果测试失败或点球后没反应，不要只说“失败”：点下面按钮，这里会显示真实报错原文（比如 Permission denied、找不到某个 .so、进程提前退出、加载超时）和进程日志尾部。把这段全文截图或发回来，才能定位是权限问题、缺库问题还是内存不够。", fontSize = 13.sp)
                    Row {
                        Button(
                            onClick = {
                                runFeedback(
                                    key = "检查本地服务启动",
                                    busyText = "正在尝试启动本地服务并收集诊断…",
                                    work = {
                                        val ok = LocalServer.ensureStartedForTest(this@MainActivity, store.contextTokens)
                                        "启动结果：" + if (ok) "成功" else "失败"
                                    },
                                    onResult = { result ->
                                        diagnostic = result + "\n" + LocalServer.diagnosticInfo(this@MainActivity)
                                    }
                                )
                            },
                            enabled = busyButton == null,
                            modifier = Modifier.padding(end = 8.dp)
                        ) { Text(if (busyButton == "检查本地服务启动") "检查中…" else "检查本地服务启动") }
                        OutlinedButton(
                            onClick = {
                                diagnostic = LocalServer.diagnosticInfo(this@MainActivity)
                                actionMessage = "诊断已刷新（没有重新启动服务）"
                                toastShort(actionMessage)
                                tick += 1
                            },
                            enabled = busyButton == null
                        ) { Text("只看当前诊断（不启动）") }
                    }
                    if (diagnostic.isNotBlank()) Text(diagnostic, fontSize = 12.sp)
                    Spacer(Modifier.height(16.dp))

                    Text("5. 悬浮球", fontWeight = FontWeight.Bold)
                    Text("启动悬浮球时会加载本地模型（首次加载要等一会儿）；关闭悬浮球时会卸载模型、释放内存。", fontSize = 13.sp)
                    Row {
                        Button(
                            onClick = {
                                if (!Settings.canDrawOverlays(this@MainActivity)) {
                                    actionMessage = "请先开悬浮窗权限"
                                    toastShort(actionMessage)
                                    startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                                    return@Button
                                }
                                val selected = LocalConfig.modelForId(selectedModelId)
                                if (!LocalServer.isModelDownloaded(this@MainActivity, selected)) {
                                    actionMessage = "请先下载模型：当前选用的是 ${selected.displayName}，它还没下载好"
                                    toastLong(actionMessage)
                                    return@Button
                                }
                                actionMessage = "已发出启动悬浮球指令，正在加载 ${selected.displayName}…"
                                toastShort(actionMessage)
                                val intent = Intent(this@MainActivity, FloatingService::class.java).apply { action = "START" }
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
                            },
                            enabled = busyButton == null,
                            modifier = Modifier.padding(end = 8.dp)
                        ) { Text("启动悬浮球") }
                        OutlinedButton(
                            onClick = {
                                actionMessage = "已发出停止指令，悬浮球关闭时会卸载模型、释放内存"
                                toastShort(actionMessage)
                                val intent = Intent(this@MainActivity, FloatingService::class.java).apply { action = "STOP" }
                                startService(intent)
                                tick += 1
                            },
                            enabled = busyButton == null
                        ) { Text("停止（同时卸载模型）") }
                    }
                    Spacer(Modifier.height(16.dp))
                    Text("使用方法：先在 X 里复制帖子，再点悬浮球，就按上次选的风格生成一次；想换风格，点另一个风格就切换一次；同风格想换一句，点重新生成。点复制后去 X 粘贴，不自动发送。没复制就点球，面板会是空的；自动读不到时，关面板、重新复制、再点球。不需要无障碍，不截图，不 OCR。面板里生成时会显示“本地 / 离线”，表示这次是手机本地模型生成的，不是服务器。", fontSize = 13.sp)
                }
            }
        }
    }

    private fun startDownload(
        model: LocalModel,
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
            val r = ModelDownloader.download(this@MainActivity, model) { downloaded, total ->
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
