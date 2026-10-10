package com.androidmcphub.app.core

import android.content.Context
import android.content.SharedPreferences

/**
 * 落盘层：SharedPreferences（MODE_PRIVATE）。
 *
 * 刻意不用 DataStore —— 本工程只有一屏表单 + 一个令牌，
 * 引一整套协程化持久化库不划算。
 *
 * 安全约定（Gate 0.1 审计 R4 之后）：
 *  - 配置项与**设备令牌分两个键**存放，令牌不进 [HubConfig]；
 *    [load] 还会把两个密钥键从读出来的 map 里剔掉 —— 哪怕键里只有密文，
 *    也不该有任何一条代码路径能顺手把凭据打出来；
 *  - 令牌**不以明文落盘**：AES-256-GCM 加密后只写密文（见 [SecureTokenStore]），
 *    密钥在 Android Keystore 里且不可导出；
 *  - 令牌只出现在 WebSocket 的请求头里，任何日志、异常信息、通知文本都不含它；
 *  - AndroidManifest 里 `allowBackup="false"`，令牌不会被云备份带走。
 */
class HubPrefs(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)

    private val store = SecureTokenStore(
        cipher = AndroidSecretCipher(),
        storage = PrefsSecretStorage(prefs),
    )

    fun load(): HubConfig {
        // 只取字符串值；HubConfig.fromMap 会忽略不认识的键。
        // 密钥键被显式跳过：配置的 map 里不该出现凭据，密文也不行。
        val flat = mutableMapOf<String, String?>()
        for ((key, value) in prefs.all) {
            if (key in SECRET_KEYS) continue
            flat[key] = value as? String
        }
        return HubConfig.fromMap(flat)
    }

    fun save(config: HubConfig) {
        val editor = prefs.edit()
        for ((key, value) in config.toMap()) {
            editor.putString(key, value)
        }
        editor.apply()
    }

    /**
     * 读设备令牌。
     *
     * 返回三态而不是 `String`：**"没设过"和"设过但解不开"必须可区分**。
     * 把后者压成空串，用户只会看到"认证失败"，永远不知道是自己那串令牌根本没被读出来。
     */
    fun loadTokenResult(): TokenLoadResult {
        migrateLegacyPlaintextIfNeeded()?.let { return it }
        return store.load()
    }

    /**
     * 保存设备令牌。传空白串等价于清除。
     *
     * @return 是否真的写成功。加密或落盘失败必须让调用方知道 —— 静默失败会让用户
     *         以为令牌已经存好了。
     */
    fun saveToken(token: String): Boolean = when (store.save(token)) {
        TokenSaveResult.Ok -> {
            // 无论是保存新令牌还是清空，明文键都必须消失。
            dropLegacyPlaintext()
            true
        }

        is TokenSaveResult.Failed -> false
    }

    /** 清空令牌。**不动** Keystore 里的密钥 —— 令牌没了不代表密钥坏了。 */
    fun clearToken() {
        store.clear()
        dropLegacyPlaintext()
    }

    /**
     * 把 Gate 0 老版本的明文令牌迁移成密文。
     *
     * 老版本把令牌直接放在 `device_token` 里。这里做一次性迁移，迁移成功**立刻删明文**。
     * 迁移失败时**不删明文**（宁可让明文多留一会儿，也不能让用户把凭据弄丢），
     * 但明确返回 [TokenLoadResult.Unreadable] —— 绝不假装"没设过"然后拿空令牌去连。
     *
     * @return 需要立刻返回给调用方的结果；`null` 表示"没事，继续正常读"。
     */
    private fun migrateLegacyPlaintextIfNeeded(): TokenLoadResult? {
        val legacy = prefs.getString(LEGACY_KEY_TOKEN, null) ?: return null

        // 新格式已经存在：这条明文是上次迁移没删干净的残留，以新格式为准。
        if (prefs.contains(PrefsSecretStorage.KEY_TOKEN_BLOB)) {
            dropLegacyPlaintext()
            return null
        }

        if (legacy.isBlank()) {
            dropLegacyPlaintext()
            return null
        }

        return when (store.save(legacy)) {
            TokenSaveResult.Ok -> {
                dropLegacyPlaintext()
                null
            }

            is TokenSaveResult.Failed -> TokenLoadResult.Unreadable(TokenFailure.MIGRATION_FAILED)
        }
    }

    private fun dropLegacyPlaintext() {
        if (prefs.contains(LEGACY_KEY_TOKEN)) {
            prefs.edit().remove(LEGACY_KEY_TOKEN).commit()
        }
    }

    companion object {

        const val PREF_FILE = "hub_settings"

        /**
         * Gate 0 的旧键，里面是**明文**令牌。只用于迁移，迁移完就被删掉。
         * 不要在任何新代码里写它。
         */
        const val LEGACY_KEY_TOKEN = "device_token"

        /** 不允许出现在 [load] 结果里的键。 */
        val SECRET_KEYS: Set<String> = setOf(PrefsSecretStorage.KEY_TOKEN_BLOB, LEGACY_KEY_TOKEN)
    }
}
