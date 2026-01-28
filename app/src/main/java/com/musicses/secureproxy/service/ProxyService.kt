package com.musicses.secureproxy.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.musicses.secureproxy.MainActivity
import com.musicses.secureproxy.model.ProxyConfig
import com.musicses.secureproxy.network.ConnectionManager
import com.musicses.secureproxy.proxy.HttpProxyServer
import com.musicses.secureproxy.proxy.Socks5Server
import kotlinx.coroutines.*

/**
 * 代理服务 - 作为前台服务运行
 */
class ProxyService : Service() {

    companion object {
        private const val TAG = "ProxyService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "proxy_service_channel"

        const val ACTION_START = "com.musicses.secureproxy.action.START"
        const val ACTION_STOP = "com.musicses.secureproxy.action.STOP"
        const val EXTRA_CONFIG = "config"
    }

    private val binder = ProxyBinder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var connectionManager: ConnectionManager? = null
    private var socks5Server: Socks5Server? = null
    private var httpProxyServer: HttpProxyServer? = null

    @Volatile
    private var running = false

    private var statusCallback: ((ServiceStatus) -> Unit)? = null

    /**
     * Binder 类
     */
    inner class ProxyBinder : Binder() {
        fun getService(): ProxyService = this@ProxyService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service created")
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val config = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_CONFIG, ProxyConfig::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_CONFIG)
                }

                if (config != null) {
                    startProxy(config)
                } else {
                    Log.e(TAG, "No config provided")
                    stopSelf()
                }
            }
            ACTION_STOP -> {
                stopProxy()
                stopSelf()
            }
        }

        return START_STICKY
    }

    /**
     * 启动代理
     */
    private fun startProxy(config: ProxyConfig) {
        if (running) {
            Log.w(TAG, "Proxy already running")
            return
        }

        scope.launch {
            try {
                updateStatus(ServiceStatus.STARTING)

                // 创建通知
                val notification = createNotification("正在启动...")
                startForeground(NOTIFICATION_ID, notification)

                // 初始化连接管理器
                connectionManager = ConnectionManager(config, scope).apply {
                    initialize()
                }

                // 启动 SOCKS5 服务器
                socks5Server = Socks5Server(config, connectionManager!!, scope).apply {
                    start()
                }

                // 启动 HTTP 代理服务器
                httpProxyServer = HttpProxyServer(config, connectionManager!!, scope).apply {
                    start()
                }

                running = true

                val message = "SOCKS5: 127.0.0.1:${config.socksPort}\n" +
                        "HTTP: 127.0.0.1:${config.httpPort}"

                updateNotification("代理运行中", message)
                updateStatus(ServiceStatus.RUNNING)

                Log.i(TAG, "Proxy started successfully")

            } catch (e: Exception) {
                Log.e(TAG, "Failed to start proxy: ${e.message}", e)
                updateStatus(ServiceStatus.ERROR, e.message)
                stopSelf()
            }
        }
    }

    /**
     * 停止代理
     */
    private fun stopProxy() {
        if (!running) return

        scope.launch {
            try {
                updateStatus(ServiceStatus.STOPPING)

                running = false

                socks5Server?.stop()
                socks5Server = null

                httpProxyServer?.stop()
                httpProxyServer = null

                connectionManager?.cleanup()
                connectionManager = null

                updateStatus(ServiceStatus.STOPPED)

                Log.i(TAG, "Proxy stopped")

            } catch (e: Exception) {
                Log.e(TAG, "Error stopping proxy: ${e.message}", e)
            }
        }
    }

    /**
     * 创建通知渠道
     */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "代理服务",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "SecureProxy 代理服务通知"
                setShowBadge(false)
            }

            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    /**
     * 创建通知
     */
    private fun createNotification(status: String, details: String = ""): android.app.Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }

        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val contentText = if (details.isNotEmpty()) {
            "$status\n$details"
        } else {
            status
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("SecureProxy")
            .setContentText(contentText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(contentText))
            .setSmallIcon(android.R.drawable.ic_dialog_info)  // 使用系统图标,你可以替换为自定义图标
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    /**
     * 更新通知
     */
    private fun updateNotification(status: String, details: String = "") {
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID, createNotification(status, details))
    }

    /**
     * 更新状态
     */
    private fun updateStatus(status: ServiceStatus, message: String? = null) {
        statusCallback?.invoke(status.copy(message = message))
    }

    /**
     * 设置状态回调
     */
    fun setStatusCallback(callback: (ServiceStatus) -> Unit) {
        statusCallback = callback
    }

    /**
     * 获取统计信息
     */
    suspend fun getStats(): ProxyStats {
        val connStats = connectionManager?.getStats()

        return ProxyStats(
            running = running,
            socks5Connections = socks5Server?.getActiveConnectionCount() ?: 0,
            httpConnections = httpProxyServer?.getActiveConnectionCount() ?: 0,
            poolAvailable = connStats?.available ?: 0,
            poolBusy = connStats?.busy ?: 0,
            poolTotal = connStats?.total ?: 0
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        stopProxy()
        scope.cancel()
        Log.d(TAG, "Service destroyed")
    }

    /**
     * 服务状态
     */
    data class ServiceStatus(
        val state: State,
        val message: String? = null
    ) {
        enum class State {
            IDLE,
            STARTING,
            RUNNING,
            STOPPING,
            STOPPED,
            ERROR
        }

        companion object {
            val IDLE = ServiceStatus(State.IDLE)
            val STARTING = ServiceStatus(State.STARTING)
            val RUNNING = ServiceStatus(State.RUNNING)
            val STOPPING = ServiceStatus(State.STOPPING)
            val STOPPED = ServiceStatus(State.STOPPED)
            fun ERROR(message: String?) = ServiceStatus(State.ERROR, message)
        }
    }

    /**
     * 代理统计信息
     */
    data class ProxyStats(
        val running: Boolean,
        val socks5Connections: Int,
        val httpConnections: Int,
        val poolAvailable: Int,
        val poolBusy: Int,
        val poolTotal: Int
    )
}