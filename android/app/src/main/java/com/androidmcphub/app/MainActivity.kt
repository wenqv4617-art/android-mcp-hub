package com.androidmcphub.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.androidmcphub.app.hub.HubApp
import com.androidmcphub.app.ui.HubViewModel
import com.androidmcphub.app.ui.theme.AndroidMcpHubTheme

/**
 * 唯一的 Activity。界面是 [HubApp]（底部四页 + 子页路由）。
 *
 * 这里不申请任何连接相关权限，也不自动发起连接：
 *  * 通知权限的请求在 [HubApp] 里用可感知的方式弹出（拒绝也不影响连接）；
 *  * 连接、保存、清令牌等动作由 [HubViewModel] 负责，Activity 只搭一层主题与容器。
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Android 15/16（targetSdk 35+）强制边到边，且不允许再用
        // windowOptOutEdgeToEdgeEnforcement 退出。显式调用它，让低版本也走同一套
        // 布局路径，避免"在 14 上正常、在 16 上被系统栏盖住"这种只在新机上出现的差异。
        // 系统栏内边距由 HubApp 的 Scaffold + NavHost(innerPadding) 统一处理，
        // 具体页面不再各自 safeDrawingPadding()（改版前只有一屏，可以那么写）。
        enableEdgeToEdge()
        setContent {
            AndroidMcpHubTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    HubApp(viewModel = viewModel())
                }
            }
        }
    }
}
