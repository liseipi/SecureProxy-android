package com.musicses.secureproxy.service

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.musicses.secureproxy.model.ProxyConfig
import kotlinx.coroutines.*

/**
 * 代理服务 - 简化版，仅用于VPN配置管理
 * 实际的VPN功能由 VpnProxyService 提供
 */
class ProxyService : Service() {

    companion object {
        private const val TAG = "ProxyService"

        const val ACTION_START = "com.musicses.secureproxy.action.START"
        const val ACTION_STOP = "com.musicses.secureproxy.action.STOP"
        const val EXTRA_CONFIG = "config"
    }

    private val binder = ProxyBinder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

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
     * 启动代理 - 现在只是记录状态，实际VPN由VpnProxyService处理
     */
    private fun startProxy(config: ProxyConfig) {
        if (running) {
            Log.w(TAG, "Proxy already running")
            return
        }

        scope.launch {
            try {
                updateStatus(ServiceStatus.STARTING)

                Log.i(TAG, "VPN mode - Configuration received")
                Log.i(TAG, "SNI Host: ${config.sniHost}")
                Log.i(TAG, "Proxy IP: ${config.proxyIp}")
                Log.i(TAG, "Server Port: ${config.serverPort}")

                running = true
                updateStatus(ServiceStatus.RUNNING)

                Log.i(TAG, "Proxy service ready (VPN mode)")

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
                updateStatus(ServiceStatus.STOPPED)
                Log.i(TAG, "Proxy stopped")
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping proxy: ${e.message}", e)
            }
        }
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
     * 获取统计信息 - 简化版
     */
    fun getStats(): ProxyStats {
        return ProxyStats(
            running = running,
            vpnMode = true
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
     * 代理统计信息 - 简化版
     */
    data class ProxyStats(
        val running: Boolean,
        val vpnMode: Boolean = true
    )
}