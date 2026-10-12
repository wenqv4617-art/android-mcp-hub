package com.androidmcphub.app.hub

import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController

/**
 * 顶层标签导航的**唯一**入口。
 *
 * ## 为什么需要这个文件（Stage 2 导航缺陷的真实原因）
 *
 * Stage 1 里存在两套写法：
 *  * 首页快捷入口用裸 `navController.navigate(HubTab.Tools.route)`；
 *  * 底部导航用 `popUpTo(起始页) { saveState = true } + launchSingleTop + restoreState`。
 *
 * 这两种写法对返回栈的**记账方式不同**。裸 `navigate` 压上去的那个标签页
 * 没有走 `saveState` 路径，于是它和底部导航保存下来的那份栈不在同一本账上：
 * 之后任何一次 `popUpTo(起始页) { saveState = true }` 都会把当前栈（包括那个
 * 裸压上去的页面）存成"某一页的已保存状态"，再 `restoreState` 时被恢复出来的
 * 栈顶就不一定是用户点的那一页 —— 表现正是真机上看到的
 * 「点首页却进了工具页」「首页回不去」，以及底栏高亮与当前页面对不上。
 *
 * 修法是让两条路径**复用同一个函数**（而不是删掉 `restoreState`：
 * 删掉会把滚动位置与筛选状态一起丢掉，属于用新问题换旧问题）。
 */
fun NavHostController.navigateToTopLevel(tab: HubTab) {
    navigate(tab.route) {
        // 把返回栈压回起始页，避免来回点标签把栈堆成一长串；
        // 同时把被弹掉的页面状态存起来，供 restoreState 恢复。
        popUpTo(graph.findStartDestination().id) { saveState = true }
        // 同一页已经在栈顶时不再压第二份。
        launchSingleTop = true
        // 恢复该标签上次离开时的滚动位置与页面栈。
        restoreState = true
    }
}

/** 路由 → 顶层标签。非顶层路由（工具详情、诊断…）返回 `null`。 */
fun hubTabOf(route: String?): HubTab? = HubTab.entries.firstOrNull { it.route == route }

/** 点击一个顶层标签时应该发生什么。 */
sealed interface TopLevelNavDecision {

    /** 已经在这一页：不导航。重复导航会让页面重建、状态抖动。 */
    data object StayOnCurrentTab : TopLevelNavDecision

    /** 切到目标标签。 */
    data class SwitchTo(val target: HubTab) : TopLevelNavDecision
}

/**
 * 顶层导航决策 —— 纯函数，没有任何 Android 依赖，因此可以被 JVM 单测直接覆盖。
 *
 * 之所以把"是否要导航"从 `NavigationBarItem.onClick` 里提出来：
 * 这条规则同时被底栏和页面内快捷入口使用，写在两处就一定会漂移。
 */
fun topLevelNavDecision(currentTab: HubTab?, target: HubTab): TopLevelNavDecision =
    if (currentTab == target) {
        TopLevelNavDecision.StayOnCurrentTab
    } else {
        TopLevelNavDecision.SwitchTo(target)
    }
