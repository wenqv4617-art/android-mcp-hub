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

/** 子页面路由。 */
object Routes {

    /** 工具详情：`tools/{toolId}`。 */
    const val TOOL_DETAIL = "tools/detail/{toolId}"

    /** 单个参数名的唯一来源，避免 `composable` 与 `navigate` 两处写错。 */
    const val ARG_TOOL_ID = "toolId"

    /** 添加 MCP 服务器（本轮是**结构预览**页，见 `AddServiceScreen`）。 */
    const val ADD_SERVICE = "services/add"

    /** 连接诊断（从设置页进入；首页的 ping 摘要也跳这里）。 */
    const val DIAGNOSTICS = "settings/diagnostics"

    fun toolDetail(toolId: String): String = "tools/detail/$toolId"
}
