package com.androidmcphub.app.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 打开应用时恢复连接的判定：每一条"不恢复"都对应一种恢复反而更糟的场景。 */
class LinkRestorePolicyTest {

    @Test
    fun `用户想连接、服务没在跑、配置与令牌都在时才恢复`() {
        assertTrue(
            LinkRestorePolicy.shouldRestore(
                linkDesired = true,
                serviceRunning = false,
                configValid = true,
                tokenPresent = true,
            ),
        )
    }

    @Test
    fun `用户上次主动断开过就不恢复`() {
        assertFalse(
            LinkRestorePolicy.shouldRestore(
                linkDesired = false,
                serviceRunning = false,
                configValid = true,
                tokenPresent = true,
            ),
        )
    }

    @Test
    fun `服务已经拿着连接时不重复投递连接`() {
        assertFalse(
            LinkRestorePolicy.shouldRestore(
                linkDesired = true,
                serviceRunning = true,
                configValid = true,
                tokenPresent = true,
            ),
        )
    }

    @Test
    fun `配置不完整或令牌不可用时不做注定失败的恢复`() {
        assertFalse(
            LinkRestorePolicy.shouldRestore(
                linkDesired = true,
                serviceRunning = false,
                configValid = false,
                tokenPresent = true,
            ),
        )
        assertFalse(
            LinkRestorePolicy.shouldRestore(
                linkDesired = true,
                serviceRunning = false,
                configValid = true,
                tokenPresent = false,
            ),
        )
    }
}
