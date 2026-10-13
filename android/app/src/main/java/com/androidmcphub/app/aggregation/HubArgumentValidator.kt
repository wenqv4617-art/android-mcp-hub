package com.androidmcphub.app.aggregation

import org.json.JSONArray
import org.json.JSONObject

/** [HubArgumentValidator.validate] 的结果。 */
sealed interface HubArgumentValidation {

    /** 校验通过，[value] 是**重建**出来的副本。 */
    data class Ok(val value: JSONObject) : HubArgumentValidation

    data class Error(val message: String) : HubArgumentValidation
}

/**
 * 第三方工具参数的本地校验。
 *
 * ## 为什么 Worker 已经校验过一遍，这里还要再校验
 *
 * 不是不信任 Worker，而是不信任"路径"：一条 `tool_request` 帧可以从 ChatGPT 来，
 * 也可以在本地联调时从任何连上本机 WebSocket 的东西来。手机是最后一道关口，
 * 它必须自己判断"这个参数我敢不敢原样发给第三方服务器"。Android 的
 * `JSONObject` 恰好**没有**原型污染（`__proto__` 在这里只是一个普通键名），
 * 所以这条防线不是为了防原型污染，而是为了：
 *
 *  - **上限**：远端可以伪造一个 10 万节点的 arguments，手机不查就会把它整份
 *    塞进内存再发给第三方服务器。上限必须由本机执行者掌握。
 *  - **重建**：所有通过校验的参数都放进一个**新**对象。上游传进来的 `JSONObject`
 *    之后被谁改了、还持有什么引用，都与发出去的内容无关。
 *  - **一致性**：危险键名在这里就被拒，而不是指望第三方服务器不介意。
 */
object HubArgumentValidator {

    /**
     * 危险键名。JSON 语义上它们只是普通字符串键，但在下游各种语言/库的实现里
     * 常常有特殊含义（`__proto__` 在 JS 里能改原型、`constructor`/`prototype`
     * 是原型链上的名字）。这里**一律拒绝**：没有任何合法的工具参数需要叫这个名字，
     * 而放行的代价是给整条链路引入一类与实现细节强耦合的漏洞。
     */
    private val FORBIDDEN_KEYS = setOf("__proto__", "constructor", "prototype")

    private class Rejected(message: String) : Exception(message)

    /**
     * @param raw 上游给的参数对象。`null` 与 `{}` 等价（都表示"没有参数"）。
     * @return [HubArgumentValidation.Ok] 时 [HubArgumentValidation.Ok.value] 一定是新对象。
     */
    fun validate(raw: JSONObject?, limits: HubLimits = HubLimits()): HubArgumentValidation {
        if (raw == null) return HubArgumentValidation.Ok(JSONObject())

        // 先按序列化长度粗筛：比逐节点走一遍便宜，也能挡住"一个巨大的字符串"。
        if (raw.toString().length > limits.maxArgumentChars) {
            return HubArgumentValidation.Error("参数太大（上限 ${limits.maxArgumentChars} 字符）")
        }

        var nodes = 0
        val counter: () -> Unit = {
            nodes++
            if (nodes > limits.maxArgumentNodes) {
                throw Rejected("参数节点太多（上限 ${limits.maxArgumentNodes} 个）")
            }
        }
        return try {
            HubArgumentValidation.Ok(copyObject(raw, depth = 0, limits = limits, counter = counter))
        } catch (rejected: Rejected) {
            HubArgumentValidation.Error(rejected.message ?: "参数不合法")
        }
    }

    private fun copyObject(
        source: JSONObject,
        depth: Int,
        limits: HubLimits,
        counter: () -> Unit,
    ): JSONObject {
        if (depth > limits.maxArgumentDepth) {
            throw Rejected("参数嵌套太深（上限 ${limits.maxArgumentDepth} 层）")
        }
        val copy = JSONObject()
        val keys = source.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            if (key.length > limits.maxArgumentKeyLength) {
                throw Rejected("参数里有超长字段名（上限 ${limits.maxArgumentKeyLength} 字符）")
            }
            if (key in FORBIDDEN_KEYS) {
                throw Rejected("参数里有不允许的字段名：$key")
            }
            copy.put(key, copyValue(source.opt(key), depth, limits, counter))
        }
        return copy
    }

    private fun copyArray(
        source: JSONArray,
        depth: Int,
        limits: HubLimits,
        counter: () -> Unit,
    ): JSONArray {
        if (depth > limits.maxArgumentDepth) {
            throw Rejected("参数嵌套太深（上限 ${limits.maxArgumentDepth} 层）")
        }
        if (source.length() > limits.maxArgumentArrayLength) {
            throw Rejected("参数里有超长数组（上限 ${limits.maxArgumentArrayLength} 项）")
        }
        val copy = JSONArray()
        for (index in 0 until source.length()) {
            copy.put(copyValue(source.opt(index), depth, limits, counter))
        }
        return copy
    }

    private fun copyValue(
        value: Any?,
        parentDepth: Int,
        limits: HubLimits,
        counter: () -> Unit,
    ): Any? {
        counter()
        // `null` 与 `JSONObject.NULL` 在 org.json 里是两种东西，这里统一成后者：
        // 前者放进 JSONObject 会把键**删掉**，参数就会静默丢字段。
        if (value == null || value == JSONObject.NULL) return JSONObject.NULL
        return when (value) {
            is JSONObject -> copyObject(value, parentDepth + 1, limits, counter)
            is JSONArray -> copyArray(value, parentDepth + 1, limits, counter)
            is String -> {
                if (value.length > limits.maxArgumentString) {
                    throw Rejected("参数里有超长字符串（上限 ${limits.maxArgumentString} 字符）")
                }
                value
            }
            is Boolean -> value
            is Int, is Long -> value
            is Double -> {
                if (!value.isFinite()) throw Rejected("参数里出现了非有限数字")
                value
            }
            is Float -> {
                if (!value.isFinite()) throw Rejected("参数里出现了非有限数字")
                value
            }
            is Number -> value
            else -> throw Rejected("参数里出现了不支持的值类型")
        }
    }
}
