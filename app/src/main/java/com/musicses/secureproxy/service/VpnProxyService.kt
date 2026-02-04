package com.musicses.secureproxy.service

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
import com.musicses.secureproxy.R
import com.musicses.secureproxy.model.ProxyConfig
import com.musicses.secureproxy.network.ConnectionManager
import com.musicses.secureproxy.network.SecureWebSocket
import kotlinx.coroutines.*
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap

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

        // TCP标志位
        private const val TCP_FIN = 0x01
        private const val TCP_SYN = 0x02
        private const val TCP_RST = 0x04
        private const val TCP_PSH = 0x08
        private const val TCP_ACK = 0x10
    }

    private val binder = VpnBinder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var vpnInterface: ParcelFileDescriptor? = null
    private var connectionManager: ConnectionManager? = null

    @Volatile
    private var running = false

    private var processingJob: Job? = null
    private var statusCallback: ((ServiceStatus) -> Unit)? = null

    // TCP 连接映射表: localPort -> TcpConnection
    private val tcpConnections = ConcurrentHashMap<Int, TcpConnection>()
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
                updateStatus(ServiceStatus.ERROR(e.message))
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
            6 -> processTcpPacket(packet, srcAddress, dstAddress, ihl, vpnOutput)
            17 -> processUdpPacket(packet, srcAddress, dstAddress)
        }
    }

    /**
     * 处理 TCP 数据包
     */
    private suspend fun processTcpPacket(
        packet: ByteBuffer,
        srcAddress: ByteArray,
        dstAddress: ByteArray,
        ipHeaderLength: Int,
        vpnOutput: FileOutputStream
    ) {
        if (packet.remaining() < 20) return

        val tcpStart = packet.position()

        // 解析 TCP 头
        val srcPort = packet.short.toInt() and 0xFFFF
        val dstPort = packet.short.toInt() and 0xFFFF

        val seqNum = packet.int
        val ackNum = packet.int

        val dataOffsetAndFlags = packet.get().toInt() and 0xFF
        val dataOffset = (dataOffsetAndFlags shr 4) * 4

        val flags = packet.get().toInt() and 0xFF
        val syn = (flags and TCP_SYN) != 0
        val ack = (flags and TCP_ACK) != 0
        val fin = (flags and TCP_FIN) != 0
        val rst = (flags and TCP_RST) != 0
        val psh = (flags and TCP_PSH) != 0

        packet.position(tcpStart + dataOffset)

        val dstHost = dstAddress.joinToString(".") { (it.toInt() and 0xFF).toString() }
        val payloadLength = packet.remaining()

        Log.d(TAG, "TCP: $srcPort -> $dstHost:$dstPort seq=$seqNum ack=$ackNum flags=${flagsToString(flags)} payload=$payloadLength")

        val connKey = "$srcPort-$dstHost-$dstPort"

        if (syn && !ack) {
            // 新连接请求
            handleTcpSyn(connKey, srcPort, dstHost, dstPort, seqNum, srcAddress, dstAddress, vpnOutput)
        } else if (fin) {
            // 关闭连接
            handleTcpFin(connKey, srcPort, dstHost, dstPort, seqNum, ackNum, srcAddress, dstAddress, vpnOutput)
        } else if (rst) {
            // 重置连接
            handleTcpRst(connKey)
        } else if (payloadLength > 0) {
            // 数据传输
            handleTcpData(connKey, packet, seqNum, ackNum, srcPort, dstPort, srcAddress, dstAddress, vpnOutput)
        } else if (ack) {
            // 纯ACK包
            handleTcpAck(connKey, ackNum)
        }
    }

    /**
     * 处理 TCP SYN
     */
    private suspend fun handleTcpSyn(
        connKey: String,
        srcPort: Int,
        dstHost: String,
        dstPort: Int,
        seqNum: Int,
        srcAddress: ByteArray,
        dstAddress: ByteArray,
        vpnOutput: FileOutputStream
    ) {
        scope.launch {
            try {
                val ws = connectionManager?.acquire() ?: return@launch
                ws.sendConnect(dstHost, dstPort)

                val connection = TcpConnection(
                    localPort = srcPort,
                    remoteHost = dstHost,
                    remotePort = dstPort,
                    webSocket = ws,
                    connected = true,
                    seqNum = seqNum + 1,
                    ackNum = (Math.random() * Int.MAX_VALUE).toInt()
                )

                tcpConnections[srcPort] = connection

                // 发送 SYN-ACK
                sendTcpResponse(
                    vpnOutput,
                    dstAddress,
                    srcAddress,
                    dstPort,
                    srcPort,
                    connection.ackNum,
                    connection.seqNum,
                    TCP_SYN or TCP_ACK
                )

                connection.ackNum++

                // 启动接收循环
                startReceiveLoop(connection, srcAddress, dstAddress, vpnOutput)

            } catch (e: Exception) {
                Log.e(TAG, "Failed to establish connection: ${e.message}")
                sendTcpResponse(
                    vpnOutput,
                    dstAddress,
                    srcAddress,
                    dstPort,
                    srcPort,
                    0,
                    0,
                    TCP_RST
                )
                tcpConnections.remove(srcPort)
            }
        }
    }

    /**
     * 处理 TCP 数据
     */
    private suspend fun handleTcpData(
        connKey: String,
        packet: ByteBuffer,
        seqNum: Int,
        ackNum: Int,
        srcPort: Int,
        dstPort: Int,
        srcAddress: ByteArray,
        dstAddress: ByteArray,
        vpnOutput: FileOutputStream
    ) {
        val connection = tcpConnections[srcPort] ?: return

        if (!connection.connected) return

        val data = ByteArray(packet.remaining())
        packet.get(data)

        scope.launch {
            try {
                connection.webSocket?.send(data)
                connection.seqNum = ackNum

                // 发送 ACK
                sendTcpResponse(
                    vpnOutput,
                    dstAddress,
                    srcAddress,
                    dstPort,
                    srcPort,
                    connection.ackNum,
                    seqNum + data.size,
                    TCP_ACK
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send data: ${e.message}")
                closeTcpConnection(srcPort)
            }
        }
    }

    /**
     * 处理 TCP FIN
     */
    private suspend fun handleTcpFin(
        connKey: String,
        srcPort: Int,
        dstHost: String,
        dstPort: Int,
        seqNum: Int,
        ackNum: Int,
        srcAddress: ByteArray,
        dstAddress: ByteArray,
        vpnOutput: FileOutputStream
    ) {
        val connection = tcpConnections[srcPort]

        if (connection != null) {
            // 发送 FIN-ACK
            sendTcpResponse(
                vpnOutput,
                dstAddress,
                srcAddress,
                dstPort,
                srcPort,
                connection.ackNum,
                seqNum + 1,
                TCP_FIN or TCP_ACK
            )

            connection.ackNum++
        }

        closeTcpConnection(srcPort)
    }

    /**
     * 处理 TCP RST
     */
    private fun handleTcpRst(connKey: String) {
        val portMatch = connKey.split("-").firstOrNull()?.toIntOrNull()
        if (portMatch != null) {
            closeTcpConnection(portMatch)
        }
    }

    /**
     * 处理 TCP ACK
     */
    private fun handleTcpAck(connKey: String, ackNum: Int) {
        val portMatch = connKey.split("-").firstOrNull()?.toIntOrNull()
        if (portMatch != null) {
            tcpConnections[portMatch]?.let { connection ->
                connection.seqNum = ackNum
            }
        }
    }

    /**
     * 启动接收循环
     */
    private fun startReceiveLoop(
        connection: TcpConnection,
        srcAddress: ByteArray,
        dstAddress: ByteArray,
        vpnOutput: FileOutputStream
    ) {
        scope.launch {
            try {
                while (connection.connected && connection.webSocket?.isConnected() == true) {
                    val data = connection.webSocket?.recv() ?: break

                    if (data.isEmpty()) break

                    // 发送数据包给客户端
                    sendTcpResponse(
                        vpnOutput,
                        dstAddress,
                        srcAddress,
                        connection.remotePort,
                        connection.localPort,
                        connection.ackNum,
                        connection.seqNum,
                        TCP_PSH or TCP_ACK,
                        data
                    )

                    connection.ackNum += data.size
                }
            } catch (e: Exception) {
                Log.e(TAG, "Receive loop error: ${e.message}")
            } finally {
                closeTcpConnection(connection.localPort)
            }
        }
    }

    /**
     * 发送 TCP 响应包
     */
    private fun sendTcpResponse(
        vpnOutput: FileOutputStream,
        srcAddress: ByteArray,
        dstAddress: ByteArray,
        srcPort: Int,
        dstPort: Int,
        seqNum: Int,
        ackNum: Int,
        flags: Int,
        data: ByteArray? = null
    ) {
        try {
            val dataLen = data?.size ?: 0
            val tcpHeaderLen = 20
            val ipHeaderLen = 20
            val totalLen = ipHeaderLen + tcpHeaderLen + dataLen

            val packet = ByteBuffer.allocate(totalLen)

            // IP 头部
            packet.put((0x45).toByte()) // Version=4, IHL=5
            packet.put(0) // DSCP/ECN
            packet.putShort(totalLen.toShort()) // Total Length
            packet.putShort(0) // Identification
            packet.putShort(0) // Flags + Fragment Offset
            packet.put(64) // TTL
            packet.put(6) // Protocol = TCP
            packet.putShort(0) // Checksum (计算后填入)
            packet.put(srcAddress) // Source IP
            packet.put(dstAddress) // Destination IP

            // 计算IP校验和
            val ipChecksumPos = 10
            val ipChecksum = calculateChecksum(packet.array(), 0, ipHeaderLen)
            packet.putShort(ipChecksumPos, ipChecksum)

            // TCP 头部
            val tcpStart = packet.position()
            packet.putShort(srcPort.toShort()) // Source Port
            packet.putShort(dstPort.toShort()) // Destination Port
            packet.putInt(seqNum) // Sequence Number
            packet.putInt(ackNum) // Acknowledgment Number
            packet.put(((tcpHeaderLen / 4) shl 4).toByte()) // Data Offset
            packet.put(flags.toByte()) // Flags
            packet.putShort(65535.toShort()) // Window Size
            packet.putShort(0) // Checksum (计算后填入)
            packet.putShort(0) // Urgent Pointer

            // 数据
            if (data != null && data.isNotEmpty()) {
                packet.put(data)
            }

            // 计算TCP校验和
            val tcpChecksumPos = tcpStart + 16
            val tcpChecksum = calculateTcpChecksum(
                packet.array(),
                srcAddress,
                dstAddress,
                tcpStart,
                tcpHeaderLen + dataLen
            )
            packet.putShort(tcpChecksumPos, tcpChecksum)

            // 写入VPN接口
            packet.position(0)
            vpnOutput.channel.write(packet)

        } catch (e: Exception) {
            Log.e(TAG, "Failed to send TCP response: ${e.message}")
        }
    }

    /**
     * 计算校验和
     */
    private fun calculateChecksum(data: ByteArray, offset: Int, length: Int): Short {
        var sum = 0L
        var i = offset

        while (i < offset + length - 1) {
            sum += ((data[i].toInt() and 0xFF) shl 8) or (data[i + 1].toInt() and 0xFF)
            i += 2
        }

        if (i < offset + length) {
            sum += (data[i].toInt() and 0xFF) shl 8
        }

        while (sum shr 16 != 0L) {
            sum = (sum and 0xFFFF) + (sum shr 16)
        }

        return (sum.inv() and 0xFFFF).toShort()
    }

    /**
     * 计算TCP校验和
     */
    private fun calculateTcpChecksum(
        packet: ByteArray,
        srcAddress: ByteArray,
        dstAddress: ByteArray,
        tcpOffset: Int,
        tcpLength: Int
    ): Short {
        val pseudoHeader = ByteBuffer.allocate(12 + tcpLength)
        pseudoHeader.put(srcAddress)
        pseudoHeader.put(dstAddress)
        pseudoHeader.put(0)
        pseudoHeader.put(6) // TCP protocol
        pseudoHeader.putShort(tcpLength.toShort())
        pseudoHeader.put(packet, tcpOffset, tcpLength)

        return calculateChecksum(pseudoHeader.array(), 0, pseudoHeader.position())
    }

    /**
     * 关闭TCP连接
     */
    private fun closeTcpConnection(localPort: Int) {
        tcpConnections.remove(localPort)?.let { connection ->
            connection.connected = false
            scope.launch {
                connection.webSocket?.let { connectionManager?.release(it) }
            }
        }
    }

    /**
     * 处理 UDP 数据包
     */
    private suspend fun processUdpPacket(
        packet: ByteBuffer,
        srcAddress: ByteArray,
        dstAddress: ByteArray
    ) {
        // UDP 支持可以后续添加
        Log.d(TAG, "UDP packet received (not yet supported)")
    }

    /**
     * 将标志位转换为字符串
     */
    private fun flagsToString(flags: Int): String {
        val parts = mutableListOf<String>()
        if ((flags and TCP_FIN) != 0) parts.add("FIN")
        if ((flags and TCP_SYN) != 0) parts.add("SYN")
        if ((flags and TCP_RST) != 0) parts.add("RST")
        if ((flags and TCP_PSH) != 0) parts.add("PSH")
        if ((flags and TCP_ACK) != 0) parts.add("ACK")
        return parts.joinToString("|")
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
                tcpConnections.values.forEach { conn ->
                    conn.webSocket?.let { connectionManager?.release(it) }
                }
                tcpConnections.clear()

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
            tcpConnections = tcpConnections.size,
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
        var connected: Boolean = false,
        var seqNum: Int = 0,
        var ackNum: Int = 0
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