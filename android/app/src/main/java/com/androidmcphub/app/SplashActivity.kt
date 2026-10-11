package com.androidmcphub.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 启动页：整屏铺一张海报，停 [HOLD_MS] 毫秒后交接给 [MainActivity]。
 *
 * 为什么单独做一个 Activity，而不是在 [MainActivity] 里先显示一段时间：
 * 通知栏点进来时 [MainActivity] 会被直接拉起（PendingIntent 指向它），
 * 那种"回来看一眼连接状态"的场景不该再看一遍启动页。只有从桌面图标冷启动
 * （本 Activity 是 LAUNCHER 入口）才会经过这里。
 *
 * 这里不做任何业务、不申请权限、不碰连接：它只是把用户的第一帧交给海报。
 * 交接用系统的淡入淡出，交接完立即 finish，主界面返回键不会退回到启动页。
 */
class SplashActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 配置变更（旋转屏幕、切换深浅色）会重建 Activity：那种情况不该再停一次，
        // 直接交接，避免"转个屏又看 1 秒启动页"。
        if (savedInstanceState != null) {
            goToHub()
            return
        }

        // 与主界面一致：让海报铺到状态栏/手势条下面，不做二次内边距处理。
        enableEdgeToEdge()
        setContent {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    // 底色与 values/colors.xml 的 splash_background、以及 Android 12+
                    // 系统启动屏同色；海报若因比例被裁，露出的也是同色系而不是白屏。
                    .background(colorResource(R.color.splash_background)),
            ) {
                Image(
                    painter = painterResource(R.drawable.splash_poster),
                    // 纯装饰图：不给无障碍描述，避免 TalkBack 每次启动都念一遍。
                    contentDescription = null,
                    // Crop：海报是 9:16，屏幕更长时宁可裁掉两侧，也不留色块边框。
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        lifecycleScope.launch {
            delay(HOLD_MS)
            goToHub()
        }
    }

    @Suppress("DEPRECATION") // 交接动画用框架自带的淡入淡出，不值得为此引 androidx 转场库
    private fun goToHub() {
        startActivity(Intent(this, MainActivity::class.java))
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        finish()
    }

    private companion object {
        /**
         * 海报停留时长。够看清那张图，又不至于让"打开应用看连接状态"变慢；
         * 交接本身还有一段淡入，总时长约 1.15 秒。
         */
        const val HOLD_MS = 900L
    }
}
