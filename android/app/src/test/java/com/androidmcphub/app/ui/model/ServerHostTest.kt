package com.androidmcphub.app.ui.model

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `serverHostLabel` 的单测。
 *
 * 存在的理由有两个：
 *  1. 首页/设置入口只显示主机名，长 URL 会撑开卡片 —— 这条"缩短"规则必须
 *     可预期，不能靠字符串截断（截断会把 `wss://` 前缀或端口切一半）。
 *  2. **安全相关**：URL 里可能带查询参数（用户手抄的地址偶尔会带 token），
 *     这个函数只返回 host:port，绝不把 path/query 带进界面。
 */
class ServerHostTest {

    @Test
    fun `空地址显示未填写`() {
        assertEquals("（未填写）", serverHostLabel(""))
        assertEquals("（未填写）", serverHostLabel("   "))
    }

    @Test
    fun `取出主机名并丢掉路径`() {
        assertEquals("example.com", serverHostLabel("wss://example.com/phone/ws"))
        assertEquals("example.com", serverHostLabel("wss://example.com"))
        assertEquals("hub.example.com", serverHostLabel("https://hub.example.com/phone/ws?a=1"))
    }

    @Test
    fun `带端口时保留端口`() {
        assertEquals("example.com:8443", serverHostLabel("wss://example.com:8443/phone/ws"))
        assertEquals("127.0.0.1:8765", serverHostLabel("http://127.0.0.1:8765/mcp"))
    }

    @Test
    fun `绝不把查询参数带进界面`() {
        val label = serverHostLabel("wss://example.com/phone/ws?token=should-not-appear")
        assertEquals("example.com", label)
        assertEquals(false, label.contains("token"))
        assertEquals(false, label.contains("?"))
    }

    @Test
    fun `去掉首尾空白`() {
        assertEquals("example.com", serverHostLabel("  wss://example.com/phone/ws  "))
    }

    @Test
    fun `解析不了就原样返回而不是猜`() {
        // 宁可显示得丑，也不显示一个错误的主机名（用户可能还在输入）。
        assertEquals("not a url", serverHostLabel("not a url"))
        assertEquals("example.com/phone/ws", serverHostLabel("example.com/phone/ws"))
    }
}
