package com.musicses.secureproxy.network

import android.util.Log
import com.musicses.secureproxy.model.ProxyConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * WebSocket 连接池管理器
 */
class ConnectionManager(
    private val config: ProxyConfig,
    private val scope: CoroutineScope
) {
    companion object {
        private const val TAG = "ConnectionManager"
        private const val POOL_SIZE = 5
    }

    private val availableConnections = ConcurrentLinkedQueue<SecureWebSocket>()
    private val busyConnections = mutableSetOf<SecureWebSocket>()
    private val mutex = Mutex()

    @Volatile
    private var initialized = false

    /**
     * 初始化连接池
     */
    suspend fun initialize() = mutex.withLock {
        if (initialized) return@withLock

        Log.d(TAG, "Initializing connection pool with $POOL_SIZE connections")

        // 预创建连接
        for (i in 0 until POOL_SIZE) {
            try {
                val ws = createConnection()
                availableConnections.offer(ws)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to create initial connection $i: ${e.message}")
            }
        }

        initialized = true
        Log.d(TAG, "Connection pool initialized with ${availableConnections.size} connections")
    }

    /**
     * 创建新连接
     */
    private suspend fun createConnection(): SecureWebSocket {
        val ws = SecureWebSocket(config, scope)
        ws.connect()
        return ws
    }

    /**
     * 获取连接
     */
    suspend fun acquire(): SecureWebSocket = mutex.withLock {
        if (!initialized) {
            initialize()
        }

        // 尝试从池中获取
        var ws = availableConnections.poll()

        // 如果没有可用连接或连接已断开,创建新连接
        if (ws == null || !ws.isConnected()) {
            ws?.close()
            ws = createConnection()
        }

        busyConnections.add(ws)
        Log.d(TAG, "Acquired connection (pool: ${availableConnections.size}, busy: ${busyConnections.size})")

        return@withLock ws
    }

    /**
     * 释放连接
     */
    suspend fun release(ws: SecureWebSocket) = mutex.withLock {
        busyConnections.remove(ws)

        // 如果连接仍然有效且池未满,放回池中
        if (ws.isConnected() && availableConnections.size < POOL_SIZE) {
            availableConnections.offer(ws)
            Log.d(TAG, "Released connection to pool (pool: ${availableConnections.size}, busy: ${busyConnections.size})")
        } else {
            ws.close()
            Log.d(TAG, "Closed released connection (pool: ${availableConnections.size}, busy: ${busyConnections.size})")
        }
    }

    /**
     * 清理所有连接
     */
    suspend fun cleanup() = mutex.withLock {
        Log.d(TAG, "Cleaning up all connections")

        while (availableConnections.isNotEmpty()) {
            availableConnections.poll()?.close()
        }

        busyConnections.forEach { it.close() }
        busyConnections.clear()

        initialized = false
        Log.d(TAG, "All connections cleaned up")
    }

    /**
     * 获取统计信息
     */
    suspend fun getStats(): ConnectionStats = mutex.withLock {
        ConnectionStats(
            available = availableConnections.size,
            busy = busyConnections.size,
            total = availableConnections.size + busyConnections.size
        )
    }

    /**
     * 连接统计
     */
    data class ConnectionStats(
        val available: Int,
        val busy: Int,
        val total: Int
    )
}