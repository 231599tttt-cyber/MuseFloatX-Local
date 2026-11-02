package com.example.musefloatx

/**
 * 模型定义：2B 和 4B 各是一个 LocalModel，互不覆盖。
 *
 * 2B（已有，不改）：落盘 Qwen3.5-2B-Q4_K_M.gguf，官方 URL 不变，
 * 下面旧常量 MODEL_* 全部仍是 2B 的值，老代码继续能用。
 *
 * 4B（新增，2026-11-02 从 Hugging Face 官方 API 核实，非猜测）：
 * - 仓库 bartowski/Qwen_Qwen3.5-4B-GGUF，原模型 Qwen/Qwen3.5-4B，
 *   同样是后训练对话版本（不是 Base 版），量化 Q4_K_M。
 * - 文件列表里带 Qwen_ 前缀的实际文件名是
 *   Qwen_Qwen3.5-4B-Q4_K_M.gguf（和 2B 的 Qwen_ 前缀规律一致），
 *   下载用带前缀的 resolve URL，落盘用不带前缀的干净名字
 *   Qwen3.5-4B-Q4_K_M.gguf，和 2B 分开存，互不删除。
 * - 大小：HF 树状 API 返回 Qwen_Qwen3.5-4B-Q4_K_M.gguf 的
 *   size = 3,013,027,808 字节，约 3.01GB（十进制）/ 2.81GiB。
 *   注意：这和用户早前实测说的约 2.6GB 不一致，那个口径可能是
 *   别的量化或别的统计方式；代码以 HF 官方文件大小为准，
 *   UI 里也写 3.01GB，不要写 2.6GB。
 * - SHA：HF 树状 API 返回该文件的 LFS oid
 *   13c16f426047e2de38cd075bdade4a7bcbc8c774384876f677740cda65f8a983，
 *   LFS oid 就是文件内容的 SHA256（2B 的 SHA 也是同一机制公布的）。
 *   下载完成后按这个 SHA 校验，不一致就删文件重下。
 * - 4B 更大更慢：mmap 加载时内存占用比 2B 大一截，12GB 手机没问题，
 *   6GB 手机可能卡，UI 里要写清这个提醒，不要让用户误以为 4B 一定更快。
 */
data class LocalModel(
    /** SharedPreferences 里存的 id："2B" 或 "4B"。 */
    val id: String,
    /** 界面显示名，如 "Qwen3.5-2B Q4_K_M"。 */
    val displayName: String,
    /** Hugging Face 仓库。 */
    val repo: String,
    /** 官方下载 URL（带 Qwen_ 前缀的实际文件名，否则 404）。 */
    val downloadUrl: String,
    /** 落盘文件名（不带 Qwen_ 前缀），2B 和 4B 各自独立。 */
    val fileName: String,
    /** 官方文件大小（字节），用于下载完成/已下载校验。 */
    val expectedBytes: Long,
    /** 官方 SHA256（HF LFS oid），下载完成后校验用。 */
    val sha256: String,
    /** llama-server 启动时的 --alias，也是 ApiClient 请求里的 model 名。 */
    val alias: String,
    /** 界面显示的大小文案，如 "约 1.40GB"。 */
    val approxSizeLabel: String
)

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
     * 用户 2026-11-02 决定：直接用官方地址，不用镜像。
     * 注意：国内直连 huggingface.co 可能 30 秒连接超时，
     * 此时需开能上外网的 VPN 再点下载，已下载的部分可续传。
     */
    const val MODEL_DOWNLOAD_URL =
        "https://huggingface.co/bartowski/Qwen_Qwen3.5-2B-GGUF/resolve/main/Qwen_Qwen3.5-2B-Q4_K_M.gguf"
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

    /** 2B 模型规格（和上面旧常量一一对应，老代码不用改）。 */
    val MODEL_2B = LocalModel(
        id = "2B",
        displayName = "Qwen3.5-2B Q4_K_M",
        repo = MODEL_REPO,
        downloadUrl = MODEL_DOWNLOAD_URL,
        fileName = MODEL_FILE_NAME,
        expectedBytes = MODEL_EXPECTED_BYTES,
        sha256 = MODEL_SHA256,
        alias = LOCAL_MODEL_NAME,
        approxSizeLabel = "约 1.40GB"
    )

    /** 4B 模型规格（2026-11-02 从 HF 官方 API 核实，见文件头注释）。 */
    val MODEL_4B = LocalModel(
        id = "4B",
        displayName = "Qwen3.5-4B Q4_K_M",
        repo = "bartowski/Qwen_Qwen3.5-4B-GGUF",
        downloadUrl = "https://huggingface.co/bartowski/Qwen_Qwen3.5-4B-GGUF/resolve/main/Qwen_Qwen3.5-4B-Q4_K_M.gguf",
        fileName = "Qwen3.5-4B-Q4_K_M.gguf",
        expectedBytes = 3013027808L,
        sha256 = "13c16f426047e2de38cd075bdade4a7bcbc8c774384876f677740cda65f8a983",
        alias = "Qwen3.5-4B",
        approxSizeLabel = "约 3.01GB"
    )

    /** 全部可选模型：界面按这个列表画两张卡片，顺序就是 2B 在前、4B 在后。 */
    val MODELS: List<LocalModel> = listOf(MODEL_2B, MODEL_4B)

    /** 按 id 取模型规格；id 不认识时回落到 2B，绝不崩。 */
    fun modelForId(id: String?): LocalModel {
        return MODELS.firstOrNull { it.id == id } ?: MODEL_2B
    }

    /** 上下文默认值：先按 1024 测试，不是写死的 512。 */
    const val DEFAULT_CONTEXT_TOKENS = 1024
    /** 线程数：手机上 4 个比较稳，6GB 机器也不要再往上加。 */
    const val THREADS = 4
    /** 10 分钟无生成自动卸载（毫秒），见 LocalServer。 */
    const val IDLE_UNLOAD_MS = 10L * 60L * 1000L
    /** 首次加载等待上限：mmap 加载 1.4GB 模型在旗舰机上约十几秒，6GB 机器放宽到 180 秒。 */
    const val SERVER_START_TIMEOUT_MS = 180_000L
}
