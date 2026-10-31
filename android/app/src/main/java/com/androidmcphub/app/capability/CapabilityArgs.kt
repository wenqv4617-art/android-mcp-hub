package com.androidmcphub.app.capability

import org.json.JSONObject

/**
 * 参数校验失败：消息是**我们自己的**文案，可以直接回给调用方。
 *
 * 与 `JSONException` / `IllegalArgumentException` 分开，是为了让能力代码可以
 * "解析到哪里失败就在哪里抛"，而 [Capability.validate] 只需要把它转成字符串。
 * 分发器已经挡掉了白名单外的键，所以这里只处理"类型 / 范围 / 必填"。
 */
class ArgumentProblem(message: String) : Exception(message)

/**
 * 参数读取的唯一入口。
 *
 * 为什么集中在这里：同一种字段（时间、ID、标题）会在多个工具里出现，
 * 如果每个能力自己写一遍 `optString`，就会出现"memo 的标题最多 200 字、
 * todo 的标题最多 500 字"这种无法解释的不一致。这里让上限成为**调用方传入的参数**，
 * 仍然明确，但类型检查只有一份。
 *
 * 所有读取函数都遵守同一条约定：
 *  * 键不存在或值为 JSON null → 由 `required` / `optional` 决定行为；
 *  * 类型不对 → 抛 [ArgumentProblem]，**不**做隐式转换（`"5"` 不是整数）。
 */
object CapabilityArgs {

    fun requiredString(obj: JSONObject, name: String, maxLength: Int, allowBlank: Boolean = false): String {
        val value = optionalString(obj, name, maxLength, allowBlank)
            ?: throw ArgumentProblem("缺少参数 $name")
        return value
    }

    fun optionalString(
        obj: JSONObject,
        name: String,
        maxLength: Int,
        allowBlank: Boolean = true,
    ): String? {
        if (!obj.has(name) || obj.isNull(name)) return null
        val value = obj.opt(name)
        if (value !is String) throw ArgumentProblem("参数 $name 必须是字符串")
        if (!allowBlank && value.isBlank()) throw ArgumentProblem("参数 $name 不能为空")
        if (value.length > maxLength) throw ArgumentProblem("参数 $name 不能超过 $maxLength 个字符")
        return value
    }

    fun requiredLong(obj: JSONObject, name: String): Long =
        optionalLong(obj, name) ?: throw ArgumentProblem("缺少参数 $name")

    fun optionalLong(obj: JSONObject, name: String): Long? {
        if (!obj.has(name) || obj.isNull(name)) return null
        val value = obj.opt(name)
        if (value !is Number) throw ArgumentProblem("参数 $name 必须是整数")
        val asDouble = value.toDouble()
        if (asDouble % 1.0 != 0.0) throw ArgumentProblem("参数 $name 必须是整数")
        if (asDouble > Long.MAX_VALUE.toDouble() || asDouble < Long.MIN_VALUE.toDouble()) {
            throw ArgumentProblem("参数 $name 超出范围")
        }
        return value.toLong()
    }

    fun requiredInt(obj: JSONObject, name: String): Int =
        optionalInt(obj, name) ?: throw ArgumentProblem("缺少参数 $name")

    fun optionalInt(obj: JSONObject, name: String): Int? {
        val value = optionalLong(obj, name) ?: return null
        if (value < Int.MIN_VALUE || value > Int.MAX_VALUE) throw ArgumentProblem("参数 $name 超出范围")
        return value.toInt()
    }

    fun optionalBoolean(obj: JSONObject, name: String): Boolean? {
        if (!obj.has(name) || obj.isNull(name)) return null
        val value = obj.opt(name)
        if (value !is Boolean) throw ArgumentProblem("参数 $name 必须是布尔值")
        return value
    }

    fun intInRange(obj: JSONObject, name: String, range: IntRange, default: Int? = null): Int {
        val value = optionalInt(obj, name)
        if (value == null) {
            return default ?: throw ArgumentProblem("缺少参数 $name")
        }
        if (value !in range) {
            throw ArgumentProblem("参数 $name 必须在 ${range.first}..${range.last} 之间")
        }
        return value
    }

    /**
     * 枚举参数：只接受给定集合里的值，大小写**不敏感**，返回规范形式。
     */
    fun enumeration(obj: JSONObject, name: String, allowed: Set<String>, default: String? = null): String {
        val raw = optionalString(obj, name, 32, allowBlank = false)
        if (raw == null) return default ?: throw ArgumentProblem("缺少参数 $name")
        val normalized = raw.trim().lowercase()
        return allowed.firstOrNull { it == normalized }
            ?: throw ArgumentProblem("参数 $name 只能是：${allowed.joinToString(", ")}")
    }
}

/**
 * 把 "解析 → 校验" 写成同一种形状。
 *
 * [Capability.validate] 和 `execute` 都要解析参数；用一个共享函数保证两边
 * 对"什么算合法"的判断**不可能**不一样（否则会出现"校验通过、执行又拒绝"
 * 或更糟的"校验拒绝、执行其实能跑"）。
 */
internal inline fun <T> validated(arguments: JSONObject, parse: () -> T): Pair<T?, String?> =
    try {
        parse() to null
    } catch (problem: ArgumentProblem) {
        null to (problem.message ?: "参数不合法")
    }
