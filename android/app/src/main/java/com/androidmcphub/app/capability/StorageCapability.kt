package com.androidmcphub.app.capability

import android.content.Context
import android.os.StatFs
import com.androidmcphub.app.protocol.ToolResultErrorCode
import org.json.JSONObject

/**
 * 一次存储读取的**原始**值：字节数 + 卷标识，尚未解释成对外字段。
 *
 * 与 [RawBatteryReading] / [RawNetworkReading] 同一个思路：系统调用只做"读数字"，
 * "已用怎么算、比例怎么取整、容量为 0 怎么办"都是纯函数，可以在 JVM 单测里跑。
 */
data class RawStorageReading(
    /** 卷标识（对外字段 `volume` 的值，英文短名，不随设备变化）。 */
    val volume: String,
    val totalBytes: Long,
    val availableBytes: Long,
)

/** 存储原始值的来源。真实实现是 [AndroidStorageSource]，单测里换成假实现。 */
interface StorageSource {
    fun read(): RawStorageReading
}

/**
 * 用**公开 API** 读内部存储卷的容量：`StatFs`。
 *
 * ── 统计的是哪个卷 ─────────────────────────────────────────────────────────
 * `context.filesDir`（应用私有目录）所在的**内部数据卷**。在 Android 上
 * 内部共享存储（`/sdcard` 这个模拟卷）与应用私有目录由同一个内部卷承载，
 * 所以这个数字就是"手机内部存储"的总量/可用量。
 *
 * 用 `filesDir` 而不是 `/sdcard` 路径的理由：它不需要任何权限、不受共享存储
 * 挂载状态影响、也不会因为作用域存储的规则而变化 —— 而 `StatFs` 只是问
 * 文件系统"你有多大"，不读任何文件。
 *
 * 刻意**不**做的事：不申请 `MANAGE_EXTERNAL_STORAGE`、不申请 `READ_MEDIA_*`、
 * 不申请传统整盘读写权限、不枚举用户文件、不扫描文件内容。
 *
 * 只持有 `applicationContext`，避免泄漏 Activity。
 */
class AndroidStorageSource(context: Context) : StorageSource {

    private val appContext = context.applicationContext

    override fun read(): RawStorageReading {
        val stat = StatFs(appContext.filesDir.absolutePath)
        return RawStorageReading(
            volume = VOLUME,
            totalBytes = stat.totalBytes,
            availableBytes = stat.availableBytes,
        )
    }

    companion object {
        /** 内部数据卷（同时承载应用私有目录与内部共享存储）。 */
        const val VOLUME = "internal_data"
    }
}

/**
 * Gate 2.5：`device_get_storage`。
 *
 * 只读、幂等、无副作用、不申请任何新权限、不读用户文件、不上传设备标识符，
 * 只返回 [OUTPUT_KEYS] 里那几个字段。
 *
 * ── 字段语义（严格定义）────────────────────────────────────────────────────
 *  * `volume`         —— 统计的是哪个卷（当前恒为 `"internal_data"`）；
 *  * `total_bytes`    —— 该卷总容量（字节）；
 *  * `available_bytes`—— 该卷当前可用容量（字节，含系统为自身保留的那部分之外的可写空间）；
 *  * `used_bytes`     —— `total - available`，即**该文件系统已被占用的空间**；
 *  * `usage_percent`  —— 已用占比，保留 1 位小数。
 *
 * ⚠ `used_bytes` **不代表"用户文件占用了多少空间"**：系统分区、应用数据、
 * 缓存、其他用户都算在里面。UI 与模型说明都必须按"文件系统已占用"来讲。
 *
 * 容量为 0 或不可访问时**明确失败**（`tool_unavailable` / `tool_failed`），
 * 不返回一串 0 让调用方以为"手机是空的"。
 *
 * 输出与 `worker/src/mcp/tools.ts` 的 `DEVICE_GET_STORAGE_TOOL.outputSchema` 一一对应。
 */
class StorageCapability(
    private val source: StorageSource,
) : Capability {

    override val name: String = NAME

    override val metadata: CapabilityMetadata = METADATA

    /** 刻意不接受任何参数：多一个键就多一条要审的路径。 */
    override val allowedArguments: Set<String> = emptySet()

    override fun execute(arguments: JSONObject): CapabilityResult {
        val reading = try {
            source.read()
        } catch (t: Throwable) {
            return CapabilityResult.Failure(
                code = ToolResultErrorCode.TOOL_FAILED,
                message = "读取存储容量失败（${t.javaClass.simpleName}）",
            )
        }

        // 容量为 0（或负数）说明这个卷读不到真实容量：如实报"不可用"，
        // 而不是回 total=0 / used=0 —— 后者看起来像"设备是空的"。
        val used = usedBytesOf(reading.totalBytes, reading.availableBytes)
            ?: return CapabilityResult.Failure(
                code = ToolResultErrorCode.TOOL_UNAVAILABLE,
                message = "存储卷不可用：系统报告的容量为 0",
            )

        val payload = JSONObject()
            .put("volume", reading.volume)
            .put("total_bytes", reading.totalBytes)
            .put("available_bytes", availableBytesOf(reading.totalBytes, reading.availableBytes))
            .put("used_bytes", used)
            .put("usage_percent", usagePercentOf(reading.totalBytes, reading.availableBytes) ?: JSONObject.NULL)

        return CapabilityResult.Success(payload)
    }

    companion object {

        const val NAME = "device_get_storage"

        /** 回给服务端的字段集合（测试与文档共用一份）。 */
        val OUTPUT_KEYS: Set<String> = setOf(
            "volume",
            "total_bytes",
            "available_bytes",
            "used_bytes",
            "usage_percent",
        )

        /**
         * 可用字节：夹在 `[0, total]` 之间。
         *
         * 为什么要夹：文件系统在极端情况下可能报出 `available > total`（保留块、
         * 计数竞态）。把它原样发出去，会让"已用 = 总 - 可用"变成负数，
         * 调用方只能自己猜该怎么处理 —— 不如在这里收敛成一个自洽的数。
         */
        fun availableBytesOf(totalBytes: Long, availableBytes: Long): Long =
            if (totalBytes <= 0L) 0L else availableBytes.coerceIn(0L, totalBytes)

        /** 已用字节 = 总 - 可用；总容量不可用时为 null（调用方按失败处理）。 */
        fun usedBytesOf(totalBytes: Long, availableBytes: Long): Long? =
            if (totalBytes <= 0L) null else totalBytes - availableBytesOf(totalBytes, availableBytes)

        /**
         * 已用占比（百分数，保留 1 位小数）。总容量不可用时为 null。
         *
         * 用 `Math.round(x * 10) / 10.0` 而不是字符串格式化：结果是**数字**，
         * 服务端与模型可以直接比较大小，不会被 `"51.2%"` 这种带单位的字符串绊住。
         */
        fun usagePercentOf(totalBytes: Long, availableBytes: Long): Double? {
            val used = usedBytesOf(totalBytes, availableBytes) ?: return null
            if (totalBytes <= 0L) return null
            val raw = used.toDouble() * 100.0 / totalBytes.toDouble()
            return Math.round(raw * 10.0) / 10.0
        }

        val METADATA: CapabilityMetadata = CapabilityMetadata(
            name = NAME,
            displayName = "存储空间",
            description = "查询手机内部存储（含内部共享存储）的总容量、可用容量与已用比例。" +
                "只读，不扫描文件、不申请任何文件访问权限。",
            category = CapabilityCategory.Storage,
            readOnly = true,
        )
    }
}
