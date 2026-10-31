package com.androidmcphub.app.service

/**
 * 「打开应用时要不要恢复上次的连接」的判定。
 *
 * 抽成纯函数，是因为这个判断的每一条都对应一种"恢复反而更糟"的场景：
 *  - `linkDesired == false`：用户上次主动断开，打开应用不能被自动连回去；
 *  - `serviceRunning == true`：服务已经拿着连接，重复投递 CONNECT 只会重建一次 socket；
 *  - `configValid == false`：地址/设备 ID 不完整，恢复只会立刻撞出同一个本地错误；
 *  - `tokenPresent == false`：令牌缺失或解不开，同理 —— 而且会把"存储坏了"
 *    伪装成一次普通的认证失败。
 *
 * 真正的后台保活不依赖这条路径（`START_STICKY` + 前台服务自己完成）；
 * 它只负责"进程被系统回收、用户重新打开应用"这一种情况。
 */
object LinkRestorePolicy {

    fun shouldRestore(
        linkDesired: Boolean,
        serviceRunning: Boolean,
        configValid: Boolean,
        tokenPresent: Boolean,
    ): Boolean = linkDesired && !serviceRunning && configValid && tokenPresent
}
