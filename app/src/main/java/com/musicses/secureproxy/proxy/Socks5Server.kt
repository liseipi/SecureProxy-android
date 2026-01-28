package com.secureproxy.android.proxy

import android.util.Log
import com.secureproxy.android.model.ProxyConfig
import com.secureproxy.android.network.ConnectionManager
import kotlinx.coroutines.*
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer

/**
 * SOCKS5 代理服务器
 */
class Socks5Server(
    private val config: ProxyConfig,
    private val connectionManager: ConnectionManager,
    private val scope: CoroutineScope
) {
    companion object {
        private const val TAG = "Socks5Server"
        private const val SOCKS_VERSION = 5.toByte()
        private const val CMD_CONNECT = 1.toByte()
        private const val ATYP_IPV4 = 1.toByte()
        private const val ATYP_DOMAIN = 3.toByte()
        private const val ATYP_IPV6 = 4.toByte()
        private const val READ_TIMEOUT = 30000
        private const val BUFFER_SIZE = 8192
    }

    private var serverSocket: ServerSocket? = null
    @Volatile
    private var running = false
    private var acceptJob: Job? = null
    private val activeConnections = mutableSetOf<Job>()

    /**
     * 启动服务器
     */
    fun start() {
        if (running) return

        try {
            serverSocket = ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress("127.0.0.1", config.socksPort))
            }

            running = true
            Log.i(TAG, "SOCKS5 server listening on 127.0.0.1:${config.socksPort}")

            acceptJob = scope.launch {
                acceptConnections()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start SOCKS5 server: ${e.message}")
            throw e
        }
    }

    /**
     * 接受客户端连接
     */
    private suspend fun acceptConnections() = withContext(Dispatchers.IO) {
        while (running && isActive) {
            try {
                val clientSocket = serverSocket?.accept() ?: break

                val job = launch {
                    handleClient(clientSocket)
                }

                synchronized(activeConnections) {
                    activeConnections.add(job)
                }

                job.invokeOnCompletion {
                    synchronized(activeConnections) {
                        activeConnections.remove(job)
                    }
                }
            } catch (e: Exception) {
                if (running) {
                    Log.e(TAG, "Accept error: ${e.message}")
                }
            }
        }
    }

    /**
     * 处理客户端连接
     */
    private suspend fun handleClient(clientSocket: Socket) = withContext(Dispatchers.IO) {
        val connId = System.currentTimeMillis().toString(36)
        var ws: com.secureproxy.android.network.SecureWebSocket? = null

        try {
            clientSocket.apply {
                soTimeout = READ_TIMEOUT
                tcpNoDelay = true
                keepAlive = true
            }

            val input = clientSocket.getInputStream()
            val output = clientSocket.getOutputStream()

            // 1. 认证阶段
            val greeting = ByteArray(2)
            input.read(greeting)

            if (greeting[0] != SOCKS_VERSION) {
                Log.w(TAG, "[$connId] Invalid SOCKS version: ${greeting[0]}")
                return@withContext
            }

            val nMethods = greeting[1].toInt()
            val methods = ByteArray(nMethods)
            input.read(methods)

            // 回复: 无需认证
            output.write(byteArrayOf(SOCKS_VERSION, 0))
            output.flush()

            // 2. 请求阶段
            val request = ByteArray(4)
            input.read(request)

            if (request[0] != SOCKS_VERSION) {
                Log.w(TAG, "[$connId] Invalid version in request")
                return@withContext
            }

            if (request[1] != CMD_CONNECT) {
                sendReply(output, 7) // Command not supported
                return@withContext
            }

            // 解析目标地址
            val (host, port) = parseAddress(input, request[3])
            Log.d(TAG, "[$connId] SOCKS5 CONNECT: $host:$port")

            // 3. 连接到代理服务器
            ws = connectionManager.acquire()
            ws.sendConnect(host, port)

            // 4. 发送成功响应
            sendReply(output, 0, host, port)

            // 5. 开始数据转发
            pipe(clientSocket, ws, connId)

        } catch (e: Exception) {
            Log.e(TAG, "[$connId] Error: ${e.message}")
        } finally {
            try {
                clientSocket.close()
            } catch (ignored: Exception) {}

            if (ws != null) {
                connectionManager.release(ws)
            }
        }
    }

    /**
     * 解析目标地址
     */
    private fun parseAddress(input: java.io.InputStream, atyp: Byte): Pair<String, Int> {
        return when (atyp) {
            ATYP_IPV4 -> {
                val addr = ByteArray(4)
                input.read(addr)
                val port = readPort(input)
                val host = addr.joinToString(".") { (it.toInt() and 0xFF).toString() }
                Pair(host, port)
            }
            ATYP_DOMAIN -> {
                val len = input.read()
                val domain = ByteArray(len)
                input.read(domain)
                val port = readPort(input)
                Pair(String(domain), port)
            }
            ATYP_IPV6 -> {
                val addr = ByteArray(16)
                input.read(addr)
                val port = readPort(input)
                val parts = addr.toList().chunked(2).map { chunk ->
                    ((chunk[0].toInt() and 0xFF) shl 8) or (chunk[1].toInt() and 0xFF)
                }
                val host = parts.joinToString(":") { it.toString(16) }
                Pair(host, port)
            }
            else -> throw IllegalArgumentException("Unsupported address type: $atyp")
        }
    }

    /**
     * 读取端口
     */
    private fun readPort(input: java.io.InputStream): Int {
        val portBytes = ByteArray(2)
        input.read(portBytes)
        return ((portBytes[0].toInt() and 0xFF) shl 8) or (portBytes[1].toInt() and 0xFF)
    }

    /**
     * 发送 SOCKS5 响应
     */
    private fun sendReply(
        output: java.io.OutputStream,
        rep: Int,
        host: String = "0.0.0.0",
        port: Int = 0
    ) {
        val reply = ByteBuffer.allocate(10)
            .put(SOCKS_VERSION)
            .put(rep.toByte())
            .put(0)
            .put(ATYP_IPV4)
            .put(byteArrayOf(0, 0, 0, 0)) // Bind address
            .put(((port shr 8) and 0xFF).toByte())
            .put((port and 0xFF).toByte())
            .array()

        output.write(reply)
        output.flush()
    }

    /**
     * 数据转发
     */
    private suspend fun pipe(
        clientSocket: Socket,
        ws: com.secureproxy.android.network.SecureWebSocket,
        connId: String
    ) = coroutineScope {
        val input = clientSocket.getInputStream()
        val output = clientSocket.getOutputStream()

        // 客户端 -> 服务器
        val uploadJob = launch(Dispatchers.IO) {
            val buffer = ByteArray(BUFFER_SIZE)
            try {
                while (isActive && ws.isConnected() && !clientSocket.isClosed) {
                    val n = input.read(buffer)
                    if (n == -1) break

                    ws.send(buffer.copyOfRange(0, n))
                }
            } catch (e: Exception) {
                if (isActive) {
                    Log.d(TAG, "[$connId] Upload error: ${e.message}")
                }
            }
        }

        // 服务器 -> 客户端
        val downloadJob = launch(Dispatchers.IO) {
            try {
                while (isActive && ws.isConnected() && !clientSocket.isClosed) {
                    val data = ws.recv()
                    if (data.isEmpty()) break

                    output.write(data)
                    output.flush()
                }
            } catch (e: Exception) {
                if (isActive) {
                    Log.d(TAG, "[$connId] Download error: ${e.message}")
                }
            }
        }

        // 等待任一方向完成
        try {
            select<Unit> {
                uploadJob.onJoin {}
                downloadJob.onJoin {}
            }
        } finally {
            uploadJob.cancel()
            downloadJob.cancel()
        }
    }

    /**
     * 停止服务器
     */
    fun stop() {
        if (!running) return
        running = false

        acceptJob?.cancel()

        synchronized(activeConnections) {
            activeConnections.forEach { it.cancel() }
            activeConnections.clear()
        }

        try {
            serverSocket?.close()
        } catch (ignored: Exception) {}

        serverSocket = null
        Log.i(TAG, "SOCKS5 server stopped")
    }

    /**
     * 获取活动连接数
     */
    fun getActiveConnectionCount(): Int {
        synchronized(activeConnections) {
            return activeConnections.size
        }
    }
}