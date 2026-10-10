package com.androidmcphub.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.androidmcphub.app.ui.AndroidMcpHubTheme
import com.androidmcphub.app.ui.HubScreen

/**
 * 唯一的 Activity。界面只有一屏 [HubScreen]。
 *
 * 这里不申请任何连接相关权限，也不自动发起连接：
 * 通知权限的请求在 [HubScreen] 里由用户可感知的方式弹出（拒绝也不影响连接）。
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Android 15/16（targetSdk 35+）强制边到边，且不允许再用
        // windowOptOutEdgeToEdgeEnforcement 退出。显式调用它，让低版本也走同一套
        // 布局路径，避免"在 14 上正常、在 16 上被系统栏盖住"这种只在新机上出现的差异。
        // 内边距只在 HubScreen 根部应用一次（safeDrawingPadding），这里不重复处理。
        enableEdgeToEdge()
        setContent {
            AndroidMcpHubTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    HubScreen()
                }
            }
        }
    }
}
