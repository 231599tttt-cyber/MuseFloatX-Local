package com.example.musefloatx

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * 本地版设置：只有风格和上下文，没有服务器地址、没有密钥、没有模型名。
 * 旧版的 base_url / apiKey / model 字段已删掉——本地版不需要它们，
 * 旧版升级过来也不会再读到服务器配置。
 */
class SettingsStore(context: Context) {
    private val prefs: SharedPreferences = try {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "musefloat_local_prefs",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (e: Exception) {
        context.getSharedPreferences("musefloat_local_prefs", Context.MODE_PRIVATE)
    }

    var style: String
        get() = prefs.getString("style", "自然") ?: "自然"
        set(v) = prefs.edit().putString("style", v).apply()

    /**
     * 当前选用的模型 id："2B" 或 "4B"，默认 2B（老用户升级过来也是 2B，
     * 不会因为加了 4B 就自动换模型）。切换模型时只改这个值 + 停掉
     * 正在跑的服务，下次启动/生成时 LocalServer 按这个值加载新模型；
     * 两个模型文件各存各的，切换不删除任何一个。
     */
    var selectedModelId: String
        get() = prefs.getString("selected_model", "2B") ?: "2B"
        set(v) = prefs.edit().putString("selected_model", LocalConfig.modelForId(v).id).apply()

    /** 当前选用的模型规格（id 不认识时回落 2B，见 LocalConfig.modelForId）。 */
    var selectedModel: LocalModel
        get() = LocalConfig.modelForId(selectedModelId)
        set(v) { selectedModelId = v.id }

    /**
     * 思考模式（2026-11-02 用户要求可选，不再写死 off）：
     * - "off" 关闭（快）：默认，老用户升级过来也是这个，行为和上一版完全一样。
     * - "auto" 自动：让 llama-server 按模型模板自己决定是否思考。
     * - "on" 开启（慢但可能更准）：强制思考，质量不保证一定更好，
     *   只是给用户一个可以自己试的开关。
     * 值不在这三个里时回落 "off"，绝不把非法值传给 llama-server。
     * 改动后必须停掉正在跑的服务，下次启动/生成时 LocalServer 才会
     * 按新值传 --reasoning；只改这个值、不重启服务是不会生效的。
     */
    var reasoningMode: String
        get() = when (prefs.getString("reasoning_mode", "off")) {
            "auto", "on" -> prefs.getString("reasoning_mode", "off") ?: "off"
            else -> "off"
        }
        set(v) = prefs.edit().putString(
            "reasoning_mode",
            when (v) { "auto", "on" -> v; else -> "off" }
        ).apply()


    /**
     * 上下文 tokens：第一版默认 1024（LocalConfig.DEFAULT_CONTEXT_TOKENS），
     * 不是写死的 512，也不是不可改的常量。用户可以在主界面改：
     * 6GB 机器如果卡，可以改成 512；12GB 机器如果想多留上下文，可以改大，
     * 改完下次加载模型生效（当前已加载的服务不会中途换上下文）。
     */
    var contextTokens: Int
        get() = prefs.getInt("context_tokens", LocalConfig.DEFAULT_CONTEXT_TOKENS)
        set(v) = prefs.edit().putInt("context_tokens", v.coerceIn(512, 4096)).apply()
}
