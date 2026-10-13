package com.androidmcphub.app.mcp.store

import org.json.JSONArray
import org.json.JSONObject

/** 一个工具当前的授权状态。 */
enum class McpAuthorizationState(val label: String) {
    NotGranted("未授权"),

    Granted("已授权"),

    /** 授权记录还在，但工具的指纹变了 —— 用户当初同意的不是现在这个定义。 */
    Stale("工具定义已变化，需要重新授权"),
}

/** 一次授权：用户在某时刻同意了某个服务器上的某个工具定义。 */
data class McpGrant(
    val serverId: String,
    val toolName: String,
    val fingerprint: String,
    val grantedAtMs: Long,
)

/**
 * 工具授权的持久化。
 *
 * 关键设计：授权记的是 **(服务器, 工具名, 指纹)** 三元组，不是"这个工具名曾经被同意过"。
 * 服务器改了工具描述或 schema，指纹就变，状态自动从 [McpAuthorizationState.Granted]
 * 掉到 [McpAuthorizationState.Stale] —— 用户必须重新看一眼再同意。
 * 这一条是"授权"和"被骗子换掉之后照样能跑"之间的分界线。
 *
 * [clock] 可注入纯粹是为了让单测能断言 `grantedAtMs`，不是为了做时间旅行。
 */
class McpAuthorizationStore(
    private val store: KeyValueStore,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    /**
     * 查询状态。
     *
     * 找不到授权记录 → [McpAuthorizationState.NotGranted]；
     * 找到但指纹不同 → [McpAuthorizationState.Stale]（不是 NotGranted：
     * 界面上这两种要显示不同的话，前者"未授权"，后者"定义变了，要不要重新同意"）。
     */
    fun state(serverId: String, toolName: String, fingerprint: String): McpAuthorizationState {
        val grant = loadGrants().firstOrNull { it.serverId == serverId && it.toolName == toolName }
            ?: return McpAuthorizationState.NotGranted
        return if (grant.fingerprint == fingerprint) {
            McpAuthorizationState.Granted
        } else {
            McpAuthorizationState.Stale
        }
    }

    /** 授权（或按新指纹重新授权）。 */
    fun grant(serverId: String, toolName: String, fingerprint: String) {
        val grants = loadGrants().filterNot { it.serverId == serverId && it.toolName == toolName }
        val updated = grants + McpGrant(serverId, toolName, fingerprint, clock())
        saveGrants(updated)
    }

    fun revoke(serverId: String, toolName: String) {
        val grants = loadGrants().filterNot { it.serverId == serverId && it.toolName == toolName }
        saveGrants(grants)
    }

    /** 撤销某台服务器上的**全部**授权（用户禁用/删掉服务器时用）。 */
    fun revokeServer(serverId: String) {
        val grants = loadGrants().filterNot { it.serverId == serverId }
        saveGrants(grants)
    }

    fun list(): List<McpGrant> = loadGrants()

    /** [state] 的便捷布尔形式。注意 `Stale` **不算**已授权。 */
    fun isAuthorized(serverId: String, toolName: String, fingerprint: String): Boolean =
        state(serverId, toolName, fingerprint) == McpAuthorizationState.Granted

    // ---- 落盘 ----

    /** 读不出来 / JSON 坏了 → 空列表。授权记录的损坏只会表现为"需要重新授权"，不会崩。 */
    private fun loadGrants(): List<McpGrant> {
        val text = store.getString(KEY_GRANTS) ?: return emptyList()
        val root = try {
            JSONObject(text)
        } catch (_: Exception) {
            return emptyList()
        }
        val array = root.optJSONArray("grants") ?: return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            val serverId = item.optString("serverId", "")
            val toolName = item.optString("toolName", "")
            val fingerprint = item.optString("fingerprint", "")
            if (serverId.isEmpty() || toolName.isEmpty() || fingerprint.isEmpty()) {
                return@mapNotNull null
            }
            McpGrant(serverId, toolName, fingerprint, item.optLong("grantedAtMs", 0L))
        }
    }

    private fun saveGrants(grants: List<McpGrant>) {
        if (grants.isEmpty()) {
            store.remove(KEY_GRANTS)
            return
        }
        val array = JSONArray()
        for (grant in grants) {
            val item = JSONObject()
            item.put("serverId", grant.serverId)
            item.put("toolName", grant.toolName)
            item.put("fingerprint", grant.fingerprint)
            item.put("grantedAtMs", grant.grantedAtMs)
            array.put(item)
        }
        val root = JSONObject()
        root.put("version", CURRENT_VERSION)
        root.put("grants", array)
        store.putString(KEY_GRANTS, root.toString())
    }

    companion object {

        const val KEY_GRANTS = "grants_v1"

        const val CURRENT_VERSION = 1
    }
}
