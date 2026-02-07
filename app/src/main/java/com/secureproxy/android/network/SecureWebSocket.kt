package com.secureproxy.android.network

import io.ktor.client.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.websocket.*
import io.ktor.client.request.header
import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import com.secureproxy.android.data.model.ProxyConfig
import kotlin.time.Duration.Companion.milliseconds

private typealias YourJob = Job

/**
 * 安全 WebSocket 连接实现
 * 支持 AES-256-GCM 加密和 HKDF 密钥派生
 */
class SecureWebSocket(
    private val config: ProxyConfig,
    private val scope: CoroutineScope
) {
    private val client = HttpClient(OkHttp) {
        engine {
            config {
                connectTimeout(10, TimeUnit.SECONDS)
                readTimeout(30, TimeUnit.SECONDS)
                writeTimeout(30, TimeUnit.SECONDS)
            }
        }
        install(WebSockets) {
            pingInterval = 20_000.milliseconds
            maxFrameSize = Long.MAX_VALUE
        }
    }
    
    private var session: DefaultClientWebSocketSession? = null
    private var sendKey: ByteArray? = null
    private var recvKey: ByteArray? = null
    
    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()
    
    private val messageQueue = Channel<ByteArray>(Channel.UNLIMITED)
    private var receiveJob: YourJob? = null
    
    /**
     * 连接到服务器
     */
    suspend fun connect() {
        try {
            val actualHost = config.proxyIP
            val url = "wss://$actualHost:${config.serverPort}${config.path}"
            
            session = client.webSocketSession(url) {
                header("Host", config.sniHost)
                header("User-Agent", "SecureProxy-Android/1.0")
                header("X-Protocol-Version", "1")
            }
            
            setupKeys()
            _isConnected.value = true
            startReceiveLoop()
            
        } catch (e: Exception) {
            throw ConnectionException("连接失败: ${e.message}", e)
        }
    }
    
    /**
     * 设置加密密钥
     */
    private suspend fun setupKeys() {
        val session = session ?: throw ConnectionException("会话未建立")
        
        // 1. 发送客户端公钥
        val clientPub = ByteArray(32).apply { SecureRandom().nextBytes(this) }
        session.send(Frame.Binary(true, clientPub))
        
        // 2. 接收服务器公钥
        val serverPubFrame = session.incoming.receive() as? Frame.Binary
            ?: throw ConnectionException("未收到服务器公钥")
        val serverPub = serverPubFrame.data
        
        if (serverPub.size != 32) {
            throw ConnectionException("服务器公钥无效")
        }
        
        // 3. 派生密钥
        val salt = clientPub + serverPub
        val psk = config.preSharedKey.hexToByteArray()
        val (sendK, recvK) = deriveKeys(psk, salt)
        sendKey = sendK
        recvKey = recvK
        
        // 4. 认证握手
        val authMessage = "auth".toByteArray()
        val challenge = hmacSha256(sendK, authMessage)
        session.send(Frame.Binary(true, challenge))
        
        // 5. 验证服务器响应
        val authResponseFrame = session.incoming.receive() as? Frame.Binary
            ?: throw ConnectionException("认证失败")
        val authResponse = authResponseFrame.data
        
        val okMessage = "ok".toByteArray()
        val expected = hmacSha256(recvK, okMessage)
        
        if (!authResponse.contentEquals(expected)) {
            throw ConnectionException("服务器认证失败")
        }
    }
    
    /**
     * 发送 CONNECT 命令
     */
    suspend fun sendConnect(host: String, port: Int): Boolean {
        val sendK = sendKey ?: throw ConnectionException("密钥未设置")
        
        // 构造二进制命令: [0x01][host:port]
        val message = byteArrayOf(0x01) + "$host:$port".toByteArray()
        val encrypted = encrypt(sendK, message)
        
        session?.send(Frame.Binary(true, encrypted))
            ?: throw ConnectionException("会话已关闭")
        
        // 等待响应
        val response = withTimeoutOrNull(10_000) {
            messageQueue.receive()
        } ?: throw ConnectionException("连接超时")
        
        // 检查响应
        return when {
            response.isEmpty() -> throw ConnectionException("服务器无响应")
            response[0] == 0x00.toByte() -> true  // 连接成功
            response[0] == 0x01.toByte() -> {
                val error = if (response.size > 1) {
                    String(response, 1, response.size - 1)
                } else {
                    "连接被拒绝"
                }
                throw ConnectionException(error)
            }
            else -> {
                // 可能是目标服务器数据，放回队列
                messageQueue.trySend(response)
                true
            }
        }
    }
    
    /**
     * 发送数据
     */
    suspend fun send(data: ByteArray) {
        val sendK = sendKey ?: throw ConnectionException("密钥未设置")
        val encrypted = encrypt(sendK, data)
        session?.send(Frame.Binary(true, encrypted))
            ?: throw ConnectionException("会话已关闭")
    }
    
    /**
     * 接收数据
     */
    suspend fun receive(): ByteArray {
        return messageQueue.receive()
    }
    
    /**
     * 启动接收循环
     */
    private fun startReceiveLoop() {
        receiveJob?.cancel()
        receiveJob = scope.launch {
            val recvK = recvKey ?: return@launch
            val session = session ?: return@launch
            
            try {
                for (frame in session.incoming) {
                    if (frame is Frame.Binary) {
                        val plaintext = decrypt(recvK, frame.data)
                        messageQueue.send(plaintext)
                    }
                }
            } catch (e: Exception) {
                // 连接断开
                _isConnected.value = false
            }
        }
    }
    
    /**
     * 关闭连接
     */
    suspend fun close() {
        receiveJob?.cancel()
        session?.close()
        session = null
        sendKey = null
        recvKey = null
        _isConnected.value = false
        messageQueue.close()
    }
    
    /**
     * 派生加密密钥 (HKDF-SHA256)
     */
    private fun deriveKeys(sharedKey: ByteArray, salt: ByteArray): Pair<ByteArray, ByteArray> {
        val info = "secure-proxy-v1".toByteArray()
        
        // HKDF-Extract
        val prk = hmacSha256(salt, sharedKey)
        
        // HKDF-Expand (生成 64 字节)
        val t1 = hmacSha256(prk, info + byteArrayOf(0x01))
        val t2 = hmacSha256(prk, t1 + info + byteArrayOf(0x02))
        
        val okm = t1 + t2
        return Pair(okm.copyOfRange(0, 32), okm.copyOfRange(32, 64))
    }
    
    /**
     * AES-256-GCM 加密
     */
    private fun encrypt(key: ByteArray, plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val nonce = ByteArray(12).apply { SecureRandom().nextBytes(this) }
        val spec = GCMParameterSpec(128, nonce)
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), spec)
        
        val ciphertext = cipher.doFinal(plaintext)
        return nonce + ciphertext  // nonce(12) + ciphertext + tag(16)
    }
    
    /**
     * AES-256-GCM 解密
     */
    private fun decrypt(key: ByteArray, data: ByteArray): ByteArray {
        if (data.size < 28) throw IllegalArgumentException("数据过短")
        
        val nonce = data.copyOfRange(0, 12)
        val ciphertext = data.copyOfRange(12, data.size)
        
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val spec = GCMParameterSpec(128, nonce)
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), spec)
        
        return cipher.doFinal(ciphertext)
    }
    
    /**
     * HMAC-SHA256
     */
    private fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }
    
    /**
     * 十六进制字符串转字节数组
     */
    private fun String.hexToByteArray(): ByteArray {
        return chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }
}

/**
 * 连接异常
 */
class ConnectionException(message: String, cause: Throwable? = null) : Exception(message, cause)
