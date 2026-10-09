package com.androidmcphub.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
