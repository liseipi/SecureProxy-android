package com.secureproxy.android.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.secureproxy.android.data.model.ProxyConfig
import com.secureproxy.android.ui.MainActivity
import kotlinx.coroutines.*

/**
 * 代理 VPN 服务
 */
class ProxyVpnService : VpnService() {

    companion object {
        private const val TAG = "ProxyVpnService"
        const val ACTION_START = "com.secureproxy.android.START"
        const val ACTION_STOP = "com.secureproxy.android.STOP"
        const val EXTRA_CONFIG = "config"

        private const val NOTIFICATION_CHANNEL_ID = "proxy_service"
        private const val NOTIFICATION_ID = 1
    }

    inner class VpnServiceBinder : Binder() {
        fun getService(): ProxyVpnService = this@ProxyVpnService
    }

    private val binder = VpnServiceBinder()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var config: ProxyConfig? = null
    private var vpnInterface: ParcelFileDescriptor? = null

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service onCreate")
        createNotificationChannel()
    }

    override fun onBind(intent: Intent?): IBinder? {
        return if (intent?.action == SERVICE_INTERFACE) {
            super.onBind(intent)
        } else {
            binder
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand: ${intent?.action}")

        // 为了防止 ForegroundServiceDidNotStartInTimeException，
        // 必须在 onStartCommand 中尽快调用 startForeground。
        // 对于 Android 14+ (Target SDK 34+)，必须指定 foregroundServiceType。
        val initialNotification = createInitialNotification()
        
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceCompat.startForeground(
                    this,
                    NOTIFICATION_ID,
                    initialNotification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else {
                startForeground(NOTIFICATION_ID, initialNotification)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start foreground service", e)
        }

        when (intent?.action) {
            ACTION_START -> {
                val configUrl = intent.getStringExtra(EXTRA_CONFIG)
                config = configUrl?.let { ProxyConfig.fromUrl(it) }

                if (config != null) {
                    startProxy(config!!)
                } else {
                    Log.e(TAG, "Invalid config, stopping service")
                    stopSelf()
                }
            }
            ACTION_STOP -> {
                stopProxy()
            }
        }

        return START_STICKY
    }

    private fun startProxy(config: ProxyConfig) {
        try {
            Log.d(TAG, "Starting proxy for ${config.name}")

            // 更新通知内容为具体的配置信息
            val notification = createNotification(config)
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager?.notify(NOTIFICATION_ID, notification)
            Log.d(TAG, "Foreground service updated with config")

            // 然后再建立 VPN 连接
            scope.launch {
                try {
                    establishVpnConnection(config)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to establish VPN", e)
                    stopProxy()
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "Failed to start proxy", e)
            stopSelf()
        }
    }

    private fun establishVpnConnection(config: ProxyConfig) {
        Log.d(TAG, "Establishing VPN connection...")

        try {
            // 创建 VPN 接口
            val builder = Builder()
                .setSession(config.name)
                .addAddress("10.0.0.2", 24)
                .addRoute("0.0.0.0", 0)
                .addDnsServer("8.8.8.8")
                .addDnsServer("8.8.4.4")
                .setMtu(1500)
                .setBlocking(false)

            vpnInterface = builder.establish()

            if (vpnInterface != null) {
                Log.d(TAG, "VPN interface established")

                // TODO: 启动数据包转发
                // 这里需要实现：
                // 1. 从 TUN 接口读取数据包
                // 2. 解析 TCP/UDP 协议
                // 3. 通过 WebSocket 转发到远程服务器
                // 4. 将返回数据写回 TUN 接口

            } else {
                Log.e(TAG, "Failed to establish VPN interface")
                stopProxy()
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error establishing VPN", e)
            stopProxy()
        }
    }

    private fun stopProxy() {
        Log.d(TAG, "Stopping proxy")

        try {
            vpnInterface?.close()
            vpnInterface = null
        } catch (e: Exception) {
            Log.e(TAG, "Error closing VPN interface", e)
        }

        scope.cancel()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }

        stopSelf()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "代理服务",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "SecureProxy 运行状态"
                setShowBadge(false)
            }

            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager?.createNotificationChannel(channel)
        }
    }

    private fun createInitialNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }

        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("SecureProxy")
            .setContentText("正在启动服务...")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun createNotification(config: ProxyConfig): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }

        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("SecureProxy 运行中")
            .setContentText("${config.name} - ${config.sniHost}")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    override fun onRevoke() {
        Log.d(TAG, "VPN permission revoked")
        stopProxy()
        super.onRevoke()
    }

    override fun onDestroy() {
        Log.d(TAG, "Service onDestroy")
        stopProxy()
        super.onDestroy()
    }
}