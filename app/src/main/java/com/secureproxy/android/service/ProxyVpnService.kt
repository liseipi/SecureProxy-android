package com.secureproxy.android.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.secureproxy.android.R
import com.secureproxy.android.data.model.ProxyConfig
import com.secureproxy.android.ui.MainActivity
import kotlinx.coroutines.*

/**
 * 代理 VPN 服务
 * 
 * 注意：这是一个简化版实现，实际的 SOCKS5/HTTP 代理需要：
 * 1. 使用 VpnService.Builder 创建 TUN 接口
 * 2. 读取 TUN 接口的数据包
 * 3. 解析 TCP/UDP 协议
 * 4. 通过 WebSocket 转发到远程服务器
 * 5. 将返回的数据写回 TUN 接口
 * 
 * 完整实现需要网络协议栈处理（可参考 shadowsocks-android 等开源项目）
 */
class ProxyVpnService : VpnService() {
    
    companion object {
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
    
    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }
    
    override fun onBind(intent: Intent?): IBinder? {
        // 如果是 VPN 服务的 bind 请求，返回 super.onBind(intent)
        // 否则返回自定义 binder
        return if (intent?.action == SERVICE_INTERFACE) {
            super.onBind(intent)
        } else {
            binder
        }
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val configUrl = intent.getStringExtra(EXTRA_CONFIG)
                config = configUrl?.let { ProxyConfig.fromUrl(it) }
                
                if (config != null) {
                    startProxy()
                } else {
                    stopSelf()
                }
            }
            ACTION_STOP -> {
                stopProxy()
            }
        }
        
        return START_STICKY
    }
    
    private fun startProxy() {
        val config = config ?: return
        
        // 启动前台服务
        val notification = createNotification(config)
        startForeground(NOTIFICATION_ID, notification)
        
        // TODO: 实际的 VPN 启动逻辑
        // 1. 建立 VPN 连接
        // val builder = Builder()
        //     .setSession("SecureProxy")
        //     .addAddress("10.0.0.2", 24)
        //     .addRoute("0.0.0.0", 0)
        //     .addDnsServer("8.8.8.8")
        // val fd = builder.establish()
        
        // 2. 启动数据包转发
        // scope.launch {
        //     handlePackets(fd)
        // }
    }
    
    private fun stopProxy() {
        scope.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
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
            notificationManager.createNotificationChannel(channel)
        }
    }
    
    private fun createNotification(config: ProxyConfig): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("SecureProxy 运行中")
            .setContentText("${config.name} - ${config.sniHost}")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
    
    override fun onDestroy() {
        super.onDestroy()
        stopProxy()
    }
}
