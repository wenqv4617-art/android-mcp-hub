package com.androidmcphub.app.ui.components

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 时间格式化的唯一入口。
 *
 * 三个细节是刻意的：
 *  * 用 `java.time` 而不是 `SimpleDateFormat`：后者非线程安全，而且静态持有时
 *    lint 的 `ConstantLocale` 会报警（改版前 `HubScreen.kt:643` 就有一条）；
 *  * `Locale`/`ZoneId` 作为**函数默认参数**在调用时求值，不放在顶层 val 里，
 *    因此既不会吃 lint 警告，也不会在用户切换时区后继续用旧时区；
 *  * `ms <= 0` 返回破折号：0 是"没有数据"，不是 1970 年。
 */
fun formatTime(
    ms: Long,
    zone: ZoneId = ZoneId.systemDefault(),
    locale: Locale = Locale.getDefault(),
): String {
    if (ms <= 0L) return "—"
    return DateTimeFormatter.ofPattern("HH:mm:ss", locale)
        .withZone(zone)
        .format(Instant.ofEpochMilli(ms))
}

/** 带日期的完整时间（诊断页展示服务端时间戳用）。 */
fun formatDateTime(
    ms: Long,
    zone: ZoneId = ZoneId.systemDefault(),
    locale: Locale = Locale.getDefault(),
): String {
    if (ms <= 0L) return "—"
    return DateTimeFormatter.ofPattern("MM-dd HH:mm:ss", locale)
        .withZone(zone)
        .format(Instant.ofEpochMilli(ms))
}
