package com.androidmcphub.app.hub

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.androidmcphub.app.ui.HubViewModel
import com.androidmcphub.app.ui.home.HomeScreen
import com.androidmcphub.app.ui.services.AddServiceScreen
import com.androidmcphub.app.ui.services.ServicesScreen
import com.androidmcphub.app.ui.settings.DiagnosticsScreen
import com.androidmcphub.app.ui.settings.SettingsScreen
import com.androidmcphub.app.ui.tools.ToolDetailScreen
import com.androidmcphub.app.ui.tools.ToolsScreen

/**
 * 应用唯一的 Composable 入口：底部四页 + 子页路由。
 *
 * 职责边界（刻意保持得比 HubScreen 时代更窄）：
 *  * 这里**只**做导航、系统栏内边距、一次性消息（Snackbar）和通知权限；
 *  * 连接、保存、清令牌等业务动作全部留在 [HubViewModel]，页面通过方法引用调用它；
 *  * 页面之间不直接互相调用，一律走路由 —— 这样返回键行为和状态保留由
 *    Navigation Compose 统一负责，而不是每个页面各写一套布尔开关。
 */
@Composable
fun HubApp(viewModel: HubViewModel) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentTab = HubTab.entries.firstOrNull { it.route == backStackEntry?.destination?.route }

    val capabilitiesEnabled by viewModel.capabilitiesEnabled.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    // 一次性消息（保存成功、令牌无法解密…）统一走 Snackbar：
    // 看完即 dismiss，不会像原来那样把消息永久留在首页正文里。
    LaunchedEffect(message) {
        val text = message ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(text)
        viewModel.dismissMessage()
    }

    RequestNotificationPermissionOnce()

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            if (currentTab != null) {
                HubBottomBar(currentTab, navController)
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = HubTab.Home.route,
            // 系统栏内边距只在这里应用一次（Scaffold 给的 innerPadding）。
            // 另外单独补 imePadding：Scaffold 默认的 contentWindowInsets 不含 ime，
            // 设置页有 10 个输入框，键盘弹出时必须把内容顶起来，否则输入框会被挡住。
            modifier = Modifier
                .padding(innerPadding)
                .imePadding(),
        ) {
            composable(HubTab.Home.route) {
                HomeScreen(
                    viewModel = viewModel,
                    onOpenTools = { navController.navigate(HubTab.Tools.route) },
                    onOpenServices = { navController.navigate(HubTab.Services.route) },
                    onOpenDiagnostics = { navController.navigate(Routes.DIAGNOSTICS) },
                    onOpenSettings = { navController.navigate(HubTab.Settings.route) },
                )
            }
            composable(HubTab.Tools.route) {
                ToolsScreen(
                    capabilitiesEnabled = capabilitiesEnabled,
                    onOpenTool = { toolId -> navController.navigate(Routes.toolDetail(toolId)) },
                )
            }
            composable(HubTab.Services.route) {
                ServicesScreen(
                    onAddService = { navController.navigate(Routes.ADD_SERVICE) },
                    onOpenSettings = { navController.navigate(HubTab.Settings.route) },
                )
            }
            composable(HubTab.Settings.route) {
                SettingsScreen(
                    viewModel = viewModel,
                    onOpenDiagnostics = { navController.navigate(Routes.DIAGNOSTICS) },
                )
            }
            composable(
                route = Routes.TOOL_DETAIL,
                arguments = listOf(navArgument(Routes.ARG_TOOL_ID) { type = NavType.StringType }),
            ) { entry ->
                ToolDetailScreen(
                    toolId = entry.arguments?.getString(Routes.ARG_TOOL_ID).orEmpty(),
                    capabilitiesEnabled = capabilitiesEnabled,
                    onBack = { navController.popBackStack() },
                )
            }
            composable(Routes.ADD_SERVICE) {
                AddServiceScreen(
                    onBack = { navController.popBackStack() },
                    onOpenTools = { navController.navigate(HubTab.Tools.route) },
                )
            }
            composable(Routes.DIAGNOSTICS) {
                DiagnosticsScreen(
                    viewModel = viewModel,
                    onBack = { navController.popBackStack() },
                )
            }
        }
    }
}

/**
 * 底部导航。
 *
 * 三段 `navigate` 配置是"状态保留"的关键：
 *  * `popUpTo(起始页)` 把返回栈压到只剩首页，避免来回点工具/服务把栈堆成一长串；
 *  * `launchSingleTop` 防止重复点同一页再压一份；
 *  * `restoreState = true` 让每个标签页记住自己的滚动位置与页面栈。
 */
@Composable
private fun HubBottomBar(currentTab: HubTab, navController: NavHostController) {
    NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
        HubTab.entries.forEach { tab ->
            NavigationBarItem(
                selected = tab == currentTab,
                onClick = {
                    if (tab != currentTab) {
                        navController.navigate(tab.route) {
                            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    }
                },
                icon = { Icon(imageVector = tab.icon, contentDescription = null) },
                label = { Text(tab.label) },
            )
        }
    }
}

private val HubTab.icon: ImageVector
    get() = when (this) {
        HubTab.Home -> Icons.Filled.Home
        HubTab.Tools -> Icons.Filled.Build
        HubTab.Services -> Icons.AutoMirrored.Filled.List
        HubTab.Settings -> Icons.Filled.Settings
    }

/**
 * 通知权限：前台服务在没有通知渠道权限时无法展示常驻通知（Android 13+）。
 * 行为与改版前一致 —— 每次进程启动请求一次，被拒绝也不阻塞任何功能。
 */
@Composable
private fun RequestNotificationPermissionOnce() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { /* 拒绝也不影响连接，只是没有常驻通知 */ },
    )
    LaunchedEffect(Unit) { launcher.launch(Manifest.permission.POST_NOTIFICATIONS) }
}
