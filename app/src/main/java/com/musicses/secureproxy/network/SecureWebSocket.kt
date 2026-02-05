package com.musicses.secureproxy.network

import android.util.Log
import com.musicses.secureproxy.crypto.CryptoUtils
import com.musicses.secureproxy.model.ProxyConfig
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.*
import okio.ByteString
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit
import javax.net.ssl.*

/**
 * 安全 WebSocket 连接类 - 修复版
 *
 * 主要修复:
 * 1. 密钥派生的 salt 顺序与服务器保持一致
 * 2. 完整的握手超时和重试机制
 * 3. 更健壮的错误处理
 */
class SecureWebSocket(
    private val config: ProxyConfig,
    private val scope: CoroutineScope
) {
    companion object {
        private const val TAG = "SecureWebSocket"
        private const val PROTOCOL_VERSION = 1
        private const val CONNECT_TIMEOUT = 10000L
        private const val HANDSHAKE_TIMEOUT = 60000L  // 握手使用更长超时
        private const val MESSAGE_TIMEOUT = 30000L
        private const val KEEPALIVE_INTERVAL = 20000L
        private const val IDLE_TIMEOUT = 120000L
        private const val MAX_RETRIES = 3
        private const val HANDSHAKE_RETRIES = 2  // 握手失败重试次数
    }

    private var webSocket: WebSocket? = null
    private var sendKey: ByteArray? = null
    private var recvKey: ByteArray? = null

    @Volatile
    private var connected = false

    @Volatile
    private var destroyed = false

    private var lastActivity = System.currentTimeMillis()
    private var keepaliveJob: Job? = null

    private val messageChannel = Channel<ByteArray>(Channel.UNLIMITED)
    private val mutex = Mutex()
    private val client: OkHttpClient

    // 连接标识
    private val connId = (Math.random() * 1000000).toInt().toString(16).substring(0, 6)

    init {
        // 创建信任所有证书的 SSL 上下文
        val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) {}
            override fun checkServerTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) {}
            override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = arrayOf()
        })

        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, trustAllCerts, java.security.SecureRandom())

        client = OkHttpClient.Builder()
            .sslSocketFactory(sslContext.socketFactory, trustAllCerts[0] as X509TrustManager)
            .hostnameVerifier { _, _ -> true }
            .connectTimeout(CONNECT_TIMEOUT, TimeUnit.MILLISECONDS)
            .readTimeout(MESSAGE_TIMEOUT, TimeUnit.MILLISECONDS)
            .writeTimeout(MESSAGE_TIMEOUT, TimeUnit.MILLISECONDS)
            .pingInterval(KEEPALIVE_INTERVAL, TimeUnit.MILLISECONDS)
            .build()
    }

    /**
     * 连接到服务器(带重试)
     */
    suspend fun connect() = mutex.withLock {
        if (destroyed) throw IllegalStateException("WebSocket destroyed")
        if (connected) return@withLock

        repeat(MAX_RETRIES) { attempt ->
            try {
                attemptConnect()
                return@withLock
            } catch (e: Exception) {
                Log.w(TAG, "[$connId] Connection attempt ${attempt + 1}/$MAX_RETRIES failed: ${e.message}")
                if (attempt < MAX_RETRIES - 1) {
                    delay(1000L * (attempt + 1))
                } else {
                    throw e
                }
            }
        }
    }

    /**
     * 尝试连接
     */
    private suspend fun attemptConnect() = withContext(Dispatchers.IO) {
        val wsUrl = "wss://${config.proxyIp}:${config.serverPort}${config.path}"
        Log.d(TAG, "[$connId] Connecting to: $wsUrl")

        val request = Request.Builder()
            .url(wsUrl)
            .addHeader("Host", config.sniHost)
            .addHeader("User-Agent", "SecureProxy-Android/1.0")
            .addHeader("X-Protocol-Version", PROTOCOL_VERSION.toString())
            .build()

        val connectDeferred = CompletableDeferred<Unit>()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "[$connId] WebSocket opened")
                scope.launch {
                    try {
                        // 使用更长的超时进行握手
                        withTimeout(HANDSHAKE_TIMEOUT) {
                            setupKeysWithRetry()
                        }
                        connected = true
                        updateActivity()
                        setupKeepalive()
                        connectDeferred.complete(Unit)
                        Log.d(TAG, "[$connId] WebSocket authenticated successfully")
                    } catch (e: Exception) {
                        Log.e(TAG, "[$connId] Auth failed: ${e.message}")
                        connectDeferred.completeExceptionally(e)
                    }
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                updateActivity()
                scope.launch {
                    messageChannel.send(bytes.toByteArray())
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "[$connId] WebSocket error: ${t.message}")
                connected = false
                connectDeferred.completeExceptionally(t)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "[$connId] WebSocket closing: code=$code, reason=$reason")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "[$connId] WebSocket closed: code=$code")
                connected = false
            }
        })

        withTimeout(CONNECT_TIMEOUT + HANDSHAKE_TIMEOUT) {
            connectDeferred.await()
        }
    }

    /**
     * 设置密钥(带重试)
     */
    private suspend fun setupKeysWithRetry() {
        var lastError: Exception? = null

        repeat(HANDSHAKE_RETRIES) { attempt ->
            try {
                setupKeys()
                Log.d(TAG, "[$connId] Handshake succeeded on attempt ${attempt + 1}")
                return
            } catch (e: Exception) {
                lastError = e
                Log.w(TAG, "[$connId] Handshake attempt ${attempt + 1}/$HANDSHAKE_RETRIES failed: ${e.message}")
                if (attempt < HANDSHAKE_RETRIES - 1) {
                    delay(1000L)
                }
            }
        }

        throw lastError ?: Exception("Handshake failed")
    }

    /**
     * 设置密钥 - 关键修复: salt 顺序与服务器一致
     */
    private suspend fun setupKeys() {
        // 1. 密钥交换
        val clientPub = CryptoUtils.generateRandomBytes(32)
        sendRaw(clientPub)
        Log.d(TAG, "[$connId] Sent client public key (${clientPub.size} bytes)")

        val serverPub = recvMessageWithTimeout(HANDSHAKE_TIMEOUT)
        if (serverPub.size != 32) {
            throw IllegalStateException("Invalid server public key: ${serverPub.size} bytes")
        }
        Log.d(TAG, "[$connId] Received server public key (${serverPub.size} bytes)")

        // 2. 密钥派生 - 关键修复: salt = clientPub + serverPub (与服务器一致)
        val salt = clientPub + serverPub
        Log.d(TAG, "[$connId] Salt generated (${salt.size} bytes)")

        val keys = CryptoUtils.deriveKeys(
            CryptoUtils.hexToBytes(config.preSharedKey),
            salt
        )

        sendKey = keys.sendKey
        recvKey = keys.recvKey
        Log.d(TAG, "[$connId] Keys derived successfully")

        // 3. 认证
        val challenge = CryptoUtils.hmacSha256(sendKey!!, "auth".toByteArray())
        sendRaw(challenge)
        Log.d(TAG, "[$connId] Sent auth challenge")

        val response = recvMessageWithTimeout(HANDSHAKE_TIMEOUT)
        val expected = CryptoUtils.hmacSha256(recvKey!!, "ok".toByteArray())

        if (!CryptoUtils.constantTimeCompare(response, expected)) {
            throw SecurityException("Authentication failed: challenge mismatch")
        }

        Log.d(TAG, "[$connId] Authentication successful")
    }

    /**
     * 发送连接请求
     */
    suspend fun sendConnect(host: String, port: Int) {
        if (!connected || destroyed) throw IllegalStateException("Not connected")

        val address = "$host:$port".toByteArray()
        val addressLen = ByteBuffer.allocate(2)
            .putShort(address.size.toShort())
            .array()

        send(addressLen + address)
        Log.d(TAG, "[$connId] Sent CONNECT request to $host:$port")

        val response = recv()
        if (response.size != 1 || response[0] != 0.toByte()) {
            val errorCode = if (response.isNotEmpty()) response[0].toInt() else -1
            throw IllegalStateException("Connect failed with code: $errorCode")
        }

        Log.d(TAG, "[$connId] Connected to $host:$port")
    }

    /**
     * 发送加密数据
     */
    suspend fun send(data: ByteArray) {
        if (!connected || destroyed) throw IllegalStateException("Not connected")

        val encrypted = CryptoUtils.encrypt(sendKey!!, data)
        sendRaw(encrypted)
        updateActivity()
    }

    /**
     * 接收加密数据
     */
    suspend fun recv(): ByteArray {
        if (!connected || destroyed) throw IllegalStateException("Not connected")

        val encrypted = withTimeout(MESSAGE_TIMEOUT) {
            messageChannel.receive()
        }

        updateActivity()
        return CryptoUtils.decrypt(recvKey!!, encrypted)
    }

    /**
     * 发送原始数据
     */
    private fun sendRaw(data: ByteArray) {
        webSocket?.send(ByteString.of(*data))
            ?: throw IllegalStateException("WebSocket not connected")
    }

    /**
     * 接收消息(带超时)
     */
    private suspend fun recvMessageWithTimeout(timeout: Long): ByteArray {
        return withTimeout(timeout) {
            messageChannel.receive()
        }
    }

    /**
     * 更新活动时间
     */
    private fun updateActivity() {
        lastActivity = System.currentTimeMillis()
    }

    /**
     * 设置保活
     */
    private fun setupKeepalive() {
        keepaliveJob?.cancel()
        keepaliveJob = scope.launch {
            while (isActive && connected && !destroyed) {
                delay(KEEPALIVE_INTERVAL)

                val idleTime = System.currentTimeMillis() - lastActivity
                if (idleTime > IDLE_TIMEOUT) {
                    Log.w(TAG, "[$connId] Connection idle too long (${idleTime / 1000}s), closing")
                    close()
                    break
                }
            }
        }
    }

    /**
     * 关闭连接
     */
    fun close() {
        if (destroyed) return
        destroyed = true
        connected = false

        keepaliveJob?.cancel()
        webSocket?.close(1000, "Normal closure")
        webSocket = null

        sendKey = null
        recvKey = null

        messageChannel.close()

        Log.d(TAG, "[$connId] WebSocket closed")
    }

    /**
     * 是否已连接
     */
    fun isConnected(): Boolean = connected && !destroyed
}