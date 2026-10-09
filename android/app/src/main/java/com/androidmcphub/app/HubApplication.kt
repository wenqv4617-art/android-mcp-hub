package com.androidmcphub.app.ui

import android.app.Application
import com.androidmcphub.app.service.LinkBus

/**
 * 应用级入口（可选的骨架件）。
 *
 * 这里**不**做任何「开机自动连接」之类的事：本应用连不连、什么时候连，
 * 全部由用户在前台点「连接」决定。这个类目前只做一件小事 ——
 * 记一条启动日志，方便用户回看这次运行是从什么时候开始的。
 */
class HubApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        LinkBus.log.info("应用进程已启动（版本 0.1.0）")
    }
}
