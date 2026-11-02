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
 *   模型名固定 Qwen3.5-2B（llama-server 启动时 --alias 起的名），
 *   没有 API 密钥——只绑回环，外面连不进来，请求里仍带一个本地占位 Bearer，
 *   只是为了让 OpenAI 兼容格式完整，服务器没设密钥时会被忽略。
 * - 生成前先调 LocalServer.ensureStarted()：悬浮球启动时加载、
 *   生成结束 10 分钟闲置自动卸载，这里的 ensureStarted 只是确认服务在跑，
 *   不会把模型常驻。
 * - 推理开关（2026-11-02 改）：llama-server 启动时传 --reasoning off
 *   （LocalServer.kt，已在当前 llama.cpp 源码里核实支持），
 *   这是服务端一级的关思考办法；不优先用 --chat-template-kwargs。
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
            val diag = "生成诊断：finish_reason=" + finishReason +
                "；content 是否为空=" + if (content.isBlank()) "是" else "否" +
                "；reasoning_content 存在=" + if (reasoning.isBlank()) "否" else "是" +
                "（长度=" + reasoning.length + " 字）" +
                "；content 过滤前长度=" + content.length + " 字" +
                "；过滤 <think> 后长度=" + stripped.length + " 字" +
                "；tokens：" + usageText +
                "；原始 content 前 200 字：" + if (preview.isBlank()) "(空)" else preview
            Pair(stripped, diag)
        } catch (e: Exception) {
            Pair(
                "",
                "生成诊断：解析返回 JSON 失败（" + (e.message ?: "无错误信息") + "）；原始返回前 300 字：" + raw.take(300)
            )
        }
    }

    fun maxTokensForStyle(style: String): Int {
        return if (style == "简短") 80 else 180
    }

    /**
     * 三种风格各自的完整系统提示词前置（2026-11-02 用户批准原样含义，
     * 不要为了省 token 缩短：每条只有约 70 字，相对 1024 上下文可忽略，
     * 且速度已经很快，用户明确说先不管长短）。
     * 推理仍关（LocalServer --reasoning off），温度 0.7、
     * max_tokens 自然/幽默 180、简短 80、上下文 1024 均不变。
     */
    fun styleSystemPrompt(style: String): String {
        // 三个前置开头统一：先定身份是推特（X）的回复助手，
        // 再接各自风格要求，避免模型把它当成通用聊天助手。
        return when (style) {
            "幽默" -> "你是一个推特（X）的回复助手，你是 X 上一个爱开玩笑的网友，用幽默、梗、反差、自嘲回一条，好笑但不冒犯、不低俗。笑点放后面，不要解释笑点，不要说教、不要长篇，一两句就行，只输出回复正文。不要思考过程，不要带标签。"
            "简短" -> "你是一个推特（X）的回复助手，你是 X 上一个网友，用最短的话回一条，一句话、十几二十个字左右，接梗或者点赞式地回一句。不展开、不建议、不总结、不重复原帖，只输出回复正文。不要思考过程，不要带标签。"
            else -> "你是一个推特（X）的回复助手，你是 X 上一个普通网友，用口语、自然地回一条，像真人聊天。贴合原帖的语气和梗，接住对方的话，不要重复原帖，不要官方腔、不要说教、不要给建议、不要总结，一两句就行，只输出回复正文。不要思考过程，不要带标签。"
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

    private fun buildBody(style: String, postText: String, stream: Boolean): JSONObject {
        // 三种风格各用完整前置（styleSystemPrompt），不再拼 V38 短句。
        // 推理仍关（服务端 --reasoning off），温度 0.7 不变。
        val system = styleSystemPrompt(style)
        val user = "X 帖子：\n" + postText.take(2000) + "\n回一条，别自动发送。"
        return JSONObject().apply {
            put("model", LocalConfig.LOCAL_MODEL_NAME)
            put("stream", stream)
            put("temperature", 0.7)
            put("max_tokens", maxTokensForStyle(style))
            put("messages", JSONArray().apply {
                put(JSONObject().apply { put("role", "system"); put("content", system) })
                put(JSONObject().apply { put("role", "user"); put("content", user) })
            })
        }
    }

    suspend fun generateStream(
        context: Context,
        contextTokens: Int,
        style: String,
        postText: String,
        onDelta: (String) -> Unit
    ): Result<String> = withContext(Dispatchers.IO) {
        val startErr = LocalServer.ensureStartedForGenerate(context, contextTokens)
        if (startErr != null) return@withContext Result.failure(Exception(startErr))
        try {
            val req = Request.Builder()
                .url("${LocalConfig.LOCAL_BASE_URL}/v1/chat/completions")
                .addHeader("Authorization", "Bearer local")
                .addHeader("Content-Type", "application/json")
                .addHeader("Accept", "text/event-stream")
                .post(buildBody(style, postText, true).toString().toRequestBody("application/json".toMediaType()))
                .build()
            val call = client.newCall(req)
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
                            val piece = delta?.optString("content", "") ?: ""
                            if (piece.isNotEmpty()) {
                                val visible = filter.feed(piece)
                                if (visible.isNotEmpty()) {
                                    sb.append(visible)
                                    onDelta(visible)
                                }
                            }
                        } catch (_: Exception) {
                            // 跳过个别畸形行，不中断整段流
                        }
                    }
                    sb.append(filter.flush())
                    // 流式最终文本也要去掉开头 "null" 前缀，再做空判断；
                    // 面板最终会用这段结果覆盖流式中逐字显示的文本。
                    val text = stripLeadingNull(sb.toString().trim())
                    if (text.isBlank()) Result.failure(Exception("本地模型返回了空回复"))
                    else Result.success(text)
                }
            } finally {
                if (activeCall == call) activeCall = null
                LocalServer.markUsed()
            }
        } catch (e: Exception) {
            Result.failure(Exception(e.message ?: "本地生成失败"))
        }
    }

    suspend fun generate(
        context: Context,
        contextTokens: Int,
        style: String,
        postText: String
    ): Result<String> = withContext(Dispatchers.IO) {
        val startErr = LocalServer.ensureStartedForGenerate(context, contextTokens)
        if (startErr != null) return@withContext Result.failure(Exception(startErr))
        try {
            val req = Request.Builder()
                .url("${LocalConfig.LOCAL_BASE_URL}/v1/chat/completions")
                .addHeader("Authorization", "Bearer local")
                .addHeader("Content-Type", "application/json")
                .post(buildBody(style, postText, false).toString().toRequestBody("application/json".toMediaType()))
                .build()
            val call = client.newCall(req)
            activeCall = call
            try {
                call.execute().use { resp ->
                    val raw = resp.body?.string() ?: ""
                    if (!resp.isSuccessful) {
                        lastGenerationDiagnostics = "生成诊断：HTTP 失败，finish_reason=无；原始返回前 500 字：" + raw.take(500)
                        return@withContext Result.failure(Exception("本地模型返回 HTTP ${resp.code}：${raw.take(500)}\n$lastGenerationDiagnostics"))
                    }
                    val (text, diag) = parseGeneration(raw)
                    lastGenerationDiagnostics = diag
                    if (text.isBlank()) Result.failure(Exception("本地模型返回了空回复\n$diag"))
                    else Result.success(text)
                }
            } finally {
                if (activeCall == call) activeCall = null
                LocalServer.markUsed()
            }
        } catch (e: Exception) {
            Result.failure(Exception(e.message ?: "本地生成失败"))
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
