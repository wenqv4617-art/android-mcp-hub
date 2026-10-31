package com.androidmcphub.app

import android.app.Application
import com.androidmcphub.app.capability.ReminderRescheduler
import com.androidmcphub.app.protocol.APP_VERSION
import com.androidmcphub.app.service.LinkBus

/**
 * 应用级入口。
 *
 * 这里**不**做任何「开机自动连接」之类的事：本应用连不连、什么时候连，
 * 全部由用户在前台点「连接」决定。这个类只做两件小事：
 *  1. 记一条启动日志，方便用户回看这次运行是从什么时候开始的；
 *  2. 把还没响过的本地提醒重新排程（见 [ReminderRescheduler]）——
 *     设备重启会清空 AlarmManager，而本应用不申请开机广播权限，
 *     所以只能在用户打开应用时补排一次。
 *
 * ⚠ 包名必须与 AndroidManifest.xml 里的 `android:name=".HubApplication"` 解析结果一致。
 * Gate 0.1 的 APK 在这里声明成了 `package com.androidmcphub.app.ui`（文件却在 app/ 目录下），
 * 编译出来的类是 `com.androidmcphub.app.ui.HubApplication`，而 Manifest 要的是
 * `com.androidmcphub.app.HubApplication` —— 系统在建进程时找不到类，直接抛
 * ClassNotFoundException，表现就是「点图标闪一下就回桌面，界面完全不出现」。
 * Kotlin 不要求目录与包名一致，编译器也不会为此报错，所以这个错误只能靠
 * 「拿真实产物比对」的自动检查来挡（`android/tools/verify_manifest_classes.py`）。
 */
class HubApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        LinkBus.log.info("应用进程已启动（版本 $APP_VERSION）")
        // 提醒补排不能把应用启动搞崩：排不上就只留一条日志，用户仍能在列表里看到提醒。
        try {
            val rearmed = ReminderRescheduler.reschedule(this)
            if (rearmed > 0) LinkBus.log.info("已重新排程 $rearmed 条提醒")
        } catch (t: Throwable) {
            LinkBus.log.warn("提醒补排失败（${t.javaClass.simpleName}）")
        }
    }
}
