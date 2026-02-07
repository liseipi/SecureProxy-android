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
 * 代理 VPN 服务 - 修复版
 * 关键修复:
 * 1. 在主线程建立 VPN 接口 (不能在协程中)
 * 2. 添加详细错误日志
 * 3. 简化启动流程
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
        Log.d(TAG, "onStartCommand: action=${intent?.action}")

        when (intent?.action) {
            ACTION_START -> {
                // 1. 立即启动前台服务
                startForegroundService()

                // 2. 解析配置
                val configUrl = intent.getStringExtra(EXTRA_CONFIG)
                config = configUrl?.let { ProxyConfig.fromUrl(it) }

                if (config == null) {
                    Log.e(TAG, "❌ Invalid config URL: $configUrl")
                    stopSelf()
                    return START_NOT_STICKY
                }

                Log.d(TAG, "✅ Config loaded: ${config!!.name}")

                // 3. 建立 VPN (必须在主线程,不能在协程!)
                val success = establishVpn(config!!)

                if (success) {
                    Log.d(TAG, "✅✅✅ VPN ESTABLISHED SUCCESSFULLY ✅✅✅")
                    updateNotification(config!!)
                } else {
                    Log.e(TAG, "❌❌❌ VPN ESTABLISH FAILED ❌❌❌")
                    stopSelf()
                }
            }

            ACTION_STOP -> {
                Log.d(TAG, "Stopping VPN service")
                stopVpn()
            }
        }

        return START_STICKY
    }

    /**
     * 启动前台服务
     */
    private fun startForegroundService() {
        val notification = createNotification("正在启动...")

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceCompat.startForeground(
                    this,
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            Log.d(TAG, "✅ Foreground service started")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to start foreground", e)
        }
    }

    /**
     * 建立 VPN 连接 - 同步执行
     */
    private fun establishVpn(config: ProxyConfig): Boolean {
        Log.d(TAG, "🔧 Establishing VPN for ${config.name}...")

        try {
            // 创建 Builder
            val builder = Builder()

            Log.d(TAG, "  → Setting session: ${config.name}")
            builder.setSession(config.name)

            Log.d(TAG, "  → Adding address: 10.0.0.2/24")
            builder.addAddress("10.0.0.2", 24)

            Log.d(TAG, "  → Adding route: 0.0.0.0/0")
            builder.addRoute("0.0.0.0", 0)

            Log.d(TAG, "  → Adding DNS: 8.8.8.8, 8.8.4.4")
            builder.addDnsServer("8.8.8.8")
            builder.addDnsServer("8.8.4.4")

            Log.d(TAG, "  → Setting MTU: 1500")
            builder.setMtu(1500)

            // 🔥 关键: 非阻塞模式
            Log.d(TAG, "  → Setting blocking: false")
            builder.setBlocking(false)

            // 🔥🔥 最关键的调用
            Log.d(TAG, "  → Calling builder.establish()...")
            vpnInterface = builder.establish()

            if (vpnInterface == null) {
                Log.e(TAG, "❌ builder.establish() returned NULL!")
                Log.e(TAG, "   Possible reasons:")
                Log.e(TAG, "   1. VPN permission was revoked")
                Log.e(TAG, "   2. Another VPN is already active")
                Log.e(TAG, "   3. System rejected the VPN configuration")
                return false
            }

            val fd = vpnInterface!!.fileDescriptor
            Log.d(TAG, "✅ VPN interface established! FD: $fd")
            Log.d(TAG, "✅ VPN is now ACTIVE - system VPN icon should appear")

            return true

        } catch (e: SecurityException) {
            Log.e(TAG, "❌ SecurityException: VPN permission denied!", e)
            Log.e(TAG, "   → User may have denied VPN permission dialog")
            return false

        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "❌ IllegalArgumentException: Invalid VPN config!", e)
            Log.e(TAG, "   → Check IP address, routes, DNS settings")
            return false

        } catch (e: IllegalStateException) {
            Log.e(TAG, "❌ IllegalStateException: VPN service in bad state!", e)
            Log.e(TAG, "   → Another VPN might be active")
            return false

        } catch (e: Exception) {
            Log.e(TAG, "❌ Unexpected error establishing VPN", e)
            return false
        }
    }

    /**
     * 停止 VPN
     */
    private fun stopVpn() {
        Log.d(TAG, "🛑 Stopping VPN...")

        try {
            vpnInterface?.close()
            vpnInterface = null
            Log.d(TAG, "✅ VPN interface closed")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error closing VPN interface", e)
        }

        scope.cancel()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }

        stopSelf()
        Log.d(TAG, "✅ Service stopped")
    }

    /**
     * 更新通知
     */
    private fun updateNotification(config: ProxyConfig) {
        val notification = createNotification("${config.name} - 已连接")
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager?.notify(NOTIFICATION_ID, notification)
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

    private fun createNotification(text: String): Notification {
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
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    override fun onRevoke() {
        Log.w(TAG, "⚠️ VPN permission revoked by user!")
        stopVpn()
        super.onRevoke()
    }

    override fun onDestroy() {
        Log.d(TAG, "Service onDestroy")
        stopVpn()
        super.onDestroy()
    }
}