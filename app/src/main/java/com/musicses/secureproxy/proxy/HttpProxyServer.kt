package com.musicses.secureproxy.proxy

import android.util.Log
import com.musicses.secureproxy.model.ProxyConfig
import com.musicses.secureproxy.network.ConnectionManager
import kotlinx.coroutines.*
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * HTTP 代理服务器
 */
class HttpProxyServer(
    private val config: ProxyConfig,
    private val connectionManager: ConnectionManager,
    private val scope: CoroutineScope
) {
    companion object {
        private const val TAG = "HttpProxyServer"
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
                bind(InetSocketAddress("127.0.0.1", config.httpPort))
            }

            running = true
            Log.i(TAG, "HTTP proxy server listening on 127.0.0.1:${config.httpPort}")

            acceptJob = scope.launch {
                acceptConnections()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start HTTP proxy server: ${e.message}")
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
        var ws: com.musicses.secureproxy.network.SecureWebSocket? = null

        try {
            clientSocket.apply {
                soTimeout = READ_TIMEOUT
                tcpNoDelay = true
                keepAlive = true
            }

            val input = clientSocket.getInputStream()
            val output = clientSocket.getOutputStream()
            val reader = BufferedReader(InputStreamReader(input))

            // 读取第一行请求
            val requestLine = reader.readLine() ?: return@withContext
            val parts = requestLine.split(" ")

            if (parts.size < 3) {
                Log.w(TAG, "[$connId] Invalid request line: $requestLine")
                return@withContext
            }

            val method = parts[0]
            val url = parts[1]

            // 处理 CONNECT 方法 (HTTPS)
            if (method.equals("CONNECT", ignoreCase = true)) {
                handleConnect(clientSocket, url, connId)
            } else {
                // 普通 HTTP 请求
                handleHttp(clientSocket, requestLine, reader, connId)
            }

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
     * 处理 CONNECT 请求 (HTTPS)
     */
    private suspend fun handleConnect(
        clientSocket: Socket,
        url: String,
        connId: String
    ) = withContext(Dispatchers.IO) {
        var ws: com.musicses.secureproxy.network.SecureWebSocket? = null

        try {
            // 解析目标地址
            val parts = url.split(":")
            val host = parts[0]
            val port = parts.getOrNull(1)?.toIntOrNull() ?: 443

            Log.d(TAG, "[$connId] HTTP CONNECT: $host:$port")

            // 连接到代理服务器
            ws = connectionManager.acquire()
            ws.sendConnect(host, port)

            // 发送成功响应
            val output = clientSocket.getOutputStream()
            output.write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray())
            output.flush()

            // 开始数据转发
            pipe(clientSocket, ws, connId)

        } catch (e: Exception) {
            Log.e(TAG, "[$connId] CONNECT error: ${e.message}")
            try {
                clientSocket.getOutputStream().write("HTTP/1.1 502 Bad Gateway\r\n\r\n".toByteArray())
            } catch (ignored: Exception) {}
        } finally {
            if (ws != null) {
                connectionManager.release(ws)
            }
        }
    }

    /**
     * 处理普通 HTTP 请求
     */
    private suspend fun handleHttp(
        clientSocket: Socket,
        requestLine: String,
        reader: BufferedReader,
        connId: String
    ) = withContext(Dispatchers.IO) {
        var ws: com.musicses.secureproxy.network.SecureWebSocket? = null

        try {
            // 读取所有请求头
            val headers = mutableListOf<String>()
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                if (line!!.isEmpty()) break
                headers.add(line!!)
            }

            // 解析目标主机
            val hostHeader = headers.find { it.startsWith("Host:", ignoreCase = true) }
            if (hostHeader == null) {
                Log.w(TAG, "[$connId] Missing Host header")
                return@withContext
            }

            val host = hostHeader.substring(5).trim()
            val port = if (host.contains(":")) {
                val parts = host.split(":")
                parts[1].toIntOrNull() ?: 80
            } else {
                80
            }

            val actualHost = if (host.contains(":")) host.split(":")[0] else host

            Log.d(TAG, "[$connId] HTTP request to $actualHost:$port")

            // 连接到代理服务器
            ws = connectionManager.acquire()
            ws.sendConnect(actualHost, port)

            // 转发请求
            val output = clientSocket.getOutputStream()
            val requestData = buildString {
                append(requestLine).append("\r\n")
                headers.forEach { append(it).append("\r\n") }
                append("\r\n")
            }.toByteArray()

            ws.send(requestData)

            // 数据转发
            pipe(clientSocket, ws, connId)

        } catch (e: Exception) {
            Log.e(TAG, "[$connId] HTTP error: ${e.message}")
        } finally {
            if (ws != null) {
                connectionManager.release(ws)
            }
        }
    }

    /**
     * 数据转发
     */
    private suspend fun pipe(
        clientSocket: Socket,
        ws: com.musicses.secureproxy.network.SecureWebSocket,
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
        Log.i(TAG, "HTTP proxy server stopped")
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