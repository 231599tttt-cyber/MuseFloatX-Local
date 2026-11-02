package com.example.musefloatx

/**
 * 本地模型配置（Qwen3.5-2B，本地版唯一模型）。
 *
 * 重要纠正（用户 2026-11-02 明确指出，不要再按旧说法改）：
 * - Qwen3.5-2B 本身就是后训练对话版本，不要误认为必须带 Instruct 后缀，
 *   也不要下载 Qwen3.5-2B-Base。正确来源就是 bartowski/Qwen_Qwen3.5-2B-GGUF，
 *   原模型 Qwen/Qwen3.5-2B。
 * - 文件名注意：Hugging Face 文件列表里显示为 Qwen3.5-2B-Q4_K_M.gguf，
 *   但 resolve 下载的实际文件名带 Qwen_ 前缀（Qwen_Qwen3.5-2B-Q4_K_M.gguf），
 *   直接用不带前缀的名字下载会 404（已实测）。代码里下载用带前缀的 URL，
 *   落盘保存时用不带前缀的干净名字，方便以后在 App 里找。
 * - 官方说明：2B 默认就是非思考模式。主办法是让 llama-server 使用模型
 *   自带的聊天模板（不要自己覆盖一个会触发思考的模板），不要靠事后过滤。
 *   事后过滤 <think> 只是兜底，见 ApiClient.ThinkFilter。
 * - 上下文先按 1024 tokens 做第一版测试，不要写死 512。
 *   这里的 1024 只是默认值，用户可以在主界面改（SettingsStore.contextTokens），
 *   LocalServer 启动时读那个值，改完下次加载生效。
 */
object LocalConfig {
    /** 模型仓库（Hugging Face）。 */
    const val MODEL_REPO = "bartowski/Qwen_Qwen3.5-2B-GGUF"
    /** 原模型。 */
    const val MODEL_ORIGINAL = "Qwen/Qwen3.5-2B"
    /**
     * 下载 URL（带 Qwen_ 前缀的实际文件名，否则 404）。
     * 2026-11-02 真机反馈：国内直连 huggingface.co 会 30 秒连接超时
     * （failed to connect to huggingface.co port 443 after 30000ms），
     * 不是文件不存在、也不是手机问题。所以默认改走国内镜像 hf-mirror.com，
     * 它就是 huggingface.co 的镜像，同一文件、SHA 校验不变；
     * 原官方地址：https://huggingface.co/bartowski/Qwen_Qwen3.5-2B-GGUF/resolve/main/Qwen_Qwen3.5-2B-Q4_K_M.gguf
     * 下一版编出来后，不用翻墙也能直接下载；当前手机上的旧版仍是直连地址。
     */
    const val MODEL_DOWNLOAD_URL =
        "https://hf-mirror.com/bartowski/Qwen_Qwen3.5-2B-GGUF/resolve/main/Qwen_Qwen3.5-2B-Q4_K_M.gguf"
    /** 落盘文件名。 */
    const val MODEL_FILE_NAME = "Qwen3.5-2B-Q4_K_M.gguf"
    /** 量化。 */
    const val MODEL_QUANT = "Q4_K_M"
    /** 约 1.40 GB（十进制），即 curl 实测的 1,396,198,496 字节。 */
    const val MODEL_EXPECTED_BYTES = 1396198496L
    /**
     * Hugging Face 文件页上公布的 SHA256（Qwen3.5-2B-Q4_K_M.gguf，1.4 GB）。
     * 下载完成后校验用；如果 Hugging Face 重新上传导致 SHA 变化，
     * 请同步更新这里，否则下载会一直报 SHA 不一致。
     */
    const val MODEL_SHA256 = "57a1085840f497d764a7fc5d346922dbde961efb54cc792ea81d694fd846a1d8"

    /** 本地 llama-server 监听地址（只绑回环，不暴露到局域网）。 */
    const val LOCAL_BASE_URL = "http://127.0.0.1:8080"
    const val LOCAL_PORT = 8080
    /** llama-server 里给这个模型起的别名，ApiClient 请求时 model 就填它。 */
    const val LOCAL_MODEL_NAME = "Qwen3.5-2B"

    /** 上下文默认值：先按 1024 测试，不是写死的 512。 */
    const val DEFAULT_CONTEXT_TOKENS = 1024
    /** 线程数：手机上 4 个比较稳，6GB 机器也不要再往上加。 */
    const val THREADS = 4
    /** 10 分钟无生成自动卸载（毫秒），见 LocalServer。 */
    const val IDLE_UNLOAD_MS = 10L * 60L * 1000L
    /** 首次加载等待上限：mmap 加载 1.4GB 模型在旗舰机上约十几秒，6GB 机器放宽到 180 秒。 */
    const val SERVER_START_TIMEOUT_MS = 180_000L
}
