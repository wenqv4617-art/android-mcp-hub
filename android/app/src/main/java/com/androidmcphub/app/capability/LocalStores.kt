package com.androidmcphub.app.capability

import android.content.Context
import java.io.File

/**
 * 本地数据文件的**唯一**登记处。
 *
 * 备忘 / 待办 / 提醒各自一个 JSON 文件，放在应用私有目录：
 *  * 不需要任何存储权限；
 *  * `allowBackup=false`，不会被云备份带走；
 *  * 卸载即删除。
 *
 * 为什么集中在这里：`ReminderReceiver` 在进程被拉起时也要能找到同一个文件；
 * 如果文件名散落在各处，改一次路径就会出现"列表能看到、到点不响"。
 */
object LocalStores {

    const val MEMOS_FILE = "memos.json"
    const val TODOS_FILE = "todos.json"
    const val REMINDERS_FILE = "reminders.json"
    const val CALENDAR_OWNED_FILE = "calendar_owned.json"

    fun memos(context: Context): JsonStore =
        FileJsonStore(File(context.filesDir, MEMOS_FILE), idPrefix = "memo_")

    fun todos(context: Context): JsonStore =
        FileJsonStore(File(context.filesDir, TODOS_FILE), idPrefix = "todo_")

    fun reminders(context: Context): JsonStore =
        FileJsonStore(File(context.filesDir, REMINDERS_FILE), idPrefix = "reminder_")

    fun calendarOwnership(context: Context): CalendarOwnershipStore =
        FileCalendarOwnershipStore(
            FileJsonStore(File(context.filesDir, CALENDAR_OWNED_FILE), idPrefix = "calevt_"),
        )
}
