package com.example.musefloatx

/**
 * 模型定义：2B 槽位和 4B 各是一个 LocalModel，互不覆盖。
 *
 * 2B 槽位（2026-11-02 用户纠正后替换，不再是 Qwen3.5-2B）：
 * 换成 Qwen3-4B-Instruct-2507 Q4_K_M，落盘
 * Qwen_Qwen3-4B-Instruct-2507-Q4_K_M.gguf，官方 URL，约 2.50GB。
 * 槽位 id 仍叫 "2B"（老用户偏好里存的就是它，回落逻辑不变），
 * 但显示名、仓库、文件名、大小、SHA、alias 全部是新模型的；
 * 旧 Qwen3.5-2B 文件名不同，不会被新模型误认，已下载状态
 * 会显示未下载，需要重新下载约 2.50GB，旧文件不会被自动删。
 * 下面旧常量 MODEL_* 全部改为新模型的值，老代码继续能用。
 * 4B 卡片（旧 Qwen3.5-4B）原样保留，不动。
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
    val approxSizeLabel: String,
    /** 是否天生非思考模型：true 时强制 --reasoning off，界面禁用思考开关。 */
    val isNonThinking: Boolean = false
)

/**
 * 本地模型配置（2B 槽位，2026-11-02 已替换为 Qwen3-4B-Instruct-2507）。
 *
 * 替换原因（用户纠正）：不要替换 4B 卡片，替换 2B 卡片。
 * 旧 Qwen3.5-2B 质量差、回得说教还编东西，新模型是质量优先、
 * 4B 档的主推：Qwen3-4B-Instruct-2507。
 * - 原模型 Qwen/Qwen3-4B-Instruct-2507，仓库
 *   bartowski/Qwen_Qwen3-4B-Instruct-2507-GGUF，量化 Q4_K_M。
 * - 它天生就是非思考模型（官方 Instruct-2507 只支持非思考模式，
 *   不生成 think 内容），所以强制 --reasoning off，界面对它
 *   禁用思考开关；不要给它开思考，那是画蛇添足。
 * - 文件名注意：HF 文件表里的实际文件名就是带 Qwen_ 前缀的
 *   Qwen_Qwen3-4B-Instruct-2507-Q4_K_M.gguf（之前研究里提到的
 *   “文件表 vs CLI 示例前缀不一致”已核对：以文件表为准，
 *   下载和落盘都用这个全名，不要再 strip 前缀）。
 * - 大小与 SHA（2026-11-02 从 HF 树状 API 核实，非猜测）：
 *   size = 2,497,280,736 字节，约 2.50GB；LFS oid（即 SHA256）=
 *   2fde00ce69dd4899c70d020845e2638353015bba0fdf161b3eb965f2bca4464e。
 * - 上下文先按 1024 tokens 测试，不要写死 512。
 *   这里的 1024 只是默认值，用户可以在主界面改（SettingsStore.contextTokens），
 *   LocalServer 启动时读那个值，改完下次加载生效。
 */
object LocalConfig {
    /** 模型仓库（Hugging Face）。 */
    const val MODEL_REPO = "bartowski/Qwen_Qwen3-4B-Instruct-2507-GGUF"
    /** 原模型。 */
    const val MODEL_ORIGINAL = "Qwen/Qwen3-4B-Instruct-2507"
    /**
     * 下载 URL（文件表里的全名，否则 404）。
     * 注意：国内直连 huggingface.co 可能 30 秒连接超时，
     * 此时需开能上外网的 VPN 再点下载，已下载的部分可续传。
     */
    const val MODEL_DOWNLOAD_URL =
        "https://huggingface.co/bartowski/Qwen_Qwen3-4B-Instruct-2507-GGUF/resolve/main/Qwen_Qwen3-4B-Instruct-2507-Q4_K_M.gguf"
    /** 落盘文件名（与下载文件名一致的全名）。 */
    const val MODEL_FILE_NAME = "Qwen_Qwen3-4B-Instruct-2507-Q4_K_M.gguf"
    /** 量化。 */
    const val MODEL_QUANT = "Q4_K_M"
    /** 约 2.50 GB（十进制），即 HF 树状 API 的 2,497,280,736 字节。 */
    const val MODEL_EXPECTED_BYTES = 2497280736L
    /**
     * Hugging Face 树状 API 公布的 LFS oid，即文件内容 SHA256。
     * 下载完成后校验用；如果 Hugging Face 重新上传导致 SHA 变化，
     * 请同步更新这里，否则下载会一直报 SHA 不一致。
     */
    const val MODEL_SHA256 = "2fde00ce69dd4899c70d020845e2638353015bba0fdf161b3eb965f2bca4464e"

    /** 本地 llama-server 监听地址（只绑回环，不暴露到局域网）。 */
    const val LOCAL_BASE_URL = "http://127.0.0.1:8080"
    const val LOCAL_PORT = 8080
    /** llama-server 里给这个模型起的别名，ApiClient 请求时 model 就填它。 */
    const val LOCAL_MODEL_NAME = "Qwen3-4B-Instruct-2507"

    /** 2B 槽位模型规格（现为 Qwen3-4B-Instruct-2507，和上面旧常量一一对应）。 */
    val MODEL_2B = LocalModel(
        id = "2B",
        displayName = "Qwen3-4B-Instruct-2507 Q4_K_M",
        repo = MODEL_REPO,
        downloadUrl = MODEL_DOWNLOAD_URL,
        fileName = MODEL_FILE_NAME,
        expectedBytes = MODEL_EXPECTED_BYTES,
        sha256 = MODEL_SHA256,
        alias = LOCAL_MODEL_NAME,
        approxSizeLabel = "约 2.50GB",
        isNonThinking = true
    )

    /** 4B 模型规格（2026-11-02 从 HF 官方 API 核实，见文件头注释）。 */
    val MODEL_4B = LocalModel(
        id = "4B",
        displayName = "Qwen3.5-4B Q4_K_M",
        repo = "bartowski/Qwen_Qwen3.5-4B-GGUF",
        // 2026-11-02 用户紧急要求：仅 4B 改走国内镜像 hf-mirror.com，
        // 因为官方直连下载太慢。镜像与官方是同一文件（SHA256 校验不变，
        // 见本文件 sha256 字段，下载完成后仍按此校验），UI 仍显示约 3.01GB。
        // 2B 绝不动：仍用 huggingface.co 官方地址，见 MODEL_2B。
        downloadUrl = "https://hf-mirror.com/bartowski/Qwen_Qwen3.5-4B-GGUF/resolve/main/Qwen_Qwen3.5-4B-Q4_K_M.gguf",
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
