package com.androidmcphub.app.hub

/**
 * 全部导航目的地。
 *
 * 为什么集中在一个文件里：`NavHost`、底部导航栏、各页面的跳转回调都引用同一份常量，
 * 任何一处写错字符串都会在编译期暴露（而不是运行时"点了没反应"）。
 * 这里**只有**路由定义，不含任何业务逻辑 —— 导航不该知道连接状态是什么。
 */
enum class HubTab(val route: String, val label: String) {
    Home("home", "首页"),
    Tools("tools", "工具"),
    Services("services", "服务"),
    Settings("settings", "设置"),
}

/** 二级页面路由。 */
object Routes {

    /** 连接详情：首页状态卡的"连接详情"入口。 */
    const val CONNECTION_DETAIL = "home/connection"

    /** 工具详情：`tools/detail/{toolId}`。 */
    const val TOOL_DETAIL = "tools/detail/{toolId}"

    /** 单个参数名的唯一来源，避免 `composable` 与 `navigate` 两处写错。 */
    const val ARG_TOOL_ID = "toolId"

    /** 工具目录的说明页（把原来堆在工具列表页里的开发说明搬到这里）。 */
    const val TOOLS_HELP = "tools/help"

    /** 连接诊断（设置 → 诊断与维护，首页 ping 摘要也跳这里）。 */
    const val DIAGNOSTICS = "settings/diagnostics"

    /** 服务器与设备配置（二级设置页）。 */
    const val CONNECTION_SETTINGS = "settings/connection"

    /** 高级连接参数：ping 超时、心跳、重连、抖动、并发与帧上限（二级设置页）。 */
    const val ADVANCED_SETTINGS = "settings/advanced"

    /** 设备令牌管理（二级设置页）。 */
    const val TOKEN_SETTINGS = "settings/token"

    fun toolDetail(toolId: String): String = "tools/detail/$toolId"
}
