package com.secureproxy.android.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * 代理配置数据模型
 */
@Serializable
data class ProxyConfig(
    @SerialName("id")
    val id: String = UUID.randomUUID().toString(),
    
    @SerialName("name")
    val name: String,
    
    @SerialName("sni_host")
    val sniHost: String,
    
    @SerialName("proxy_ip")
    val proxyIP: String,
    
    @SerialName("path")
    val path: String,
    
    @SerialName("server_port")
    val serverPort: Int,
    
    @SerialName("socks_port")
    val socksPort: Int,
    
    @SerialName("http_port")
    val httpPort: Int,
    
    @SerialName("pre_shared_key")
    val preSharedKey: String
) {
    companion object {
        /**
         * 生成 64 位十六进制 PSK
         */
        fun generatePSK(): String {
            val random = java.security.SecureRandom()
            val bytes = ByteArray(32)
            random.nextBytes(bytes)
            return bytes.joinToString("") { "%02x".format(it) }
        }
        
        /**
         * 从 URL 字符串导入配置
         * 格式: wss://host:port/path?psk=xxx&socks=1080&http=1081&name=MyProxy&proxy_ip=1.1.1.1
         */
        fun fromUrl(urlString: String): ProxyConfig? {
            try {
                val url = java.net.URL(urlString)
                
                // 验证协议
                if (url.protocol != "wss") return null
                
                val host = url.host ?: return null
                val port = if (url.port > 0) url.port else 443
                val path = url.path.ifEmpty { "/ws" }
                
                // 解析查询参数
                val queryParams = url.query?.split("&")
                    ?.associate {
                        val parts = it.split("=", limit = 2)
                        parts[0] to (parts.getOrNull(1) ?: "")
                    } ?: emptyMap()
                
                // 提取 PSK（必需）
                val psk = queryParams["psk"] ?: return null
                if (psk.length != 64 || !psk.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) {
                    return null
                }
                
                // 提取其他参数
                val socksPort = queryParams["socks"]?.toIntOrNull() ?: 1080
                val httpPort = queryParams["http"]?.toIntOrNull() ?: 1081
                val name = queryParams["name"] ?: host
                val proxyIP = queryParams["proxy_ip"] ?: host
                
                return ProxyConfig(
                    name = name,
                    sniHost = host,
                    proxyIP = proxyIP,
                    path = path,
                    serverPort = port,
                    socksPort = socksPort,
                    httpPort = httpPort,
                    preSharedKey = psk
                )
            } catch (e: Exception) {
                return null
            }
        }
    }
    
    /**
     * 导出为 URL 字符串
     */
    fun toUrl(): String {
        val params = buildString {
            append("psk=$preSharedKey")
            append("&socks=$socksPort")
            append("&http=$httpPort")
            append("&name=${java.net.URLEncoder.encode(name, "UTF-8")}")
            append("&proxy_ip=${java.net.URLEncoder.encode(proxyIP, "UTF-8")}")
        }
        
        return "wss://$sniHost:$serverPort$path?$params"
    }
    
    /**
     * 是否使用 CDN 模式
     */
    val isCdnMode: Boolean
        get() = sniHost != proxyIP
    
    /**
     * 验证配置有效性
     */
    fun isValid(): Boolean {
        return name.isNotBlank() &&
                sniHost.isNotBlank() &&
                proxyIP.isNotBlank() &&
                path.isNotBlank() &&
                serverPort in 1..65535 &&
                socksPort in 1024..65535 &&
                httpPort in 1024..65535 &&
                preSharedKey.length == 64 &&
                preSharedKey.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }
    }
}

/**
 * 代理状态枚举
 */
enum class ProxyStatus {
    DISCONNECTED,
    CONNECTING,
    CONNECTED;
    
    val displayName: String
        get() = when (this) {
            DISCONNECTED -> "未连接"
            CONNECTING -> "连接中..."
            CONNECTED -> "已连接"
        }
}

/**
 * 流量统计
 */
data class TrafficStats(
    val uploadSpeed: Double = 0.0,  // KB/s
    val downloadSpeed: Double = 0.0,  // KB/s
    val totalUpload: Long = 0L,  // bytes
    val totalDownload: Long = 0L  // bytes
) {
    fun formatSpeed(kbps: Double): String {
        return when {
            kbps < 1 -> "%.0f B/s".format(kbps * 1024)
            kbps < 1024 -> "%.1f KB/s".format(kbps)
            else -> "%.2f MB/s".format(kbps / 1024)
        }
    }
    
    fun formatTotal(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
            bytes < 1024 * 1024 * 1024 -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
            else -> "%.2f GB".format(bytes / 1024.0 / 1024.0 / 1024.0)
        }
    }
}
