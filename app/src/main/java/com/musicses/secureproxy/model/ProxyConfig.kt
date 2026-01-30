package com.musicses.secureproxy.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * 代理配置数据类 - VPN 纯净版
 * 移除了 socksPort 和 httpPort，因为 VPN 模式不需要
 */
@Parcelize
data class ProxyConfig(
    val sniHost: String,
    val proxyIp: String,
    val serverPort: Int,
    val path: String,
    val preSharedKey: String
) : Parcelable {

    /**
     * 验证配置有效性
     */
    fun validate(): Boolean {
        return sniHost.isNotBlank() &&
                proxyIp.isNotBlank() &&
                serverPort in 1..65535 &&
                path.isNotBlank() &&
                preSharedKey.length == 64 &&
                preSharedKey.matches(Regex("^[0-9a-fA-F]{64}$"))
    }

    /**
     * 获取 WebSocket URL
     */
    fun getWebSocketUrl(): String {
        return "wss://$proxyIp:$serverPort$path"
    }

    /**
     * 验证 IP 地址格式
     */
    private fun isValidIp(ip: String): Boolean {
        val ipv4Regex = Regex("^((25[0-5]|(2[0-4]|1\\d|[1-9]|)\\d)\\.?\\b){4}$")
        return ipv4Regex.matches(ip)
    }

    /**
     * 获取详细验证错误信息
     */
    fun getValidationError(): String? {
        return when {
            sniHost.isBlank() -> "SNI 主机不能为空"
            proxyIp.isBlank() -> "代理 IP 不能为空"
            !isValidIp(proxyIp) -> "代理 IP 格式不正确"
            serverPort !in 1..65535 -> "服务器端口必须在 1-65535 之间"
            path.isBlank() -> "路径不能为空"
            !path.startsWith("/") -> "路径必须以 / 开头"
            preSharedKey.length != 64 -> "PSK 必须是 64 位十六进制字符"
            !preSharedKey.matches(Regex("^[0-9a-fA-F]{64}$")) -> "PSK 只能包含十六进制字符 (0-9, a-f)"
            else -> null
        }
    }

    /**
     * 获取配置摘要（隐藏敏感信息）
     */
    fun getSummary(): String {
        return """
            SNI Host: $sniHost
            Proxy IP: $proxyIp
            Server Port: $serverPort
            Path: $path
            PSK: ${preSharedKey.take(8)}... (隐藏)
        """.trimIndent()
    }

    companion object {
        /**
         * 创建默认配置用于测试
         */
        fun createDefault(): ProxyConfig {
            return ProxyConfig(
                sniHost = "example.com",
                proxyIp = "1.2.3.4",
                serverPort = 2053,
                path = "/v1",
                preSharedKey = "0".repeat(64)
            )
        }

        /**
         * 从 JSON 字符串解析
         */
        fun fromJson(json: String): ProxyConfig? {
            // 简单的 JSON 解析，实际项目可以使用 Gson 或 kotlinx.serialization
            return try {
                val sniHostMatch = Regex(""""sniHost":"([^"]+)"""").find(json)
                val proxyIpMatch = Regex(""""proxyIp":"([^"]+)"""").find(json)
                val serverPortMatch = Regex(""""serverPort":(\d+)""").find(json)
                val pathMatch = Regex(""""path":"([^"]+)"""").find(json)
                val pskMatch = Regex(""""preSharedKey":"([^"]+)"""").find(json)

                if (sniHostMatch != null && proxyIpMatch != null &&
                    serverPortMatch != null && pathMatch != null && pskMatch != null) {
                    ProxyConfig(
                        sniHost = sniHostMatch.groupValues[1],
                        proxyIp = proxyIpMatch.groupValues[1],
                        serverPort = serverPortMatch.groupValues[1].toInt(),
                        path = pathMatch.groupValues[1],
                        preSharedKey = pskMatch.groupValues[1]
                    )
                } else {
                    null
                }
            } catch (e: Exception) {
                null
            }
        }
    }
}