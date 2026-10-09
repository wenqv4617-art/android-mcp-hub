package com.androidmcphub.app.core

import android.content.Context
import android.content.SharedPreferences

/**
 * 落盘层：SharedPreferences（MODE_PRIVATE）。
 *
 * 刻意不用 DataStore —— 本工程只有一屏表单 + 一个令牌，
 * 引一整套协程化持久化库不划算。
 *
 * 安全约定：
 *  - 配置项与**设备令牌分两个键**存放，令牌不进 [HubConfig]；
 *  - 令牌只出现在请求头里，任何日志、异常信息、界面文本都不会打印它；
 *  - AndroidManifest 里 `allowBackup="false"`，令牌不会被云备份带走。
 */
class HubPrefs(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)

    fun load(): HubConfig {
        // 只取字符串值；HubConfig.fromMap 会忽略不认识的键（例如令牌键）
        val flat = mutableMapOf<String, String?>()
        for ((key, value) in prefs.all) {
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

    fun loadToken(): String = prefs.getString(KEY_TOKEN, "").orEmpty()

    fun saveToken(token: String) {
        prefs.edit().putString(KEY_TOKEN, token).apply()
    }

    companion object {
        const val PREF_FILE = "hub_settings"
        const val KEY_TOKEN = "device_token"
    }
}
