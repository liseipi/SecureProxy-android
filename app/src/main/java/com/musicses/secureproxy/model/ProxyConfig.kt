package com.musicses.secureproxy.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * 代理配置数据类
 */
@Parcelize
data class ProxyConfig(
    val sniHost: String,
    val proxyIp: String,
    val serverPort: Int,
    val path: String,
    val preSharedKey: String,
    val socksPort: Int = 1080,
    val httpPort: Int = 8080
) : Parcelable {

    fun validate(): Boolean {
        return sniHost.isNotBlank() &&
                proxyIp.isNotBlank() &&
                serverPort in 1..65535 &&
                path.isNotBlank() &&
                preSharedKey.length == 64 &&
                preSharedKey.matches(Regex("^[0-9a-fA-F]{64}$"))
    }
}