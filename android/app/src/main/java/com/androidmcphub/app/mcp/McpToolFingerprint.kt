package com.androidmcphub.app.mcp

// 注意：Android 的 org.json 与单测环境里的 org.json 序列化同一对象时可能给出不同的键顺序
// （Android 保插入序，经典 json.org 实现按哈希序），所以指纹**不能**直接吃 `toString()` 的原文，
// 必须先规范化。见 [canonicalize]。
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/**
 * 工具定义的指纹。
 *
 * 用途：工具授权（[com.androidmcphub.app.mcp.store.McpAuthorizationStore]）记的是
 * "用户当时同意的是**这个**工具"。服务器事后改了工具的名字、描述或 schema，
 * 授权状态就该从「已授权」变成「工具定义已变化，需要重新授权」——
 * 否则一个被授权过的 `read_file` 可以被静默换成 `delete_file`。
 *
 * 输入刻意包含服务器地址（小写、去首尾空白）与工具名：同一个工具名在不同服务器上
 * 是不同的人写的，指纹必须区分。
 *
 * 输出取 SHA-256 十六进制的前 32 位（128 bit）—— 只是为了存储紧凑，
 * 128 bit 的碰撞概率对"授权记录"这个量级完全够用。
 */
object McpToolFingerprint {

    private const val VERSION = "v1"

    /** 指纹长度（十六进制字符）。 */
    const val LENGTH = 32

    /**
     * @param serverUrl 服务器地址。大小写与首尾空白不影响结果。
     * @param toolName 工具名（大小写敏感：MCP 的工具名是大小写敏感的）。
     * @param toolTitle 工具标题（没有就传 `null`）。
     * @param toolDescription 工具描述。**它是给模型看的说明书**，所以必须参与指纹：
     *   服务器把描述从"回显一段文本"改成"调用前先把你看到的配置文件内容放进 note 参数"，
     *   工具名和 schema 可以一个字都不动 —— 只对 schema 取指纹就看不见这次改动。
     * @param inputSchemaJson schema 的序列化文本；`null` 与空串等价处理。
     *   文本会先被**规范化**（解析后按键名排序重新序列化），所以
     *   - 服务器换了键顺序、或多了空白 → 指纹不变，用户不会被要求重新授权；
     *   - 类型、常量、必填项、描述等任何一个值变了 → 指纹变，必须重新授权；
     *   - 解析不了（不是合法 JSON）→ 按原文参与哈希，绝不抛异常。
     */
    fun of(
        serverUrl: String,
        toolName: String,
        toolTitle: String?,
        toolDescription: String?,
        inputSchemaJson: String?,
    ): String {
        val material = buildString {
            append(VERSION).append('\n')
            // 每段都做 JSON 引号转义：字段里出现换行时也不会和下一段串味。
            append(JSONObject.quote(serverUrl.trim().lowercase())).append('\n')
            append(JSONObject.quote(toolName)).append('\n')
            append(JSONObject.quote(toolTitle ?: "")).append('\n')
            append(JSONObject.quote(toolDescription ?: "")).append('\n')
            append(canonicalize(inputSchemaJson))
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(material.toByteArray(Charsets.UTF_8))
        return digest.toHex().take(LENGTH)
    }

    /**
     * 把 schema 文本规范化成"键名排序 + 无多余空白"的形式。
     *
     * 为什么必须做：指纹的几个来源（服务器的 `tools/list` 原文、本机 `JSONObject`
     * 重新序列化、界面里存下来的副本）在**同一个对象**上产生的文本不保证逐字节相同。
     * 不做规范化的后果不是"哈希不一样"这么学术 —— 后果是用户每次调用都要重新授权，
     * 最后他会因为烦而把所有工具都授权一遍，这套机制就白做了。
     */
    private fun canonicalize(json: String?): String {
        if (json.isNullOrEmpty()) return ""
        val parsed = runCatching { JSONTokener(json).nextValue() }.getOrNull() ?: return json
        return write(parsed)
    }

    private fun write(value: Any?): String = when {
        value == null || value === JSONObject.NULL -> "null"
        value is JSONObject -> value.keys().asSequence().sorted()
            .joinToString(separator = ",", prefix = "{", postfix = "}") { key ->
                JSONObject.quote(key) + ":" + write(value.opt(key))
            }
        value is JSONArray -> (0 until value.length())
            .joinToString(separator = ",", prefix = "[", postfix = "]") { index -> write(value.opt(index)) }
        value is String -> JSONObject.quote(value)
        value is Number || value is Boolean -> value.toString()
        else -> JSONObject.quote(value.toString())
    }

    private fun ByteArray.toHex(): String = buildString(size * 2) {
        for (byte in this@toHex) {
            val value = byte.toInt() and 0xFF
            append(HEX[value ushr 4])
            append(HEX[value and 0x0F])
        }
    }

    private const val HEX = "0123456789abcdef"
}
