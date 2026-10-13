package com.androidmcphub.app.mcp.json

import org.json.JSONArray
import org.json.JSONObject

/**
 * 一个**极小的** JSON Schema 校验子集。
 *
 * 为什么不引 JSON Schema 库：
 *  - 手机端只需要"在把参数发给远端之前，拦住明显填错的字段"这一件事。完整的
 *    JSON Schema（$ref / allOf / patternProperties / format …）既要一个几百 KB 的库，
 *    又会给出用户看不懂的报错；
 *  - 真正的裁决权在远端服务器手里：这里返回 `null`（放行）只是"我没发现问题"，
 *    不是"参数一定合法"。
 *
 * 支持的子集：`type`（含数组形式的多选）、`properties` + `required`、
 * `additionalProperties = false`、`enum`、`minimum` / `maximum`、
 * `minLength` / `maxLength`、数组的 `items`（只查每个元素的 type）、
 * 嵌套 object 的递归（**深度上限 [MAX_DEPTH]**）。
 *
 * 递归深度上限不是为了省事：schema 是远端给的，一个自引用/深嵌套的 schema
 * 会把校验变成栈溢出的入口。超深直接报错，不递归。
 */
object JsonSchemaValidator {

    /** 参数整体大小上限（字节）。超过就不发了，别把手机流量和服务器内存当无限的。 */
    const val MAX_ARGUMENT_BYTES = 4096

    /** 嵌套 object 的最大层数（根对象算第 0 层）。 */
    const val MAX_DEPTH = 4

    /**
     * 校验参数。
     *
     * @param schema 工具的 inputSchema；`null` 表示**无法校验**，直接放行（交给远端自己校验）。
     * @param arguments 待校验的参数对象。
     * @return `null` 表示通过；否则是**中文**问题描述（可以直接显示给用户）。
     *
     * 大小上限在 schema 之前判定：参数太大就连发送都不该发送，与 schema 有没有无关。
     */
    fun validate(schema: JSONObject?, arguments: JSONObject): String? {
        val bytes = arguments.toString().toByteArray(Charsets.UTF_8).size
        if (bytes > MAX_ARGUMENT_BYTES) {
            return "参数太大（$bytes 字节，最多 $MAX_ARGUMENT_BYTES 字节）"
        }
        if (schema == null) return null
        return validateNode(schema, arguments, "参数", 0)
    }

    /** 递归校验一个值。[path] 是给人看的路径（`参数.坐标.经度`）。 */
    private fun validateNode(schema: JSONObject, value: Any?, path: String, depth: Int): String? {
        if (depth > MAX_DEPTH) {
            return "$path 的 Schema 嵌套层级超过上限（$MAX_DEPTH 层）"
        }

        val types = readTypes(schema)
        if (types.isNotEmpty() && !matchesAnyType(types, value)) {
            return "$path 的类型应为 ${types.joinToString(" 或 ")}，实际是 ${typeNameOf(value)}"
        }

        val enumValues = schema.optJSONArray("enum")
        if (enumValues != null && !enumContains(enumValues, value)) {
            return "$path 的值不在工具允许的范围内"
        }

        if (value is Number) {
            val number = value.toDouble()
            if (schema.has("minimum")) {
                val min = runCatching { schema.getDouble("minimum") }.getOrNull()
                if (min != null && number < min) return "$path 不能小于 $min"
            }
            if (schema.has("maximum")) {
                val max = runCatching { schema.getDouble("maximum") }.getOrNull()
                if (max != null && number > max) return "$path 不能大于 $max"
            }
        }

        if (value is String) {
            if (schema.has("minLength")) {
                val min = runCatching { schema.getInt("minLength") }.getOrNull()
                if (min != null && value.length < min) return "$path 至少需要 $min 个字符"
            }
            if (schema.has("maxLength")) {
                val max = runCatching { schema.getInt("maxLength") }.getOrNull()
                if (max != null && value.length > max) return "$path 最多只能有 $max 个字符"
            }
        }

        if (value is JSONObject) return validateObject(schema, value, path, depth)
        if (value is JSONArray) return validateArray(schema, value, path)
        return null
    }

    private fun validateObject(schema: JSONObject, value: JSONObject, path: String, depth: Int): String? {
        val required = schema.optJSONArray("required")
        if (required != null) {
            for (index in 0 until required.length()) {
                val key = required.optString(index, "")
                if (key.isEmpty()) continue
                // 显式 null 也算"没给"：JSON Schema 里 null 和缺席对 required 是两种结果，
                // 但工具作者写 required 的意图都是"这个字段要有内容"。
                if (!value.has(key) || value.isNull(key)) return "缺少必填参数：$key"
            }
        }

        val properties = schema.optJSONObject("properties")
        if (properties != null) {
            val names = properties.keys()
            while (names.hasNext()) {
                val key = names.next()
                if (!value.has(key)) continue
                val childSchema = properties.optJSONObject(key) ?: continue
                val childValue = if (value.isNull(key)) null else value.get(key)
                val problem = validateNode(childSchema, childValue, "$path.$key", depth + 1)
                if (problem != null) return problem
            }
        }

        if (schema.has("additionalProperties") && !schema.optBoolean("additionalProperties", true)) {
            val declared = properties?.let { it.keys().asSequence().toSet() } ?: emptySet()
            val names = value.keys()
            while (names.hasNext()) {
                val key = names.next()
                if (key !in declared) return "$path 里出现了工具没声明的字段：$key"
            }
        }
        return null
    }

    private fun validateArray(schema: JSONObject, value: JSONArray, path: String): String? {
        val items = schema.optJSONObject("items") ?: return null
        val itemTypes = readTypes(items)
        if (itemTypes.isEmpty()) return null
        for (index in 0 until value.length()) {
            val element = if (value.isNull(index)) null else value.get(index)
            if (!matchesAnyType(itemTypes, element)) {
                return "$path[$index] 的类型应为 ${itemTypes.joinToString(" 或 ")}，实际是 ${typeNameOf(element)}"
            }
        }
        return null
    }

    /** `type` 既可以是字符串，也可以是字符串数组（多选）。不认识的写法当成"没有约束"。 */
    private fun readTypes(schema: JSONObject): List<String> {
        return when (val raw = schema.opt("type")) {
            is String -> if (raw.isEmpty()) emptyList() else listOf(raw)
            is JSONArray -> (0 until raw.length())
                .mapNotNull { raw.optString(it, "").takeIf { type -> type.isNotEmpty() } }
            else -> emptyList()
        }
    }

    private fun matchesAnyType(types: List<String>, value: Any?): Boolean = types.any { type ->
        when (type) {
            "object" -> value is JSONObject
            "array" -> value is JSONArray
            "string" -> value is String
            "boolean" -> value is Boolean
            "null" -> value == null
            "number" -> value is Number
            "integer" -> value is Number && isIntegral(value)
            // 不认识的 type 一律放行：可能是新规范里的关键字，不该由客户端武断拒绝。
            else -> true
        }
    }

    private fun isIntegral(value: Number): Boolean {
        if (value is Int || value is Long || value is Short || value is Byte) return true
        val number = value.toDouble()
        return number.isFinite() && number % 1.0 == 0.0
    }

    private fun enumContains(enumValues: JSONArray, value: Any?): Boolean {
        for (index in 0 until enumValues.length()) {
            val candidate = if (enumValues.isNull(index)) null else enumValues.get(index)
            if (jsonEquals(candidate, value)) return true
        }
        return false
    }

    private fun jsonEquals(left: Any?, right: Any?): Boolean {
        if (left == null || right == null) return left == null && right == null
        if (left is Number && right is Number) return left.toDouble() == right.toDouble()
        return left == right
    }

    private fun typeNameOf(value: Any?): String = when (value) {
        null -> "null"
        is JSONObject -> "object"
        is JSONArray -> "array"
        is String -> "string"
        is Boolean -> "boolean"
        is Number -> if (isIntegral(value)) "integer" else "number"
        else -> "未知类型"
    }
}
