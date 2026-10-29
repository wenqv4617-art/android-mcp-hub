package com.androidmcphub.app.gateway

import android.content.Context
import android.os.Build
import android.provider.Settings
import com.androidmcphub.app.core.AndroidSecretCipher
import com.androidmcphub.app.core.HubPrefs
import com.androidmcphub.app.core.PrefsSecretStorage
import com.androidmcphub.app.mcp.store.AndroidSecretBlobStore
import java.security.MessageDigest

/** 凭据存储的窄接口：JVM 测试不需要 Android Keystore。 */
interface GatewayCredentialsStore {
    fun baseUrl(): String
    fun saveBaseUrl(baseUrl: String)
    fun load(): GatewayCredentials?
    fun save(credentials: GatewayCredentials)
    fun clear()

    /** 配对上报的设备标识（不是认证凭据；认证靠配对口令）。 */
    fun deviceId(): String

    fun deviceName(): String
}

/**
 * 真机实现。
 *
 * 两枚令牌（受限管理令牌 + 该客户端的 MCP 令牌）各自用 Android Keystore 的
 * AES-256-GCM 密钥加密后落盘；SharedPreferences 里只有密文。**没有**任何明文
 * 令牌进入 BuildConfig、资源、Gradle 或日志。全局 `GATEWAY_ADMIN_TOKEN`
 * 从不参与配对，也不会出现在这个应用里。
 */
class AndroidGatewayCredentialsStore(context: Context) : GatewayCredentialsStore {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
    private val management = AndroidSecretBlobStore(
        AndroidSecretCipher(alias = MANAGEMENT_ALIAS),
        PrefsSecretStorage(prefs, KEY_MANAGEMENT_BLOB),
    )
    private val client = AndroidSecretBlobStore(
        AndroidSecretCipher(alias = CLIENT_ALIAS),
        PrefsSecretStorage(prefs, KEY_CLIENT_BLOB),
    )

    override fun baseUrl(): String = prefs.getString(KEY_BASE_URL, null).orEmpty()

    override fun saveBaseUrl(baseUrl: String) {
        prefs.edit().putString(KEY_BASE_URL, baseUrl).commit()
    }

    override fun load(): GatewayCredentials? {
        val managementToken = management.read()?.takeIf { it.startsWith("hub_admin_") } ?: return null
        val clientToken = client.read()?.takeIf { it.startsWith("hub_client_") } ?: return null
        return GatewayCredentials(managementToken, clientToken)
    }

    override fun save(credentials: GatewayCredentials) {
        management.write(credentials.managementToken)
        client.write(credentials.clientToken)
    }

    override fun clear() {
        management.clear()
        client.clear()
    }

    /**
     * 配对用的设备标识。
     *
     * 优先使用用户已经配置的设备 ID（与手机长连接身份一致）；没配或格式不合法时，
     * 用 ANDROID_ID 的单向摘要生成一个稳定值。两者都不含硬件序列号，也不需要新权限。
     * 服务端只把它当"这一台设备"的口径，不会拿它当认证凭据 —— 认证靠配对口令。
     */
    override fun deviceId(): String {
        val configured = runCatching { HubPrefs(appContext).load().deviceId }.getOrNull().orEmpty()
        if (DEVICE_ID.matches(configured)) return configured
        val androidId = runCatching {
            Settings.Secure.getString(appContext.contentResolver, Settings.Secure.ANDROID_ID)
        }.getOrNull().orEmpty()
        val digest = MessageDigest.getInstance("SHA-256")
            .digest((androidId + appContext.packageName).toByteArray(Charsets.UTF_8))
        val suffix = digest.take(10).joinToString("") { "%02x".format(it.toInt() and 0xff) }
        return "android-$suffix"
    }

    override fun deviceName(): String {
        val model = listOf(Build.MANUFACTURER, Build.MODEL)
            .filter { it.isNotBlank() }
            .joinToString(" ")
            .trim()
        return model.ifEmpty { "Android" }.take(64)
    }

    companion object {
        const val PREF_FILE = "gateway_client_v1"
        const val KEY_BASE_URL = "base_url"
        const val KEY_MANAGEMENT_BLOB = "management_token_v1"
        const val KEY_CLIENT_BLOB = "client_token_v1"
        private const val MANAGEMENT_ALIAS = "android-mcp-hub.gateway-admin.v1"
        private const val CLIENT_ALIAS = "android-mcp-hub.gateway-client.v1"
        private val DEVICE_ID = Regex("^[a-z][a-z0-9-]{0,31}$")
    }
}
