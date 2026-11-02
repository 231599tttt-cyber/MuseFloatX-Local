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
 * - 聊天模板（用户纠正）：不传自定义模板，llama-server 直接用模型自带模板，
 *   Qwen3.5-2B 官方默认就是非思考模式。下面的 ThinkFilter 只是兜底：
 *   万一流式输出里混进 <think> 段，就地丢掉，不显示、不进最终回复。
 * - 系统提示词沿用 V38 短句，只加一句“只输出回复正文，不要思考过程”，
 *   作为模板之外的第二层保险，不是主办法。
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

    fun maxTokensForStyle(style: String): Int {
        return if (style == "简短") 80 else 180
    }

    fun styleInstruction(style: String): String {
        return when (style) {
            "幽默" -> "幽默地"
            "简短" -> "简短地"
            else -> "自然地"
        }
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

        /** 非流式兜底：整段去掉 <think>...</think>。 */
        companion object {
            fun stripAll(text: String): String {
                var t = text
                while (true) {
                    val s = t.indexOf("<think")
                    if (s < 0) break
                    val e = t.indexOf("</think>", s)
                    t = if (e < 0) t.substring(0, s) else t.substring(0, s) + t.substring(e + "</think>".length)
                }
                return t.trim()
            }
        }
    }

    private fun buildBody(style: String, postText: String, stream: Boolean): JSONObject {
        // V38 短句 + 只输出正文（模板才是主办法，这句是第二层保险）。
        val system = "你是一个回复助手。根据这个内容，" + styleInstruction(style) + "回复。只输出回复正文，不要思考过程。"
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
                    val text = sb.toString().trim()
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
                        return@withContext Result.failure(Exception("本地模型返回 HTTP ${resp.code}：${raw.take(500)}"))
                    }
                    val content = JSONObject(raw)
                        .getJSONArray("choices")
                        .getJSONObject(0)
                        .getJSONObject("message")
                        .optString("content", "")
                    val text = ThinkFilter.stripAll(content)
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

    /** 主界面“测试本地生成”用：模型没下好、服务起不来都会直接报原因。 */
    suspend fun testLocal(context: Context, contextTokens: Int): Result<String> {
        return generate(context, contextTokens, "自然", "你好")
            .map { if (it.isBlank()) "本地连接成功（返回为空）" else it }
    }
}
