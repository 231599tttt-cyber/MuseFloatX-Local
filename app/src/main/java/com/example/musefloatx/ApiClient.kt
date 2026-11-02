package com.example.musefloatx

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 本地版 ApiClient：不再调任何远程服务，只调本机 llama-server。
 *
 * - 地址固定 http://127.0.0.1:8080（LocalConfig.LOCAL_BASE_URL），
 *   模型名是当前选用模型的 alias（llama-server 启动时 --alias 起的名，
 *   2B 或 4B，不能写死），没有 API 密钥——只绑回环，外面连不进来，
 *   请求里仍带一个本地占位 Bearer，
 *   只是为了让 OpenAI 兼容格式完整，服务器没设密钥时会被忽略。
 * - 生成前先调 LocalServer.ensureStarted()：悬浮球启动时加载、
 *   生成结束 10 分钟闲置自动卸载，这里的 ensureStarted 只是确认服务在跑，
 *   不会把模型常驻。
 * - 推理开关（2026-11-02 改为用户可选，默认 off）：llama-server 启动时
 *   传 --reasoning（LocalServer.kt，已在当前 llama.cpp 源码里核实支持），
 *   值来自用户在主界面选的思考模式（off/auto/on），
 *   这是服务端一级的思考办法；不优先用 --chat-template-kwargs。
 *   下面的 ThinkFilter 只是兜底：万一输出里混进 <think> 段，
 *   就地丢掉，不显示、不进最终回复。空回复时不要只猜是思考导致，
 *   看 generate() 记下的 lastGenerationDiagnostics（finish_reason、
 *   reasoning_content、tokens、过滤前/后长度）。
 * - 系统提示词（2026-11-02 用户批准，三种风格各写一个前置，不再是
 *   V38 那句只差一个风格词的短句；推理仍关，保持快）：
 *   自然 / 幽默 / 简短各有完整前置，见 styleSystemPrompt()。
 *   服务端 --reasoning off 是主办法，提示词里的“不要思考过程”
 *   只是第二层保险，不是主办法。
 * - 单飞行仍由 FloatingService 保证：这里只负责一次 HTTP 请求的取消
 *   （cancelActive），换风格时 FloatingService 先取消再提交新风格。
 *
 * 注意：旧版签名 generateStream(baseUrl, apiKey, model, ...) 已删掉，
 * FloatingService 已同步改成 generateStream(context, contextTokens, style, post, onDelta)。
 * 不要再往这里加回远程地址/密钥参数——本地版没有远程服务。
 */
object ApiClient {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    @Volatile private var activeCall: Call? = null
    fun cancelActive() {
        try { activeCall?.cancel() } catch (_: Exception) { }
    }

    /**
     * 用户手动取消标记（2026-11-02 取消按钮）：
     * 点取消不只是断 OkHttp——断开后 doGenerate 的 catch 本来会把
     * "Canceled" 当成普通失败、generate() 还可能再重试一次，那样
     * 取消就白点了。所以取消时立 flag，doGenerate/generate() 见到
     * flag 就直接返回“已取消生成”、不再重试；下一次新生成入口
     * （generate()/generateStream() 开头）会把 flag 清掉，不影响下次。
     * 取消只取消这次生成，不改思考模式、不删模型、不停服务。
     */
    @Volatile private var userCancelled = false
    fun cancelGeneration() {
        userCancelled = true
        cancelActive()
    }

    /**
     * 最近一次非流式生成的诊断（主界面“测试本地生成”显示用）。
     * 目的：空回复时不再只报“空回复”让用户猜原因，而是直接给出
     * finish_reason、content 是否为空、reasoning_content 是否存在、
     * 过滤前/后长度、实际 tokens 消耗。ThinkFilter 只是兜底，
     * 不是诊断结论。
     */
    @Volatile var lastGenerationDiagnostics: String = ""
        private set

    /**
     * 从 llama-server 的 OpenAI 兼容返回里抽诊断。
     * 返回 Pair(过滤后的正文, 诊断文本)。reasoning_content 在不同
     * llama.cpp 版本里可能放在 message 里，也可能放在 choice 里，
     * 两处都查；usage 没返回时如实写“API 未返回 usage”，不编数字。
     */
    private fun parseGeneration(raw: String): Pair<String, String> {
        return try {
            val root = JSONObject(raw)
            val choices = root.optJSONArray("choices")
            if (choices == null || choices.length() == 0) {
                return Pair(
                    "",
                    "生成诊断：返回里没有 choices（finish_reason=无；content 为空=是；" +
                        "reasoning_content 存在=否；过滤前长度=0 字；过滤后长度=0 字；" +
                        "tokens：API 未返回 usage；原始返回前 300 字：" + raw.take(300) + ")"
                )
            }
            val choice = choices.optJSONObject(0)
            val finishReason = choice?.optString("finish_reason", "")?.ifBlank { "(无/未返回)" } ?: "(无/未返回)"
            val message = choice?.optJSONObject("message")
            val content = message?.optString("content", "") ?: ""
            var reasoning = message?.optString("reasoning_content", "") ?: ""
            if (reasoning.isBlank()) reasoning = choice?.optString("reasoning_content", "") ?: ""
            if (reasoning.isBlank() && message != null && message.has("reasoning")) {
                reasoning = message.optString("reasoning", "")
            }
            val usage = root.optJSONObject("usage")
            val usageText = if (usage == null) {
                "API 未返回 usage"
            } else {
                "prompt_tokens=" + usage.optInt("prompt_tokens", -1).let { if (it < 0) "(未返回)" else it.toString() } +
                    "，completion_tokens=" + usage.optInt("completion_tokens", -1).let { if (it < 0) "(未返回)" else it.toString() } +
                    "，total_tokens=" + usage.optInt("total_tokens", -1).let { if (it < 0) "(未返回)" else it.toString() }
            }
            val stripped = ThinkFilter.stripAll(content)
            val preview = content.take(200).replace("\n", " ")
            // 思考内容绝不能被当成正文：reasoning 只进诊断，不进 Pair 的正文。
            // 正文为空、但思考非空时，要在诊断里明说——这就是思考耗尽
            // token 预算的典型信号，不要只报“空回复”让用户猜。
            val reasoningNote = if (stripped.isBlank() && reasoning.isNotBlank()) {
                "；注意：正文为空、但思考内容非空，可能是思考耗尽了 token 预算，" +
                    "思考开/自动时 max_tokens 已抬高，仍出现此情况请换回思考关闭再试"
            } else ""
            val diag = "生成诊断：finish_reason=" + finishReason +
                "；content 是否为空=" + if (content.isBlank()) "是" else "否" +
                "；reasoning_content 存在=" + if (reasoning.isBlank()) "否" else "是" +
                "（长度=" + reasoning.length + " 字）" +
                "；content 过滤前长度=" + content.length + " 字" +
                "；过滤 <think> 后长度=" + stripped.length + " 字" +
                "；tokens：" + usageText + reasoningNote +
                "；原始 content 前 200 字：" + if (preview.isBlank()) "(空)" else preview
            Pair(stripped, diag)
        } catch (e: Exception) {
            Pair(
                "",
                "生成诊断：解析返回 JSON 失败（" + (e.message ?: "无错误信息") + "）；原始返回前 300 字：" + raw.take(300)
            )
        }
    }

    /** 当前思考模式（SettingsStore.reasoningMode，默认 "off"）。 */
    fun reasoningMode(context: Context): String {
        return try {
            // 天生非思考模型强制 off（与 LocalServer.reasoningMode 同规则，
            // 否则 max_tokens 会按思考档抬到 600/300，白白变慢）。
            if (SettingsStore(context).selectedModel.isNonThinking) "off"
            else SettingsStore(context).reasoningMode
        } catch (_: Exception) {
            "off"
        }
    }

    /**
     * 各风格的 max_tokens（2026-11-02 旧空回复教训的修复）：
     * - 思考关（off，默认）：自然/幽默 180、简短 80，和上一版一样，快。
     * - 思考自动/开启（auto/on）：自然/幽默 600、简短 300。
     *   原因：思考开着时，思考 token 和正文 token 共用同一个预算，
     *   180 很容易被思考段先耗完、剩下正文为空——这就是 2026-11-02
     *   上午“本地模型返回了空回复”那次的疑似机制。抬高上限只是
     *   给正文留出空间，不是保证质量一定更好，速度也会变慢，
     *   界面文案必须如实写这一点。
     */
    fun maxTokensForStyle(style: String, reasoningMode: String = "off"): Int {
        return if (reasoningMode == "off") {
            if (style == "简短") 80 else 180
        } else {
            if (style == "简短") 300 else 600
        }
    }

    /**
     * 三种风格各自的系统提示词前置：2026-11-02 15:51 用户明确要求改短、
     * 像昨晚那样一句话就行——每条只留一句话（句内用逗号分隔、句末一个句号），
     * 但防冒充/防编造硬约束必须保留在句内，不得为缩短而删掉。
     * 思考模式默认关、可选（LocalServer --reasoning 跟随用户选择），温度 0.7、
     * max_tokens 思考关时自然/幽默 180、简短 80，思考开/自动时 600/300，
     * 上下文 1024 均不变。
     */
    fun styleSystemPrompt(style: String): String {
        // 用户 2026-11-02 17:10 决定：纯测试版，中文前置原文，不带 X，
        // 不加语言点名、不加防冒充/防编造后缀；自然/幽默按用户原文为
        // “一两句说完”，不要擅自改回“一句话”。
        // 用户 2026-11-02 17:20 反馈：模型好像没反应过来自己是在回复别人的
        // 帖子，答得像问答而不是简短评论，所以三条前面各加一句对象说明
        // “你正在回复别人的帖子，”，其余措辞一字不改；这样测试结果才能
        // 归因于前置本身，若仍回长段、冒充作者，下一版再把保障句加回来。
        return when (style) {
            "幽默" -> "你正在回复别人的帖子，像一个幽默风趣、有点嘴欠的网友，善于接梗，机灵自然，不刻意搞笑，一两句说完。"
            "简短" -> "你正在回复别人的帖子，像真人刷推时随手回一句，直接自然，尽量简洁，不说废话。"
            else -> "你正在回复别人的帖子，像普通网友一样接话，口语化，有自己的态度，轻松自然，一两句说完。"
        }
    }

    // 兼容旧名：内部不再用“自然地/幽默地/简短地”单字拼提示词。
    fun styleInstruction(style: String): String = styleSystemPrompt(style)

    /**
     * 去掉生成结果开头的字面 "null" 前缀（2026-11-02 真机实测：
     * 回复以 "null恭喜 X 获得 10 万粉丝！" 开头）。
     * 只去开头一个前缀，且 "null" 后不能紧跟英文字母/数字
     * （避免误伤 nullify 之类正常单词）；句中出现的 null 不动。
     * 开头标点/空格一并清掉。流式路径的最终文本和非流式路径
     * 都走这里，空判断之前就先清掉，免得 "null" 被当成正文显示。
     */
    fun stripLeadingNull(text: String): String {
        var t = text.trim()
        if (t.length > 4 && t.substring(0, 4).equals("null", ignoreCase = true)) {
            val next = t[4]
            if (!(next in 'A'..'Z' || next in 'a'..'z' || next in '0'..'9')) {
                t = t.substring(4).trimStart(' ', '，', ',', '、', ':', '：', '·', '.', '。')
            }
        }
        return t
    }

    /** 流式 <think> 兜底过滤器：按块处理，跨块的 <think>...</think> 也能丢掉。 */
    class ThinkFilter {
        private var inThink = false
        private var buf = StringBuilder()
        /** 返回本块里应该显示的正文部分（可能为空）。 */
        fun feed(piece: String): String {
            buf.append(piece)
            var text = buf.toString()
            val out = StringBuilder()
            while (text.isNotEmpty()) {
                if (inThink) {
                    val end = text.indexOf("</think>")
                    if (end < 0) {
                        // 整块都在思考里，丢掉，等下一块看有没有结束标签。
                        text = ""
                        break
                    }
                    inThink = false
                    text = text.substring(end + "</think>".length)
                } else {
                    val start = text.indexOf("<think")
                    if (start < 0) {
                        // 修复（2026-11-02）：块边界把 "<think" 切开时
                        // （例如本块末尾只有 "<th"），旧代码会把 "<th"
                        // 当正文发出，下一块的 "ink>..." 也当正文发出，
                        // 思考标签就漏网了。改为：末尾若是 "<think" 的
                        // 前缀片段，先留在 buf 里，等下一块补全再判断。
                        val lastLt = text.lastIndexOf("<")
                        val tail = if (lastLt >= 0) text.substring(lastLt) else ""
                        if (lastLt >= 0 && "<think".startsWith(tail) && tail.length < 6) {
                            out.append(text.substring(0, lastLt))
                            text = tail
                            break
                        }
                        out.append(text)
                        text = ""
                    } else {
                        out.append(text.substring(0, start))
                        // 标签可能被截断在块边界：先留着，等下一块再判断。
                        val close = text.indexOf(">", start)
                        if (close < 0) {
                            // 只有 "<thi" 这种半个标签，也先丢掉，等下一块补全。
                            // 为避免把正文误丢，这里只保留标签开头部分在 buf 里，
                            // 下一块来了再一起判断。
                            text = text.substring(start)
                            break
                        }
                        inThink = true
                        text = text.substring(close + 1)
                    }
                }
            }
            buf = StringBuilder(text)
            return out.toString()
        }

        /** 生成结束时：把残留的半个标签/未闭合思考段丢掉，只留正文。 */
        fun flush(): String {
            val text = buf.toString()
            buf = StringBuilder()
            if (inThink) return ""
            // 残留的 "<think" 开头（没等到结束）也丢掉。
            val start = text.indexOf("<think")
            return if (start >= 0) text.substring(0, start) else text
        }

        /** 非流式兜底：整段去掉 <think>...</think>，再去掉开头 "null" 前缀。 */
        companion object {
            fun stripAll(text: String): String {
                var t = text
                while (true) {
                    val s = t.indexOf("<think")
                    if (s < 0) break
                    val e = t.indexOf("</think>", s)
                    t = if (e < 0) t.substring(0, s) else t.substring(0, s) + t.substring(e + "</think>".length)
                }
                return ApiClient.stripLeadingNull(t.trim())
            }
        }
    }

    private fun buildBody(style: String, postText: String, stream: Boolean, modelAlias: String, reasoningMode: String = "off", maxTokensOverride: Int? = null, systemOverride: String? = null): JSONObject {
        // 三种风格各用完整前置（styleSystemPrompt），不再拼 V38 短句。
        // 思考模式默认关、可选（服务端 --reasoning 跟随用户选择），温度 0.7 不变。
        // max_tokens 跟随思考模式：思考开/自动时抬高，防思考耗尽预算出空回复。
        // model 必须是当前选用模型的 alias（2B 或 4B），不能写死 2B：
        // llama-server 启动时 --alias 起的名字和这里必须一致，否则
        // 切换到 4B 后请求还会去找 2B 的名字。
        val system = systemOverride ?: styleSystemPrompt(style)
        val user = "X 帖子：\n" + postText.take(2000) + "\n回一条，别自动发送。"
        return JSONObject().apply {
            put("model", modelAlias)
            put("stream", stream)
            put("temperature", 0.7)
            put("max_tokens", maxTokensOverride ?: maxTokensForStyle(style, reasoningMode))
            put("messages", JSONArray().apply {
                put(JSONObject().apply { put("role", "system"); put("content", system) })
                put(JSONObject().apply { put("role", "user"); put("content", user) })
            })
        }
    }

    /**
     * 思考/自动模式“根本没反应”的根因（2026-11-02 真机实测后定位，
     * 不是猜测）：思考开/自动时，llama-server 先流式返回大量
     * reasoning_content（或 <think> 段），正文 content 要等思考结束
     * 才开始。旧代码把 reasoning_content 只记一个布尔标记、绝不显示，
     * ThinkFilter 又把 <think> 段全部丢掉，于是面板在思考阶段
     * 一直停在“生成中…”、一个字都不出；手机上 2B 生成思考又慢，
     * 很容易超过 OkHttp 120 秒读超时才报错，用户看到的就是
     * “半天没反应”。修法不是发明一个 llama.cpp 没有的思考 token
     * 上限参数（--reasoning 只有 on/off/auto 三档，没有 token 上限），
     * 而是：(a) 一收到思考就立刻回调 onReasoning，让面板显示
     * “正在思考…”；(b) 思考模式下 60 秒内没出正文就主动失败，
     * 给出明确诊断和“换回思考关闭”的建议，而不是静默挂着；
     * (c) 思考关时行为、超时、token 上限全部原样不动。
     */
    suspend fun generateStream(
        context: Context,
        contextTokens: Int,
        style: String,
        postText: String,
        onDelta: (String) -> Unit,
        onReasoning: () -> Unit = {}
    ): Result<String> = withContext(Dispatchers.IO) {
        // 新的一通生成开始：清掉上一次取消留下的 flag，否则上次取消
        // 会误伤这次。取消只在当次生成内有效。
        userCancelled = false
        // 模型文件先显式校验：文件不对时直接报模型问题，绝不能放行
        // 一个错名/不完整文件去生成、最后变成误导性的空回复。
        val selectedModel = LocalServer.selectedModel(context)
        LocalServer.validateModelFile(context, selectedModel)?.let { modelErr ->
            return@withContext Result.failure(Exception(modelErr))
        }
        val startErr = LocalServer.ensureStartedForGenerate(context, contextTokens)
        if (startErr != null) return@withContext Result.failure(Exception(startErr))
        if (userCancelled) return@withContext Result.failure(Exception("已取消生成"))
        val mode = reasoningMode(context)
        // 思考模式下读超时收紧到 60 秒：思考流本身会持续来行，
        // 但 60 秒没收到任何行也该明确失败，而不是等 120 秒。
        // 思考关时用原客户端（120 秒），行为原样不动。
        val callClient = if (mode == "off") client
            else client.newBuilder().readTimeout(60, TimeUnit.SECONDS).build()
        val streamStartMs = System.currentTimeMillis()
        try {
            val req = Request.Builder()
                .url("${LocalConfig.LOCAL_BASE_URL}/v1/chat/completions")
                .addHeader("Authorization", "Bearer local")
                .addHeader("Content-Type", "application/json")
                .addHeader("Accept", "text/event-stream")
                .post(buildBody(style, postText, true, LocalServer.selectedModel(context).alias, mode).toString().toRequestBody("application/json".toMediaType()))
                .build()
            val call = callClient.newCall(req)
            activeCall = call
            try {
                call.execute().use { resp ->
                    if (!resp.isSuccessful) {
                        val raw = resp.body?.string() ?: ""
                        return@withContext Result.failure(Exception("本地模型返回 HTTP ${resp.code}：${raw.take(500)}"))
                    }
                    val source = resp.body?.source()
                        ?: return@withContext Result.failure(Exception("本地模型返回为空"))
                    val filter = ThinkFilter()
                    val sb = StringBuilder()
                    // 流式 null 防闪烁状态（2026-11-02 实测：先看到 null，
                    // 生成完最终文本覆盖时又跳一次）：
                    // sb 累积的是未去 null 的可见文本，emitted 是已经发给
                    // 面板、且已去 null 的文本；每块只发 cleaned 相对 emitted
                    // 多出来的部分，所以面板上永远不会出现 null 前缀，
                    // 最终覆盖时也是同一段文本，不会再跳一次。
                    // 句中出现的 null 不动，只处理开头。
                    val emitted = StringBuilder()
                    var reasoningSeenInStream = false
                    var reasoningNotified = false
                    fun notifyReasoning() {
                        if (!reasoningNotified) {
                            reasoningNotified = true
                            try { onReasoning() } catch (_: Exception) { }
                        }
                    }
                    while (true) {
                        val line = try { source.readUtf8Line() } catch (_: Exception) { break } ?: break
                        if (!line.startsWith("data:")) continue
                        val data = line.removePrefix("data:").trim()
                        if (data.isBlank()) continue
                        if (data == "[DONE]") break
                        try {
                            val json = JSONObject(data)
                            val err = json.optJSONObject("error")
                            if (err != null) {
                                return@withContext Result.failure(Exception(err.optString("message", "本地模型返回错误")))
                            }
                            val delta = json.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("delta")
                            // 思考模式开/自动时，流里可能有 reasoning_content
                            // 增量：它绝不能显示，也绝不能被当成正文，只记
                            // 一个“见过思考”的标记，供最后空回复时诊断用。
                            val reasoningPiece = delta?.optString("reasoning_content", "") ?: ""
                            if (reasoningPiece.isNotBlank()) {
                                reasoningSeenInStream = true
                                notifyReasoning()
                            }
                            val piece = delta?.optString("content", "") ?: ""
                            if (piece.contains("<think")) notifyReasoning()
                            if (piece.isNotEmpty()) {
                                val visible = filter.feed(piece)
                                if (visible.isNotEmpty()) {
                                    sb.append(visible)
                                    val raw = sb.toString()
                                    // 分块可能把 "null" 切开（"nu"+"ll…"）：
                                    // 累积文本还是 "null" 的前缀时先一字不发，
                                    // 等下一块来了再判断，避免先闪 "nu" 再跳变。
                                    val isNullPrefix = raw.length in 1..4 &&
                                        "null".startsWith(raw.lowercase())
                                    if (!isNullPrefix) {
                                        val cleaned = stripLeadingNull(raw)
                                        if (cleaned.length > emitted.length) {
                                            onDelta(cleaned.substring(emitted.length))
                                            emitted.clear()
                                            emitted.append(cleaned)
                                        }
                                    }
                                }
                            }
                        } catch (_: Exception) {
                            // 跳过个别畸形行，不中断整段流
                        }
                        // 思考模式 60 秒无正文保护：思考流在持续来行时，
                        // 读超时不会触发，但用户面板不能无限停在“生成中…”。
                        // 60 秒内只收到思考、一个正文字都没出，就主动失败，
                        // 并明确建议换回思考关闭。思考关时此分支不启用
                        // （mode == "off" 时 reasoningSeen 正常应为 false，
                        // 且 off 本来就 1-2 秒出正文，不会走到这里）。
                        if (mode != "off" && reasoningSeenInStream && emitted.isEmpty() &&
                            System.currentTimeMillis() - streamStartMs > 60_000L
                        ) {
                            try { call.cancel() } catch (_: Exception) { }
                            return@withContext Result.failure(
                                Exception(
                                    "思考模式下 60 秒内没有收到正文（已收到思考内容，" +
                                        "思考可能过长或被思考耗尽了 token 预算；" +
                                        "finish_reason=未完成；reasoning_content 存在=是；" +
                                        "建议：换回思考关闭再试，思考关闭仍是 1-2 秒的快模式）"
                                )
                            )
                        }
                    }
                    sb.append(filter.flush())
                    // 最终文本同样先去开头 null 再做空判断；如果流里
                    // 已发出的 emitted 和最终文本一致，面板覆盖时
                    // 就是同一段文本，不会再跳一次。
                    val text = stripLeadingNull(sb.toString().trim())
                    if (text.length > emitted.length) {
                        onDelta(text.substring(emitted.length))
                    }
                    if (text.isBlank()) {
                        if (reasoningSeenInStream) {
                            Result.failure(
                                Exception(
                                    "本地模型返回了空回复（流里只收到了思考内容，没有正文；" +
                                        "思考开/自动时可能思考耗尽了 token 预算，" +
                                        "请换回思考关闭再试，或检查思考模式与 max_tokens）"
                                )
                            )
                        } else {
                            Result.failure(Exception("本地模型返回了空回复"))
                        }
                    } else Result.success(text)
                }
            } finally {
                if (activeCall == call) activeCall = null
                LocalServer.markUsed()
            }
        } catch (e: Exception) {
            // 用户取消优先：OkHttp 断开会抛 "Canceled"，不要把它当成
            // 普通失败、更不要走到思考超时那条“建议换回关闭”的分支。
            if (userCancelled) {
                return@withContext Result.failure(Exception("已取消生成"))
            }
            val msg = e.message ?: "本地生成失败"
            val isTimeout = e is java.net.SocketTimeoutException ||
                msg.contains("timeout", ignoreCase = true) ||
                msg.contains("超时")
            if (mode != "off" && isTimeout) {
                Result.failure(
                    Exception(
                        "思考模式下 60 秒内没有收到数据（思考可能过长；" +
                            "原始错误：$msg）。建议：换回思考关闭再试，" +
                            "思考关闭仍是 1-2 秒的快模式；思考开/自动不保证更快或更好。"
                    )
                )
            } else {
                Result.failure(Exception(msg))
            }
        }
    }

    /**
     * 非流式生成（主界面“测试本地生成”用），带一次有用的重试：
     * - 第 0 步先显式校验模型文件：不存在、大小不对（例如之前串号
     *   留下的 2873MB 错名文件）直接报模型问题，绝不放行去生成、
     *   最后变成误导性的“空回复”。
     * - 思考模式下正文为空且思考非空、finish_reason=length：自动用
     *   思考关闭重生成一次（临时 override，不改用户持久化的思考
     *   模式选择；下次生成仍按用户选择重启），这次能出正文就返回
     *   正文并在诊断里写明已重试；再空才报失败。
     * - 思考关闭下正文为空且 finish_reason=length：说明模型在
     *   180 token 内只输出了思考/标签就被截断，重试一次把上限
     *   放到 400（自然/幽默）/200（简短），仍在 1024 上下文内，
     *   给模型留出走出思考段、说出正文的空间。
     * - 重试有界：思考模式最多重试一次（用思考关闭）；思考关闭
     *   最多重试两次（先放宽上限、再用短提示词），绝不循环重试。
     *   面板流式路径不走这里，仍遵守单飞行、不自动第二通的规则。
     */
    suspend fun generate(
        context: Context,
        contextTokens: Int,
        style: String,
        postText: String
    ): Result<String> {
        // 新的一通生成开始：清掉上一次取消留下的 flag。
        userCancelled = false
        val selectedModel = LocalServer.selectedModel(context)
        LocalServer.validateModelFile(context, selectedModel)?.let { modelErr ->
            lastGenerationDiagnostics = "生成诊断：未调用模型（$modelErr）"
            return Result.failure(Exception(modelErr))
        }
        val mode = reasoningMode(context)
        val first = doGenerate(context, contextTokens, style, postText, null, null)
        if (first.isSuccess) return first
        // 用户已取消：直接返回，不再走下面的放宽上限/短提示词重试，
        // 否则取消后还会再发请求，等于没取消。
        if (userCancelled) return Result.failure(Exception("已取消生成"))
        val diag = lastGenerationDiagnostics
        val isLength = diag.contains("finish_reason=length")
        val reasoningPresent = diag.contains("reasoning_content 存在=是")
        val isBlankFailure = first.exceptionOrNull()?.message?.contains("空回复") == true
        if (!isBlankFailure || !isLength) return first
        // 思考模式：用思考关闭重生成一次（临时 override，不改用户设置）。
        if (mode != "off" && reasoningPresent) {
            val retry = doGenerate(context, contextTokens, style, postText, "off", null)
            if (retry.isSuccess) {
                lastGenerationDiagnostics = lastGenerationDiagnostics +
                    "（注意：思考模式下首次生成正文为空，已自动用思考关闭重生成一次并成功；用户思考模式选择未改）"
                return retry
            }
            return Result.failure(
                Exception("本地模型返回了空回复（思考模式重试思考关闭后仍为空）\n" + diag + "\n建议：保持思考关闭再试；若思考关闭也空，请检查模型文件大小与诊断")
            )
        }
        // 思考关闭：先把上限放宽重试一次；仍空则用一句短提示词
        // 再试一次——长提示词（含防冒充硬约束）本身可能把 2B 带进
        // 长思考/标签段、180 token 内走不出来，短提示词能验证
        // 是不是提示词把正文挤没了，而不是模型文件坏了。
        if (mode == "off") {
            val bigger = if (style == "简短") 200 else 400
            val retry = doGenerate(context, contextTokens, style, postText, "off", bigger)
            if (retry.isSuccess) {
                lastGenerationDiagnostics = lastGenerationDiagnostics +
                    "（注意：首次生成在 180/80 token 内被截断为空，已自动放宽上限到 $bigger 重生成一次并成功）"
                return retry
            }
            val simplePrompt = "你是一个推特（X）的回复助手。直接用口语回一条，一两句就行，只输出回复正文，不要思考过程，不要输出<think>标签，不要带标签。"
            val retry2 = doGenerate(context, contextTokens, style, postText, "off", bigger, simplePrompt)
            if (retry2.isSuccess) {
                lastGenerationDiagnostics = lastGenerationDiagnostics +
                    "（注意：放宽上限后仍为空，已自动用短提示词重生成一次并成功；说明长提示词可能把 2B 带偏了，短提示词更稳）"
                return retry2
            }
        }
        // 最终失败时附上模型/alias/端口，方便对照是不是请求发错了地方。
        val modelInfo = "当前选用模型：${selectedModel.displayName}，文件：${selectedModel.fileName}，请求 alias：${selectedModel.alias}，服务：${LocalConfig.LOCAL_BASE_URL}"
        return Result.failure(Exception((first.exceptionOrNull()?.message ?: "本地模型返回了空回复") + "\n$modelInfo"))
    }

    /** 非流式生成的一次实际请求（forcedMode 非空时临时用该思考模式启动服务，不改用户设置）。 */
    private suspend fun doGenerate(
        context: Context,
        contextTokens: Int,
        style: String,
        postText: String,
        forcedMode: String?,
        maxTokensOverride: Int?,
        systemOverride: String? = null
    ): Result<String> = withContext(Dispatchers.IO) {
        val startErr = LocalServer.ensureStartedForGenerate(context, contextTokens, forcedMode)
        if (startErr != null) return@withContext Result.failure(Exception(startErr))
        // 取消可能发生在模型加载阶段（那时还没建 OkHttp 调用可断）：
        // 加载完先查 flag，取消了就不再发请求，直接失败。
        if (userCancelled) return@withContext Result.failure(Exception("已取消生成"))
        val nonStreamMode = forcedMode ?: reasoningMode(context)
        // 非流式（主界面测试）同样：思考模式下 60 秒读超时即明确失败，
        // 不再静默等 120 秒；思考关时用原客户端，行为原样不动。
        val nonStreamClient = if (nonStreamMode == "off") client
            else client.newBuilder().readTimeout(60, TimeUnit.SECONDS).build()
        try {
            val req = Request.Builder()
                .url("${LocalConfig.LOCAL_BASE_URL}/v1/chat/completions")
                .addHeader("Authorization", "Bearer local")
                .addHeader("Content-Type", "application/json")
                .post(buildBody(style, postText, false, LocalServer.selectedModel(context).alias, nonStreamMode, maxTokensOverride, systemOverride).toString().toRequestBody("application/json".toMediaType()))
                .build()
            val call = nonStreamClient.newCall(req)
            activeCall = call
            try {
                call.execute().use { resp ->
                    val raw = resp.body?.string() ?: ""
                    if (!resp.isSuccessful) {
                        lastGenerationDiagnostics = "生成诊断：HTTP 失败，finish_reason=无；原始返回前 500 字：" + raw.take(500)
                        return@withContext Result.failure(Exception("本地模型返回 HTTP ${resp.code}：${raw.take(500)}" + "\n" + lastGenerationDiagnostics))
                    }
                    val (text, diag) = parseGeneration(raw)
                    lastGenerationDiagnostics = diag
                    if (text.isBlank()) Result.failure(Exception("本地模型返回了空回复" + "\n" + diag))
                    else Result.success(text)
                }
            } finally {
                if (activeCall == call) activeCall = null
                LocalServer.markUsed()
            }
        } catch (e: Exception) {
            // 用户取消优先：OkHttp 断开会抛 "Canceled"，不要把它当成
            // 普通失败、更不要走到思考超时那条“建议换回关闭”的分支。
            if (userCancelled) {
                return@withContext Result.failure(Exception("已取消生成"))
            }
            val msg = e.message ?: "本地生成失败"
            val isTimeout = e is java.net.SocketTimeoutException ||
                msg.contains("timeout", ignoreCase = true) || msg.contains("超时")
            if (nonStreamMode != "off" && isTimeout) {
                Result.failure(
                    Exception(
                        "思考模式下 60 秒内没有返回正文（思考可能过长；原始错误：$msg）。" +
                            "建议：换回思考关闭再试，思考关闭仍是 1-2 秒的快模式。"
                    )
                )
            } else {
                Result.failure(Exception(msg))
            }
        }
    }


    /**
     * 主界面“测试本地生成”用：模型没下好、服务起不来都会直接报原因。
     * 成功和失败都把生成诊断附在结果后面显示在主界面上，
     * 失败时 generate() 里已经带了诊断，这里不再重复；成功时把
     * lastGenerationDiagnostics 一并附上，方便对照 tokens 和 finish_reason。
     */
    suspend fun testLocal(context: Context, contextTokens: Int): Result<String> {
        return generate(context, contextTokens, "自然", "你好")
            .map { text ->
                val diag = lastGenerationDiagnostics
                if (diag.isBlank()) text else text + "\n\n---- 生成诊断 ----\n" + diag
            }
    }
}
