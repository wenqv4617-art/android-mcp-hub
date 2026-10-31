package com.androidmcphub.app.capability

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Phase 3A：应用使用时长。
 *
 * 两类断言：
 *  * [UsageStatsCapability.rangeOf] —— "今天/昨天/7 天"在设备时区里到底是哪一段，
 *    这是纯函数，跨天/跨时区最容易被写错；
 *  * 能力的输出形状与权限分支 —— 没授权时必须是 `permission_denied`，
 *    绝不能把系统返回的空列表说成"今天没用过应用"。
 */
class UsageStatsCapabilityTest {

    private val zone = ZoneId.of("Asia/Shanghai")
    private val now = LocalDateTime.of(2026, 3, 10, 15, 30).atZone(zone).toInstant().toEpochMilli()

    private fun midnight(date: LocalDate): Long = date.atStartOfDay(zone).toInstant().toEpochMilli()

    @Test
    fun `今天是从本地零点到此刻`() {
        val range = UsageStatsCapability.rangeOf("today", now, zone)
        assertEquals(midnight(LocalDate.of(2026, 3, 10)), range.startMs)
        assertEquals(now, range.endMs)
    }

    @Test
    fun `昨天是完整的上一个本地日`() {
        val range = UsageStatsCapability.rangeOf("yesterday", now, zone)
        assertEquals(midnight(LocalDate.of(2026, 3, 9)), range.startMs)
        assertEquals(midnight(LocalDate.of(2026, 3, 10)), range.endMs)
    }

    @Test
    fun `最近 7 天包含今天在内共 7 个本地日`() {
        val range = UsageStatsCapability.rangeOf("7d", now, zone)
        assertEquals(midnight(LocalDate.of(2026, 3, 4)), range.startMs)
        assertEquals(now, range.endMs)
        // 3/4 00:00 → 3/10 15:30 覆盖 3/4..3/10 共 7 个本地日。
        val todayMidnight = midnight(LocalDate.of(2026, 3, 10))
        assertEquals(6L, java.time.Duration.ofMillis(todayMidnight - range.startMs).toDays())
    }

    private class FakeUsageSource(
        var access: Boolean,
        private val apps: List<AppUsage> = emptyList(),
    ) : UsageStatsSource {
        var lastStart: Long? = null
        var lastEnd: Long? = null

        override fun hasAccess(): Boolean = access

        override fun query(startMs: Long, endMs: Long): UsageSnapshot {
            lastStart = startMs
            lastEnd = endMs
            return UsageSnapshot(apps, startMs, endMs)
        }
    }

    private fun source(access: Boolean = true): FakeUsageSource = FakeUsageSource(
        access = access,
        apps = listOf(
            AppUsage("com.example.long", "长用", 90_000L, now - 1_000, isSystemApp = false),
            AppUsage("com.android.systemui", "系统界面", 30_000L, now - 2_000, isSystemApp = true),
            AppUsage("com.example.short", "短用", 5_000L, null, isSystemApp = false),
        ),
    )

    private fun dispatcher(source: UsageStatsSource) = CapabilityDispatcher(
        capabilities = listOf(UsageStatsCapability(source, clock = { now }, zone = { zone })),
    )

    private fun call(dispatcher: CapabilityDispatcher, arguments: JSONObject = JSONObject()): JSONObject {
        val outcome = dispatcher.handle("tr-1", "device_get_usage_stats", arguments)
        return JSONObject(outcome.frame!!)
    }

    @Test
    fun `没有使用情况访问权限时返回 permission_denied，而不是空数据`() {
        val frame = call(dispatcher(source(access = false)))
        assertFalse(frame.getBoolean("ok"))
        assertEquals("permission_denied", frame.getJSONObject("error").getString("code"))
        // 错误信息必须告诉用户下一步去哪，而不是只说"失败"。
        assertTrue(frame.getJSONObject("error").getString("message").contains("使用情况访问"))
    }

    @Test
    fun `按使用时长降序、默认排除系统应用、过滤零时长`() {
        val frame = call(dispatcher(source()))
        assertTrue(frame.getBoolean("ok"))
        val result = frame.getJSONObject("result")
        assertEquals("today", result.getString("range"))
        assertEquals(2, result.getInt("returned"))
        assertEquals(2, result.getInt("total_apps"))
        assertEquals(95_000L, result.getLong("total_foreground_ms"))

        val apps = result.getJSONArray("apps")
        assertEquals("com.example.long", apps.getJSONObject(0).getString("package_name"))
        assertEquals(1, apps.getJSONObject(0).getLong("foreground_minutes"))
        assertEquals("com.example.short", apps.getJSONObject(1).getString("package_name"))
        // last_used_at_ms 读不到时是 JSON null，不是 0（0 会被误读成 1970 年）。
        assertTrue(apps.getJSONObject(1).isNull("last_used_at_ms"))
    }

    @Test
    fun `包含系统应用时保留它们`() {
        val frame = call(dispatcher(source()), JSONObject().put("include_system_apps", true))
        val result = frame.getJSONObject("result")
        assertEquals(3, result.getInt("returned"))
    }

    @Test
    fun `limit 截断时 has_more 为 true`() {
        val frame = call(dispatcher(source()), JSONObject().put("limit", 1))
        val result = frame.getJSONObject("result")
        assertEquals(1, result.getInt("returned"))
        assertTrue(result.getBoolean("has_more"))
    }

    @Test
    fun `range 是闭集，非法值直接拒绝`() {
        val frame = call(dispatcher(source()), JSONObject().put("range", "last_month"))
        assertFalse(frame.getBoolean("ok"))
        assertEquals("invalid_arguments", frame.getJSONObject("error").getString("code"))
    }

    @Test
    fun `查询区间与请求的 range 对应`() {
        val fake = source()
        call(dispatcher(fake), JSONObject().put("range", "yesterday"))
        assertEquals(midnight(LocalDate.of(2026, 3, 9)), fake.lastStart)
        assertEquals(midnight(LocalDate.of(2026, 3, 10)), fake.lastEnd)
    }
}
