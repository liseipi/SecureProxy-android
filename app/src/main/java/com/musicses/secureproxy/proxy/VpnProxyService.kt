package com.musicses.secureproxy.proxy

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import com.musicses.secureproxy.MainActivity
import com.musicses.secureproxy.model.ProxyConfig
import com.musicses.secureproxy.network.ConnectionManager
import com.musicses.secureproxy.network.SecureWebSocket
import kotlinx.coroutines.*
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.DatagramChannel

/**
 * VPN 代理服务 - 全局代理
 */
class VpnProxyService : VpnService() {

    companion object {
        private const val TAG = "VpnProxyService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "vpn_service_channel"

        const val ACTION_START = "com.musicses.secureproxy.action.START_VPN"
        const val ACTION_STOP = "com.musicses.secureproxy.action.STOP_VPN"
        const val EXTRA_CONFIG = "config"

        // VPN 配置
        private const val VPN_ADDRESS = "10.0.0.2"
        private const val VPN_ROUTE = "0.0.0.0"
        private const val VPN_MTU = 1500
    }

    private val binder = VpnBinder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var vpnInterface: ParcelFileDescriptor? = null
    private var connectionManager: ConnectionManager? = null

    @Volatile
    private var running = false

    private var processingJob: Job? = null
    private var statusCallback: ((ServiceStatus) -> Unit)? = null

    // TCP 连接映射表: 本地端口 -> WebSocket连接
    private val tcpConnections = mutableMapOf<Int, TcpConnection>()
    private var nextLocalPort = 10000

    inner class VpnBinder : Binder() {
        fun getService(): VpnProxyService = this@VpnProxyService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "VPN Service created")
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
                    startVpn(config)
                } else {
                    Log.e(TAG, "No config provided")
                    stopSelf()
                }
            }
            ACTION_STOP -> {
                stopVpn()
                stopSelf()
            }
        }

        return START_STICKY
    }

    /**
     * 启动 VPN
     */
    private fun startVpn(config: ProxyConfig) {
        if (running) {
            Log.w(TAG, "VPN already running")
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

                // 建立 VPN 接口
                vpnInterface = establishVpnInterface()

                if (vpnInterface == null) {
                    throw IllegalStateException("Failed to establish VPN interface")
                }

                running = true

                // 开始处理数据包
                processingJob = scope.launch {
                    processPackets()
                }

                updateNotification("VPN 已连接", "全局代理已启用")
                updateStatus(ServiceStatus.RUNNING)

                Log.i(TAG, "VPN started successfully")

            } catch (e: Exception) {
                Log.e(TAG, "Failed to start VPN: ${e.message}", e)
                updateStatus(ServiceStatus.ERROR, e.message)
                stopSelf()
            }
        }
    }

    /**
     * 建立 VPN 接口
     */
    private fun establishVpnInterface(): ParcelFileDescriptor? {
        return Builder()
            .setSession("SecureProxy")
            .addAddress(VPN_ADDRESS, 24)
            .addRoute(VPN_ROUTE, 0)
            .setMtu(VPN_MTU)
            .addDnsServer("8.8.8.8")
            .addDnsServer("8.8.4.4")
            .setBlocking(false)
            .establish()
    }

    /**
     * 处理数据包
     */
    private suspend fun processPackets() = withContext(Dispatchers.IO) {
        val vpnInput = FileInputStream(vpnInterface!!.fileDescriptor)
        val vpnOutput = FileOutputStream(vpnInterface!!.fileDescriptor)

        val packet = ByteBuffer.allocate(VPN_MTU)

        try {
            while (isActive && running) {
                packet.clear()

                // 从 VPN 读取数据包
                val length = vpnInput.channel.read(packet)
                if (length <= 0) {
                    delay(10)
                    continue
                }

                packet.flip()

                // 解析 IP 包
                val ipVersion = (packet.get(0).toInt() shr 4) and 0x0F

                if (ipVersion == 4) {
                    processIPv4Packet(packet, vpnOutput)
                } else if (ipVersion == 6) {
                    // IPv6 支持 (可选)
                    Log.d(TAG, "IPv6 packet received, skipping")
                }
            }
        } catch (e: Exception) {
            if (running) {
                Log.e(TAG, "Error processing packets: ${e.message}", e)
            }
        }
    }

    /**
     * 处理 IPv4 数据包
     */
    private suspend fun processIPv4Packet(packet: ByteBuffer, vpnOutput: FileOutputStream) {
        // IP 头部最小20字节
        if (packet.remaining() < 20) return

        val headerStart = packet.position()

        // 解析 IP 头
        val versionAndIHL = packet.get().toInt() and 0xFF
        val ihl = (versionAndIHL and 0x0F) * 4

        packet.position(headerStart + 9)
        val protocol = packet.get().toInt() and 0xFF

        packet.position(headerStart + 12)
        val srcAddress = ByteArray(4)
        packet.get(srcAddress)

        val dstAddress = ByteArray(4)
        packet.get(dstAddress)

        packet.position(headerStart + ihl)

        when (protocol) {
            6 -> processTcpPacket(packet, srcAddress, dstAddress, vpnOutput)
            17 -> processUdpPacket(packet, srcAddress, dstAddress, vpnOutput)
            else -> {
                Log.d(TAG, "Unsupported protocol: $protocol")
            }
        }
    }

    /**
     * 处理 TCP 数据包
     */
    private suspend fun processTcpPacket(
        packet: ByteBuffer,
        srcAddress: ByteArray,
        dstAddress: ByteArray,
        vpnOutput: FileOutputStream
    ) {
        if (packet.remaining() < 20) return

        val tcpStart = packet.position()

        // 解析 TCP 头
        val srcPort = packet.short.toInt() and 0xFFFF
        val dstPort = packet.short.toInt() and 0xFFFF

        packet.position(tcpStart + 12)
        val dataOffsetAndFlags = packet.get().toInt() and 0xFF
        val dataOffset = (dataOffsetAndFlags shr 4) * 4

        val flags = packet.get().toInt() and 0xFF
        val syn = (flags and 0x02) != 0
        val ack = (flags and 0x10) != 0
        val fin = (flags and 0x01) != 0
        val rst = (flags and 0x04) != 0

        packet.position(tcpStart + dataOffset)

        val dstHost = dstAddress.joinToString(".") { (it.toInt() and 0xFF).toString() }

        Log.d(TAG, "TCP: $srcPort -> $dstHost:$dstPort flags=${if(syn)"SYN "else""}${if(ack)"ACK "else""}${if(fin)"FIN "else""}${if(rst)"RST "else""}")

        // 获取或创建连接
        val connection = synchronized(tcpConnections) {
            tcpConnections.getOrPut(srcPort) {
                TcpConnection(srcPort, dstHost, dstPort)
            }
        }

        if (syn && !ack) {
            // 新连接
            scope.launch {
                try {
                    val ws = connectionManager?.acquire() ?: return@launch
                    ws.sendConnect(dstHost, dstPort)
                    connection.webSocket = ws
                    connection.connected = true

                    // 发送 SYN-ACK 响应
                    sendTcpResponse(vpnOutput, dstAddress, srcAddress, dstPort, srcPort, syn = true, ack = true)

                } catch (e: Exception) {
                    Log.e(TAG, "Failed to establish connection: ${e.message}")
                    sendTcpResponse(vpnOutput, dstAddress, srcAddress, dstPort, srcPort, rst = true)
                    synchronized(tcpConnections) {
                        tcpConnections.remove(srcPort)
                    }
                }
            }
        } else if (connection.connected && packet.hasRemaining()) {
            // 转发数据
            val data = ByteArray(packet.remaining())
            packet.get(data)

            scope.launch {
                try {
                    connection.webSocket?.send(data)
                    // 发送 ACK
                    sendTcpResponse(vpnOutput, dstAddress, srcAddress, dstPort, srcPort, ack = true)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to send data: ${e.message}")
                }
            }
        } else if (fin || rst) {
            // 关闭连接
            scope.launch {
                connection.webSocket?.let { connectionManager?.release(it) }
                synchronized(tcpConnections) {
                    tcpConnections.remove(srcPort)
                }
            }
        }
    }

    /**
     * 处理 UDP 数据包
     */
    private suspend fun processUdpPacket(
        packet: ByteBuffer,
        srcAddress: ByteArray,
        dstAddress: ByteArray,
        vpnOutput: FileOutputStream
    ) {
        // UDP 支持 (简化版，实际需要更复杂的处理)
        Log.d(TAG, "UDP packet received, basic forwarding")

        if (packet.remaining() < 8) return

        val srcPort = packet.short.toInt() and 0xFFFF
        val dstPort = packet.short.toInt() and 0xFFFF
        packet.short // length
        packet.short // checksum

        val data = ByteArray(packet.remaining())
        packet.get(data)

        // UDP 通过代理转发 (需要特殊处理)
        // 这里简化处理，实际应该建立 UDP 映射
    }

    /**
     * 发送 TCP 响应
     */
    private fun sendTcpResponse(
        vpnOutput: FileOutputStream,
        srcAddress: ByteArray,
        dstAddress: ByteArray,
        srcPort: Int,
        dstPort: Int,
        syn: Boolean = false,
        ack: Boolean = false,
        fin: Boolean = false,
        rst: Boolean = false,
        data: ByteArray? = null
    ) {
        // 构建 TCP 响应包 (简化版)
        // 实际实现需要完整的 IP/TCP 头部构建和校验和计算
        // 这里省略具体实现
    }

    /**
     * 停止 VPN
     */
    private fun stopVpn() {
        if (!running) return

        scope.launch {
            try {
                updateStatus(ServiceStatus.STOPPING)

                running = false

                processingJob?.cancel()
                processingJob = null

                // 关闭所有连接
                synchronized(tcpConnections) {
                    tcpConnections.values.forEach { conn ->
                        conn.webSocket?.let { connectionManager?.release(it) }
                    }
                    tcpConnections.clear()
                }

                connectionManager?.cleanup()
                connectionManager = null

                vpnInterface?.close()
                vpnInterface = null

                updateStatus(ServiceStatus.STOPPED)

                Log.i(TAG, "VPN stopped")

            } catch (e: Exception) {
                Log.e(TAG, "Error stopping VPN: ${e.message}", e)
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
                "VPN 服务",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "SecureProxy VPN 服务通知"
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
            .setContentTitle("SecureProxy VPN")
            .setContentText(contentText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(contentText))
            .setSmallIcon(android.R.drawable.ic_dialog_info)
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
    suspend fun getStats(): VpnStats {
        val connStats = connectionManager?.getStats()

        return VpnStats(
            running = running,
            tcpConnections = synchronized(tcpConnections) { tcpConnections.size },
            poolAvailable = connStats?.available ?: 0,
            poolBusy = connStats?.busy ?: 0,
            poolTotal = connStats?.total ?: 0
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        stopVpn()
        scope.cancel()
        Log.d(TAG, "VPN Service destroyed")
    }

    /**
     * TCP 连接
     */
    private data class TcpConnection(
        val localPort: Int,
        val remoteHost: String,
        val remotePort: Int,
        var webSocket: SecureWebSocket? = null,
        var connected: Boolean = false
    )

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
     * VPN 统计信息
     */
    data class VpnStats(
        val running: Boolean,
        val tcpConnections: Int,
        val poolAvailable: Int,
        val poolBusy: Int,
        val poolTotal: Int
    )
}