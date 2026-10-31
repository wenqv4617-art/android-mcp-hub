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
        // 非字符串的开关（如能力开关）也跳过 —— `value as? String` 会给它塞一个
        // null 值进 map，虽然 fromMap 会忽略，但没必要让"配置里有个空键"存在。
        val flat = mutableMapOf<String, String?>()
        for ((key, value) in prefs.all) {
            if (key in NON_CONFIG_KEYS) continue
            flat[key] = value as? String
        }
        return HubConfig.fromMap(flat)
    }

    /**
     * 本机是否允许把只读能力交给已授权的服务端调用（Gate 2 工作包 D "本地授权策略"）。
     *
     * 默认**关**（集中审计问题 5）。这条默认值是**安全默认**，不是体验取舍：
     *  - 白名单（`CapabilityDispatcher` 的注册表）决定"哪些能力存在"，是编译期的；
     *    这个开关是用户手里的第二个、也是更粗的闸门 —— 关掉之后手机对任何
     *    `tool_request` 都回 `not_permitted`，且**不执行**任何能力。
     *  - 首次安装时用户还没有对这个应用授权过任何事。默认开等于"装完即可被远端
     *    读电池状态"，而用户从未表达过同意；默认关则把同意变成一次**明确动作**
     *    （用户自己到界面上打开总开关）。
     *  - 代价是升级上来的老用户要自己打开一次开关。这是可接受的：功能不会被
     *    破坏，只是被挡住，并且服务端会拿到明确的 `not_permitted`（而不是超时）。
     *
     * 与"用户已明确设置过"的关系：该键**存在**时一律以用户的值为准，只有键不存在
     * （全新安装、或用户从未动过）才落到 [DEFAULT_CAPABILITIES_ENABLED]。
     * 所以老用户此前手动关掉的设置、以及手动打开的设置，都不会因为这次改默认值而变化。
     */
    fun capabilitiesEnabled(): Boolean = prefs.getBoolean(KEY_CAPABILITIES_ENABLED, DEFAULT_CAPABILITIES_ENABLED)

    /** 写入能力开关。默认值不落盘：删掉这个键就回到默认。 */
    fun saveCapabilitiesEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_CAPABILITIES_ENABLED, enabled).apply()
    }

    /**
     * 是否允许执行**会修改数据的**能力（新建/修改/删除闹钟、备忘、日历事件等）。
     *
     * 与只读总开关**分开**，默认同样是关：
     *  - 只读开关打开只代表"可以看"，不代表"可以改"；把两者合成一个开关，
     *    等于用户为了读电量就顺手交出了删除备忘的权限；
     *  - 写操作还要再过一道**单条工具开关**（[isCapabilityToolEnabled]，写入类默认关），
     *    两道闸门都打开才会执行；
     *  - 删除/覆盖类工具还需要调用方显式传 `confirm=true`（执行层校验）。
     */
    fun writeCapabilitiesEnabled(): Boolean =
        prefs.getBoolean(KEY_WRITE_CAPABILITIES_ENABLED, DEFAULT_WRITE_CAPABILITIES_ENABLED)

    fun saveWriteCapabilitiesEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_WRITE_CAPABILITIES_ENABLED, enabled).apply()
    }

    /**
     * 被用户**单独关掉**的能力名集合（存的是"关闭"而不是"开启"）。
     *
     * 存"关闭"是为了让将来新注册的能力默认可被总开关管理，而不是每次加能力
     * 都要用户重新去开一遍。写入类能力的默认值不在这里体现 —— 它们由
     * `CapabilityMetadata.readOnly == false` 在运行期判定（见 [PhoneLinkService]）。
     */
    fun disabledCapabilities(): Set<String> =
        prefs.getStringSet(KEY_DISABLED_CAPABILITIES, emptySet())?.toSet() ?: emptySet()

    /** 这一条工具是否被单独关掉。 */
    fun isCapabilityToolEnabled(name: String): Boolean = name !in disabledCapabilities()

    /** 单独开关一条工具；[enabled] 为 false 时记入关闭集合。 */
    fun setCapabilityToolEnabled(name: String, enabled: Boolean) {
        val next = disabledCapabilities().toMutableSet()
        if (enabled) next.remove(name) else next.add(name)
        // getStringSet 返回的可能是内部实例，必须新建集合并写回。
        prefs.edit().putStringSet(KEY_DISABLED_CAPABILITIES, next).apply()
    }

    /**
     * 被用户**逐条打开**的写入类能力。
     *
     * 与只读工具"默认开、可单独关"相反，写入类工具是"默认关、必须单独开"：
     * 只打开写入总开关只表示"我知道这个应用可以改数据"，不等于"我同意它改**这一类**数据"。
     * 删除类工具还会在执行层再要求 `confirm=true`。
     */
    fun enabledWriteCapabilities(): Set<String> =
        prefs.getStringSet(KEY_ENABLED_WRITE_CAPABILITIES, emptySet())?.toSet() ?: emptySet()

    fun isWriteCapabilityEnabled(name: String): Boolean = name in enabledWriteCapabilities()

    fun setWriteCapabilityEnabled(name: String, enabled: Boolean) {
        val next = enabledWriteCapabilities().toMutableSet()
        if (enabled) next.add(name) else next.remove(name)
        prefs.edit().putStringSet(KEY_ENABLED_WRITE_CAPABILITIES, next).apply()
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

        /** 只读能力总开关（布尔）。它不是 [HubConfig] 的一部分，见 [capabilitiesEnabled]。 */
        const val KEY_CAPABILITIES_ENABLED = "capabilities_enabled"

        /** 写入类能力总开关（布尔），见 [writeCapabilitiesEnabled]。 */
        const val KEY_WRITE_CAPABILITIES_ENABLED = "write_capabilities_enabled"

        /** 被单独关闭的能力名集合（StringSet），见 [disabledCapabilities]。 */
        const val KEY_DISABLED_CAPABILITIES = "disabled_capabilities"

        /** 被逐条打开的写入类能力名集合（StringSet），见 [enabledWriteCapabilities]。 */
        const val KEY_ENABLED_WRITE_CAPABILITIES = "enabled_write_capabilities"

        /**
         * 总开关的**安全默认**：首次安装（键不存在）时为 `false`，即"未授权"。
         *
         * 集中审计问题 5：这里单独提成一个常量，是为了让"默认值"可被单测直接钉住
         * （见 `CapabilitySwitchDefaultTest`）—— 任何把它改回 `true` 的改动都必须
         * 显式地改测试并给出理由，而不是在 `getBoolean` 的第二个实参里悄悄发生。
         */
        const val DEFAULT_CAPABILITIES_ENABLED: Boolean = false

        /**
         * 写入类能力的**安全默认**：关。用户必须单独同意"可以改数据"，
         * 并且写入类工具还要逐个打开（见 [HubPrefs.isCapabilityToolEnabled] 的注释）。
         */
        const val DEFAULT_WRITE_CAPABILITIES_ENABLED: Boolean = false

        /** [load] 时一律跳过的键：凭据与所有非 [HubConfig] 的开关。 */
        val NON_CONFIG_KEYS: Set<String> =
            SECRET_KEYS + setOf(
                KEY_CAPABILITIES_ENABLED,
                KEY_WRITE_CAPABILITIES_ENABLED,
                KEY_DISABLED_CAPABILITIES,
                KEY_ENABLED_WRITE_CAPABILITIES,
            )
    }
}
