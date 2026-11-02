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
     * 上下文 tokens：第一版默认 1024（LocalConfig.DEFAULT_CONTEXT_TOKENS），
     * 不是写死的 512，也不是不可改的常量。用户可以在主界面改：
     * 6GB 机器如果卡，可以改成 512；12GB 机器如果想多留上下文，可以改大，
     * 改完下次加载模型生效（当前已加载的服务不会中途换上下文）。
     */
    var contextTokens: Int
        get() = prefs.getInt("context_tokens", LocalConfig.DEFAULT_CONTEXT_TOKENS)
        set(v) = prefs.edit().putInt("context_tokens", v.coerceIn(512, 4096)).apply()
}
