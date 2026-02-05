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
 * VPN 代理服务 - 修复版
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

    // TCP 连接映射
    private val tcpConnections = ConcurrentHashMap<String, TcpConnection>()

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

    private fun startVpn(config: ProxyConfig) {
        if (running) {
            Log.w(TAG, "VPN already running")
            return
        }

        scope.launch {
            try {
                updateStatus(ServiceStatus.STARTING)

                val notification = createNotification("正在启动...")
                startForeground(NOTIFICATION_ID, notification)

                connectionManager = ConnectionManager(config, scope).apply {
                    initialize()
                }

                vpnInterface = establishVpnInterface()

                if (vpnInterface == null) {
                    throw IllegalStateException("Failed to establish VPN interface")
                }

                running = true

                processingJob = scope.launch {
                    processPackets()
                }

                updateNotification("VPN 已连接", "全局代理已启用")
                updateStatus(ServiceStatus.RUNNING)

                Log.i(TAG, "VPN started successfully")

            } catch (e: Exception) {
                Log.e(TAG, "Failed to start VPN", e)
                updateStatus(ServiceStatus.ERROR(e.message))
                stopSelf()
            }
        }
    }

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

    private suspend fun processPackets() = withContext(Dispatchers.IO) {
        val vpnInput = FileInputStream(vpnInterface!!.fileDescriptor)
        val vpnOutput = FileOutputStream(vpnInterface!!.fileDescriptor)

        val packet = ByteBuffer.allocate(VPN_MTU)

        try {
            Log.d(TAG, "开始处理数据包...")

            while (isActive && running) {
                packet.clear()

                val length = vpnInput.channel.read(packet)
                if (length <= 0) {
                    delay(10)
                    continue
                }

                packet.flip()

                try {
                    val ipVersion = (packet.get(0).toInt() shr 4) and 0x0F
                    if (ipVersion == 4) {
                        processIPv4Packet(packet, vpnOutput)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "处理数据包失败: ${e.message}")
                }
            }
        } catch (e: Exception) {
            if (running) {
                Log.e(TAG, "Error processing packets", e)
            }
        } finally {
            Log.d(TAG, "停止处理数据包")
        }
    }

    private suspend fun processIPv4Packet(packet: ByteBuffer, vpnOutput: FileOutputStream) {
        if (packet.remaining() < 20) return

        val headerStart = packet.position()
        val versionAndIHL = packet.get().toInt() and 0xFF
        val ihl = (versionAndIHL and 0x0F) * 4

        packet.get() // TOS
        packet.short // Total Length
        packet.position(headerStart + 8)
        packet.get() // TTL
        val protocol = packet.get().toInt() and 0xFF
        packet.position(headerStart + 12)

        val srcAddress = ByteArray(4)
        packet.get(srcAddress)

        val dstAddress = ByteArray(4)
        packet.get(dstAddress)

        packet.position(headerStart + ihl)

        when (protocol) {
            6 -> {
                Log.d(TAG, "TCP: ${formatIp(srcAddress)} -> ${formatIp(dstAddress)}")
                processTcpPacket(packet, srcAddress, dstAddress, headerStart, ihl, vpnOutput)
            }
        }
    }

    private suspend fun processTcpPacket(
        packet: ByteBuffer,
        srcAddress: ByteArray,
        dstAddress: ByteArray,
        ipHeaderStart: Int,
        ipHeaderLength: Int,
        vpnOutput: FileOutputStream
    ) {
        if (packet.remaining() < 20) return

        val tcpStart = packet.position()

        val srcPort = packet.short.toInt() and 0xFFFF
        val dstPort = packet.short.toInt() and 0xFFFF
        val seqNum = packet.int
        val ackNum = packet.int

        val dataOffsetAndFlags = packet.short.toInt() and 0xFFFF
        val dataOffset = ((dataOffsetAndFlags shr 12) and 0x0F) * 4
        val flags = dataOffsetAndFlags and 0x3F

        val syn = (flags and TCP_SYN) != 0
        val ack = (flags and TCP_ACK) != 0
        val fin = (flags and TCP_FIN) != 0
        val rst = (flags and TCP_RST) != 0
        val psh = (flags and TCP_PSH) != 0

        packet.position(tcpStart + dataOffset)

        val dstHost = formatIp(dstAddress)
        val payloadLength = packet.remaining()

        val connKey = "$srcPort-$dstHost-$dstPort"

        Log.d(TAG, "$connKey [${flagsToString(flags)}] seq=$seqNum ack=$ackNum payload=$payloadLength")

        try {
            when {
                syn && !ack -> handleTcpSyn(connKey, srcPort, dstHost, dstPort, seqNum, srcAddress, dstAddress, vpnOutput)
                fin -> handleTcpFin(connKey, seqNum, ackNum, srcPort, dstPort, srcAddress, dstAddress, vpnOutput)
                rst -> handleTcpRst(connKey)
                payloadLength > 0 -> {
                    val payload = ByteArray(payloadLength)
                    packet.get(payload)
                    handleTcpData(connKey, payload, seqNum, ackNum, srcPort, dstPort, srcAddress, dstAddress, vpnOutput)
                }
                ack -> handleTcpAck(connKey, ackNum)
            }
        } catch (e: Exception) {
            Log.e(TAG, "TCP处理失败 ($connKey)", e)
            sendTcpPacket(vpnOutput, dstAddress, srcAddress, dstPort, srcPort, 0, 0, TCP_RST, null)
        }
    }

    private suspend fun handleTcpSyn(
        connKey: String,
        srcPort: Int,
        dstHost: String,
        dstPort: Int,
        clientSeq: Int,
        srcAddress: ByteArray,
        dstAddress: ByteArray,
        vpnOutput: FileOutputStream
    ) {
        if (tcpConnections.containsKey(connKey)) return

        scope.launch {
            try {
                Log.i(TAG, "新连接: $connKey")

                val ws = connectionManager?.acquire() ?: throw Exception("无法获取WebSocket")

                ws.sendConnect(dstHost, dstPort)
                Log.d(TAG, "已连接远程: $dstHost:$dstPort")

                val serverSeq = (Math.random() * Int.MAX_VALUE).toInt()

                val connection = TcpConnection(
                    key = connKey,
                    localPort = srcPort,
                    remoteHost = dstHost,
                    remotePort = dstPort,
                    webSocket = ws,
                    connected = true,
                    clientSeq = clientSeq + 1,
                    serverSeq = serverSeq + 1,
                    srcAddress = srcAddress,
                    dstAddress = dstAddress
                )

                tcpConnections[connKey] = connection

                sendTcpPacket(
                    vpnOutput,
                    dstAddress, srcAddress,
                    dstPort, srcPort,
                    serverSeq,
                    clientSeq + 1,
                    TCP_SYN or TCP_ACK,
                    null
                )

                Log.d(TAG, "已发送 SYN-ACK: $connKey")

                startReceiveLoop(connection, vpnOutput)

            } catch (e: Exception) {
                Log.e(TAG, "连接失败 ($connKey)", e)
                tcpConnections.remove(connKey)
                sendTcpPacket(vpnOutput, dstAddress, srcAddress, dstPort, srcPort, 0, 0, TCP_RST, null)
            }
        }
    }

    private suspend fun handleTcpData(
        connKey: String,
        data: ByteArray,
        seqNum: Int,
        ackNum: Int,
        srcPort: Int,
        dstPort: Int,
        srcAddress: ByteArray,
        dstAddress: ByteArray,
        vpnOutput: FileOutputStream
    ) {
        val connection = tcpConnections[connKey]
        if (connection == null) {
            sendTcpPacket(vpnOutput, dstAddress, srcAddress, dstPort, srcPort, 0, 0, TCP_RST, null)
            return
        }

        if (!connection.connected) return

        scope.launch {
            try {
                Log.d(TAG, "发送数据 ($connKey): ${data.size} bytes")

                connection.webSocket?.send(data)
                connection.clientSeq = seqNum + data.size

                sendTcpPacket(
                    vpnOutput,
                    dstAddress, srcAddress,
                    dstPort, srcPort,
                    connection.serverSeq,
                    connection.clientSeq,
                    TCP_ACK,
                    null
                )

            } catch (e: Exception) {
                Log.e(TAG, "发送失败 ($connKey)", e)
                closeTcpConnection(connKey, vpnOutput)
            }
        }
    }

    private fun handleTcpAck(connKey: String, ackNum: Int) {
        // ACK处理
    }

    private suspend fun handleTcpFin(
        connKey: String,
        seqNum: Int,
        ackNum: Int,
        srcPort: Int,
        dstPort: Int,
        srcAddress: ByteArray,
        dstAddress: ByteArray,
        vpnOutput: FileOutputStream
    ) {
        val connection = tcpConnections[connKey]
        if (connection != null) {
            Log.i(TAG, "FIN ($connKey)")

            sendTcpPacket(
                vpnOutput,
                dstAddress, srcAddress,
                dstPort, srcPort,
                connection.serverSeq,
                seqNum + 1,
                TCP_ACK,
                null
            )

            sendTcpPacket(
                vpnOutput,
                dstAddress, srcAddress,
                dstPort, srcPort,
                connection.serverSeq,
                seqNum + 1,
                TCP_FIN or TCP_ACK,
                null
            )
        }

        closeTcpConnection(connKey, vpnOutput)
    }

    private fun handleTcpRst(connKey: String) {
        Log.i(TAG, "RST ($connKey)")
        closeTcpConnection(connKey, null)
    }

    private fun startReceiveLoop(connection: TcpConnection, vpnOutput: FileOutputStream) {
        scope.launch {
            try {
                Log.d(TAG, "开始接收: ${connection.key}")

                while (connection.connected && connection.webSocket?.isConnected() == true) {
                    val data = connection.webSocket?.recv() ?: break

                    if (data.isEmpty()) break

                    Log.d(TAG, "收到数据 (${connection.key}): ${data.size} bytes")

                    sendTcpPacket(
                        vpnOutput,
                        connection.dstAddress,
                        connection.srcAddress,
                        connection.remotePort,
                        connection.localPort,
                        connection.serverSeq,
                        connection.clientSeq,
                        TCP_PSH or TCP_ACK,
                        data
                    )

                    connection.serverSeq += data.size
                }
            } catch (e: Exception) {
                Log.e(TAG, "接收错误 (${connection.key})", e)
            } finally {
                closeTcpConnection(connection.key, vpnOutput)
            }
        }
    }

    private fun sendTcpPacket(
        vpnOutput: FileOutputStream,
        srcAddress: ByteArray,
        dstAddress: ByteArray,
        srcPort: Int,
        dstPort: Int,
        seqNum: Int,
        ackNum: Int,
        flags: Int,
        data: ByteArray?
    ) {
        try {
            val dataLen = data?.size ?: 0
            val tcpHeaderLen = 20
            val ipHeaderLen = 20
            val totalLen = ipHeaderLen + tcpHeaderLen + dataLen

            val packet = ByteBuffer.allocate(totalLen)

            // IP 头
            packet.put(0x45.toByte())
            packet.put(0)
            packet.putShort(totalLen.toShort())
            packet.putShort(0)
            packet.putShort(0x4000.toShort())
            packet.put(64)
            packet.put(6)
            packet.putShort(0)
            packet.put(srcAddress)
            packet.put(dstAddress)

            val ipChecksum = calculateChecksum(packet.array(), 0, ipHeaderLen)
            packet.putShort(10, ipChecksum)

            // TCP 头
            val tcpStart = packet.position()
            packet.putShort(srcPort.toShort())
            packet.putShort(dstPort.toShort())
            packet.putInt(seqNum)
            packet.putInt(ackNum)
            packet.put(0x50.toByte())
            packet.put(flags.toByte())
            packet.putShort(65535.toShort())
            packet.putShort(0)
            packet.putShort(0)

            if (data != null) {
                packet.put(data)
            }

            val tcpChecksum = calculateTcpChecksum(packet.array(), srcAddress, dstAddress, tcpStart, tcpHeaderLen + dataLen)
            packet.putShort(tcpStart + 16, tcpChecksum)

            packet.position(0)
            packet.limit(totalLen)
            vpnOutput.channel.write(packet)

        } catch (e: Exception) {
            Log.e(TAG, "发送TCP包失败", e)
        }
    }

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
        pseudoHeader.put(6)
        pseudoHeader.putShort(tcpLength.toShort())
        pseudoHeader.put(packet, tcpOffset, tcpLength)

        return calculateChecksum(pseudoHeader.array(), 0, pseudoHeader.position())
    }

    private fun closeTcpConnection(connKey: String, vpnOutput: FileOutputStream?) {
        tcpConnections.remove(connKey)?.let { connection ->
            Log.i(TAG, "关闭: $connKey")
            connection.connected = false

            scope.launch {
                try {
                    connection.webSocket?.let { connectionManager?.release(it) }
                } catch (e: Exception) {
                    Log.e(TAG, "释放WebSocket失败", e)
                }
            }
        }
    }

    private fun formatIp(address: ByteArray): String {
        return address.joinToString(".") { (it.toInt() and 0xFF).toString() }
    }

    private fun flagsToString(flags: Int): String {
        val parts = mutableListOf<String>()
        if ((flags and TCP_FIN) != 0) parts.add("FIN")
        if ((flags and TCP_SYN) != 0) parts.add("SYN")
        if ((flags and TCP_RST) != 0) parts.add("RST")
        if ((flags and TCP_PSH) != 0) parts.add("PSH")
        if ((flags and TCP_ACK) != 0) parts.add("ACK")
        return if (parts.isEmpty()) "NONE" else parts.joinToString("|")
    }

    private fun stopVpn() {
        if (!running) return

        scope.launch {
            try {
                updateStatus(ServiceStatus.STOPPING)
                running = false

                processingJob?.cancel()
                processingJob = null

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
                Log.e(TAG, "停止VPN失败", e)
            }
        }
    }

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

            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }

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

        val contentText = if (details.isNotEmpty()) "$status\n$details" else status

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("SecureProxy VPN")
            .setContentText(contentText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(contentText))
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(status: String, details: String = "") {
        getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, createNotification(status, details))
    }

    private fun updateStatus(status: ServiceStatus, message: String? = null) {
        statusCallback?.invoke(status.copy(message = message))
    }

    fun setStatusCallback(callback: (ServiceStatus) -> Unit) {
        statusCallback = callback
    }

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

    private data class TcpConnection(
        val key: String,
        val localPort: Int,
        val remoteHost: String,
        val remotePort: Int,
        var webSocket: SecureWebSocket? = null,
        var connected: Boolean = false,
        var clientSeq: Int = 0,
        var serverSeq: Int = 0,
        val srcAddress: ByteArray,
        val dstAddress: ByteArray
    )

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

    data class VpnStats(
        val running: Boolean,
        val tcpConnections: Int,
        val poolAvailable: Int,
        val poolBusy: Int,
        val poolTotal: Int
    )
}