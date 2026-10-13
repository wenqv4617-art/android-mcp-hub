package com.androidmcphub.app.hub

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Home
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.androidmcphub.app.ui.HubViewModel
import com.androidmcphub.app.ui.home.ConnectionDetailScreen
import com.androidmcphub.app.ui.home.HomeScreen
import com.androidmcphub.app.ui.mcp.McpViewModel
import com.androidmcphub.app.ui.services.McpServerEditScreen
import com.androidmcphub.app.ui.services.McpServerToolsScreen
import com.androidmcphub.app.ui.services.ServicesScreen
import com.androidmcphub.app.ui.settings.AdvancedSettingsScreen
import com.androidmcphub.app.ui.settings.ConnectionSettingsScreen
import com.androidmcphub.app.ui.settings.DiagnosticsScreen
import com.androidmcphub.app.ui.settings.SettingsScreen
import com.androidmcphub.app.ui.settings.TokenSettingsScreen
import com.androidmcphub.app.ui.tools.ToolDetailScreen
import com.androidmcphub.app.ui.tools.ToolsHelpScreen
import com.androidmcphub.app.ui.tools.ToolsScreen

/**
 * 应用骨架：底部四个标签 + 二级页面 + 一次性提示。
 *
 * Stage 2 的关键改动是**导航只有一个入口**：底栏和页面内快捷入口都调
 * [navigateToTopLevel]（见该函数的注释，里面写了真机上"点首页进了工具页"的根因）。
 * 这样返回栈的记账方式只有一套，`restoreState` 恢复出来的栈顶必然就是用户点的那页。
 *
 * 二级页面（工具详情、工具说明、连接详情、诊断、三个设置子页）不显示底栏：
 * `currentTab == null` 时 `bottomBar` 直接不渲染，而不是靠隐藏/占位去糊。
 */
@Composable
fun HubApp(viewModel: HubViewModel) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentTab = hubTabOf(backStackEntry?.destination?.route)
    val capabilitiesEnabled by viewModel.capabilitiesEnabled.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()

    // MCP 服务器列表是**另一个** ViewModel：它管的是"用户配了哪些第三方服务器、
    // 其中哪些工具被授权"，与连接/诊断状态互不相干。放在骨架层建一次，
    // 服务页与两个二级页共用同一个实例（否则添加完回到列表会看不到）。
    val mcpViewModel: McpViewModel = viewModel()
    val mcpMessage by mcpViewModel.message.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }

    // 一次性提示（保存成功 / 校验失败 / 令牌读不出来）：显示完立刻消费掉，
    // 否则旋转屏幕或切页回来会再弹一次。
    LaunchedEffect(message) {
        val text = message ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(text)
        viewModel.dismissMessage()
    }

    // 服务页的提示（保存成功 / 地址不合法 / 请求头被拒）走同一个 snackbar。
    LaunchedEffect(mcpMessage) {
        val text = mcpMessage ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(text)
        mcpViewModel.dismissMessage()
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            if (currentTab != null) {
                HubBottomBar(currentTab = currentTab, navController = navController)
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = HubTab.Home.route,
            // 系统栏内边距由 Scaffold 的 innerPadding 统一给；
            // 键盘（ime）不在 Scaffold 的默认 contentWindowInsets 里，
            // 而设置子页有一屏输入框 —— 不单独加 imePadding 会被键盘挡住。
            // 两者不叠加：imePadding 只吃键盘 inset。
            modifier = Modifier
                .padding(innerPadding)
                .imePadding(),
        ) {
            composable(HubTab.Home.route) {
                HomeScreen(
                    viewModel = viewModel,
                    onOpenTools = { navController.navigateToTopLevel(HubTab.Tools) },
                    onOpenDiagnostics = { navController.navigate(Routes.DIAGNOSTICS) },
                    onOpenConnectionDetail = { navController.navigate(Routes.CONNECTION_DETAIL) },
                )
            }

            composable(Routes.CONNECTION_DETAIL) {
                ConnectionDetailScreen(
                    viewModel = viewModel,
                    onBack = { navController.popBackStack() },
                    onOpenDiagnostics = { navController.navigate(Routes.DIAGNOSTICS) },
                    onOpenConnectionSettings = { navController.navigate(Routes.CONNECTION_SETTINGS) },
                )
            }

            composable(HubTab.Tools.route) {
                ToolsScreen(
                    capabilitiesEnabled = capabilitiesEnabled,
                    onOpenTool = { toolId -> navController.navigate(Routes.toolDetail(toolId)) },
                    onOpenHelp = { navController.navigate(Routes.TOOLS_HELP) },
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

            composable(Routes.TOOLS_HELP) {
                ToolsHelpScreen(
                    onBack = { navController.popBackStack() },
                    onOpenSettings = { navController.navigateToTopLevel(HubTab.Settings) },
                )
            }

            composable(HubTab.Services.route) {
                ServicesScreen(
                    viewModel = mcpViewModel,
                    capabilitiesEnabled = capabilitiesEnabled,
                    onOpenDiagnostics = { navController.navigate(Routes.DIAGNOSTICS) },
                    onOpenConnectionSettings = { navController.navigate(Routes.CONNECTION_SETTINGS) },
                    onAddServer = { navController.navigate(Routes.MCP_SERVER_NEW) },
                    onOpenServer = { serverId -> navController.navigate(Routes.mcpServerTools(serverId)) },
                )
            }

            composable(Routes.MCP_SERVER_NEW) {
                McpServerEditScreen(
                    viewModel = mcpViewModel,
                    serverId = null,
                    onBack = { navController.popBackStack() },
                    onSaved = { navController.popBackStack() },
                )
            }

            composable(
                route = Routes.MCP_SERVER_EDIT,
                arguments = listOf(navArgument(Routes.ARG_SERVER_ID) { type = NavType.StringType }),
            ) { entry ->
                McpServerEditScreen(
                    viewModel = mcpViewModel,
                    serverId = entry.arguments?.getString(Routes.ARG_SERVER_ID),
                    onBack = { navController.popBackStack() },
                    onSaved = { navController.popBackStack() },
                )
            }

            composable(
                route = Routes.MCP_SERVER_TOOLS,
                arguments = listOf(navArgument(Routes.ARG_SERVER_ID) { type = NavType.StringType }),
            ) { entry ->
                McpServerToolsScreen(
                    viewModel = mcpViewModel,
                    serverId = entry.arguments?.getString(Routes.ARG_SERVER_ID).orEmpty(),
                    onBack = { navController.popBackStack() },
                    onDeleted = { navController.popBackStack() },
                )
            }

            composable(HubTab.Settings.route) {
                SettingsScreen(
                    viewModel = viewModel,
                    onOpenConnectionSettings = { navController.navigate(Routes.CONNECTION_SETTINGS) },
                    onOpenAdvancedSettings = { navController.navigate(Routes.ADVANCED_SETTINGS) },
                    onOpenTokenSettings = { navController.navigate(Routes.TOKEN_SETTINGS) },
                    onOpenDiagnostics = { navController.navigate(Routes.DIAGNOSTICS) },
                )
            }

            composable(Routes.CONNECTION_SETTINGS) {
                ConnectionSettingsScreen(
                    viewModel = viewModel,
                    onBack = { navController.popBackStack() },
                    onOpenTokenSettings = { navController.navigate(Routes.TOKEN_SETTINGS) },
                )
            }

            composable(Routes.ADVANCED_SETTINGS) {
                AdvancedSettingsScreen(
                    viewModel = viewModel,
                    onBack = { navController.popBackStack() },
                )
            }

            composable(Routes.TOKEN_SETTINGS) {
                TokenSettingsScreen(
                    viewModel = viewModel,
                    onBack = { navController.popBackStack() },
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

    RequestNotificationPermissionOnce()
}

/**
 * 底部导航栏。
 *
 * 只有一处导航逻辑：已经在这一页就什么都不做（[topLevelNavDecision]），
 * 否则交给 [navigateToTopLevel]。图标固定 22dp —— 默认的 24dp 在四标签下
 * 会显得底栏很重，而选中态本身已经由 M3 的指示器表达。
 */
@Composable
private fun HubBottomBar(
    currentTab: HubTab,
    navController: NavHostController,
) {
    NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
        HubTab.entries.forEach { tab ->
            NavigationBarItem(
                selected = tab == currentTab,
                onClick = {
                    when (topLevelNavDecision(currentTab, tab)) {
                        TopLevelNavDecision.StayOnCurrentTab -> Unit
                        is TopLevelNavDecision.SwitchTo -> navController.navigateToTopLevel(tab)
                    }
                },
                icon = {
                    Icon(
                        imageVector = tab.icon,
                        contentDescription = null,
                        modifier = Modifier.size(22.dp),
                    )
                },
                label = { Text(tab.label) },
            )
        }
    }
}

/**
 * Requesting POST_NOTIFICATIONS once on first composition (Android 13+).
 *
 * Denial does not block anything: the foreground-service notification simply
 * won't show. 保持改版前的行为，不新增二次请求/引导弹窗。
 */
@Composable
private fun RequestNotificationPermissionOnce() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { },
    )
    LaunchedEffect(Unit) {
        launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}

private val HubTab.icon: ImageVector
    get() = when (this) {
        HubTab.Home -> Icons.Filled.Home
        HubTab.Tools -> Icons.Filled.Build
        HubTab.Services -> Icons.AutoMirrored.Filled.List
        HubTab.Settings -> Icons.Filled.Settings
    }
