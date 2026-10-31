package com.androidmcphub.app.core

/**
 * 当前默认网络的**粗分类**，只用于诊断展示。
 *
 * 刻意不暴露 SSID、运营商、IP 或链路速率：诊断页要能截图发出去，
 * 网络名称与 IP 都属于可能暴露用户位置的信息。
 */
enum class NetworkKind(val label: String) {
    NONE("无网络"),
    WIFI("Wi-Fi"),
    CELLULAR("移动数据"),
    ETHERNET("以太网"),
    VPN("VPN"),
    OTHER("其他"),
}

/**
 * 把 `NetworkCapabilities.hasTransport(...)` 的几个布尔值收敛成一个类别。
 *
 * VPN 优先：VPN 场景下底层通常同时是 Wi-Fi 或蜂窝，但用户真正关心的是
 * "当前流量是不是走 VPN"，所以先报 VPN。纯函数，JVM 可直接测。
 */
fun networkKind(
    wifi: Boolean = false,
    cellular: Boolean = false,
    ethernet: Boolean = false,
    vpn: Boolean = false,
    other: Boolean = false,
): NetworkKind = when {
    vpn -> NetworkKind.VPN
    ethernet -> NetworkKind.ETHERNET
    wifi -> NetworkKind.WIFI
    cellular -> NetworkKind.CELLULAR
    other -> NetworkKind.OTHER
    else -> NetworkKind.NONE
}
