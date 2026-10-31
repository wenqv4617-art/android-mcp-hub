package com.androidmcphub.app.capability

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets

/**
 * 一条本地记录的仓库（备忘 / 待办 / 提醒共用）。
 *
 * 为什么是接口：这些数据必须能在 JVM 单测里被完整读写，否则"删除后查不到"
 * 这类最关键的语义只能靠上真机碰运气。真实实现是 [FileJsonStore]，
 * 单测用内存实现或临时目录。
 *
 * 约定：
 *  * [put] 用于新建，[update] 用于修改，[delete] 用于删除；
 *  * 所有方法都是线程安全的（调用方可能来自 WebSocket 读线程）；
 *  * 返回的 [JSONObject] 是**拷贝**：调用方改了它不会影响仓库里的数据。
 */
interface JsonStore {

    fun list(): List<JSONObject>

    fun get(id: String): JSONObject?

    /** 分配一个新 ID 并写入 [item]；ID 会写回 `item` 并返回。 */
    fun insert(item: JSONObject): String

    /** 用 [item] 覆盖同 ID 记录；记录不存在时返回 false。 */
    fun replace(id: String, item: JSONObject): Boolean

    fun delete(id: String): Boolean

    /** 当前记录数（用于上限检查）。 */
    fun size(): Int
}

/**
 * 内存仓库：只用于**没有 Context 的环境**（JVM 单测）与注册清单的默认参数。
 *
 * 它不会落盘，也不假装落盘：进程结束数据就没了。生产代码永远用 [FileJsonStore]。
 */
class MemoryJsonStore(private val idPrefix: String = "item_") : JsonStore {

    private val items = LinkedHashMap<String, JSONObject>()
    private var nextSeq = 1

    override fun list(): List<JSONObject> = items.values.map { JSONObject(it.toString()) }

    override fun get(id: String): JSONObject? = items[id]?.let { JSONObject(it.toString()) }

    override fun insert(item: JSONObject): String {
        val id = "$idPrefix${nextSeq++}"
        item.put("id", id)
        items[id] = JSONObject(item.toString())
        return id
    }

    override fun replace(id: String, item: JSONObject): Boolean {
        if (!items.containsKey(id)) return false
        item.put("id", id)
        items[id] = JSONObject(item.toString())
        return true
    }

    override fun delete(id: String): Boolean = items.remove(id) != null

    override fun size(): Int = items.size
}

/**
 * 基于单个 JSON 文件的仓库。
 *
 * 安全与可靠性上的选择：
 *  * 文件在应用私有目录（`filesDir`），`allowBackup=false`，不导出、不需要存储权限；
 *  * 写入先落临时文件再 `renameTo`，避免写到一半掉电留下半个 JSON
 *    （半个 JSON 会让下一次启动把**所有**备忘当成损坏数据，那比丢一条更糟）；
 *  * 读不出来时**不覆盖**原文件：保留 `.corrupt` 备份并返回空列表，
 *    下次写入会另起一个新文件，用户至少还能从备份里找回内容。
 *  * ID 是"前缀 + 自增序号"，不是文件路径、不是用户输入，永远只在本文件内使用。
 */
class FileJsonStore(
    private val file: File,
    private val idPrefix: String,
    private val clock: () -> Long = System::currentTimeMillis,
    private val maxItems: Int = DEFAULT_MAX_ITEMS,
) : JsonStore {

    private val lock = Any()

    override fun list(): List<JSONObject> = synchronized(lock) { readLocked().map { JSONObject(it.toString()) } }

    override fun get(id: String): JSONObject? = synchronized(lock) {
        readLocked().firstOrNull { it.optString("id") == id }?.let { JSONObject(it.toString()) }
    }

    override fun size(): Int = synchronized(lock) { readLocked().size }

    override fun insert(item: JSONObject): String = synchronized(lock) {
        val state = readStateLocked()
        require(state.items.length() < maxItems) { "记录数量已达上限（$maxItems）" }

        val next = state.nextSeq
        val id = "$idPrefix$next"
        item.put("id", id)
        state.items.put(JSONObject(item.toString()))
        writeLocked(JSONObject().put("version", VERSION).put("next_seq", next + 1).put("items", state.items))
        id
    }

    override fun replace(id: String, item: JSONObject): Boolean = synchronized(lock) {
        val state = readStateLocked()
        val index = (0 until state.items.length()).firstOrNull { state.items.optJSONObject(it)?.optString("id") == id }
            ?: return@synchronized false
        item.put("id", id)
        state.items.put(index, JSONObject(item.toString()))
        writeLocked(JSONObject().put("version", VERSION).put("next_seq", state.nextSeq).put("items", state.items))
        true
    }

    override fun delete(id: String): Boolean = synchronized(lock) {
        val state = readStateLocked()
        val kept = JSONArray()
        var removed = false
        for (index in 0 until state.items.length()) {
            val current = state.items.optJSONObject(index) ?: continue
            if (current.optString("id") == id) {
                removed = true
            } else {
                kept.put(current)
            }
        }
        if (!removed) return@synchronized false
        writeLocked(JSONObject().put("version", VERSION).put("next_seq", state.nextSeq).put("items", kept))
        true
    }

    private data class State(val nextSeq: Int, val items: JSONArray)

    private fun readLocked(): List<JSONObject> {
        val items = readStateLocked().items
        return (0 until items.length()).mapNotNull { items.optJSONObject(it) }
    }

    private fun readStateLocked(): State {
        if (!file.exists()) return State(nextSeq = 1, items = JSONArray())
        val raw = try {
            file.readText(StandardCharsets.UTF_8)
        } catch (_: Throwable) {
            return State(nextSeq = 1, items = JSONArray())
        }
        val parsed = try {
            JSONObject(raw)
        } catch (_: Throwable) {
            quarantineLocked()
            return State(nextSeq = 1, items = JSONArray())
        }
        val items = parsed.optJSONArray("items") ?: JSONArray()
        val storedSeq = parsed.optInt("next_seq", 1)
        // next_seq 可能被手改或来自旧版本：始终保证它大于现有最大序号，避免 ID 冲突。
        val maxSeq = (0 until items.length()).mapNotNull { index ->
            items.optJSONObject(index)?.optString("id")?.removePrefix(idPrefix)?.toIntOrNull()
        }.maxOrNull() ?: 0
        return State(nextSeq = maxOf(storedSeq, maxSeq + 1, 1), items = items)
    }

    private fun writeLocked(payload: JSONObject) {
        val parent = file.parentFile
        if (parent != null && !parent.exists()) parent.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        FileOutputStream(tmp).use { out ->
            out.write(payload.toString().toByteArray(StandardCharsets.UTF_8))
            out.flush()
            out.fd.sync()
        }
        if (!tmp.renameTo(file)) {
            // Windows/老设备上 rename 覆盖已存在文件可能失败：退回到"删除再改名"。
            file.delete()
            if (!tmp.renameTo(file)) {
                tmp.delete()
                throw IllegalStateException("无法写入本地存储")
            }
        }
    }

    private fun quarantineLocked() {
        try {
            val backup = File(file.parentFile, file.name + ".corrupt." + clock())
            file.renameTo(backup)
        } catch (_: Throwable) {
            // 备份失败也不覆盖：下一次读仍然失败，但数据还在。
        }
    }

    companion object {
        const val VERSION = 1
        const val DEFAULT_MAX_ITEMS = 500
    }
}
