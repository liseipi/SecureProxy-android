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
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * VPN 代理服务 - 完整修复版
 *
 * 新增功能:
 * 1. DNS 查询支持 (UDP 53)
 * 2. 完整的 TCP 三次握手
 * 3. TCP 状态机管理
 * 4. ICMP 支持(可选)
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

        // TCP 状态
        private const val TCP_STATE_LISTEN = 0
        private const val TCP_STATE_SYN_SENT = 1
        private const val TCP_STATE_SYN_RECEIVED = 2
        private const val TCP_STATE_ESTABLISHED = 3
        private const val TCP_STATE_FIN_WAIT_1 = 4
        private const val TCP_STATE_FIN_WAIT_2 = 5
        private const val TCP_STATE_CLOSE_WAIT = 6
        private const val TCP_STATE_CLOSING = 7
        private const val TCP_STATE_LAST_ACK = 8
        private const val TCP_STATE_TIME_WAIT = 9
        private const val TCP_STATE_CLOSED = 10
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

    // DNS 缓存
    private val dnsCache = ConcurrentHashMap<String, InetAddress>()

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
        val totalLength = packet.short.toInt() and 0xFFFF
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
            6 -> {  // TCP
                processTcpPacket(packet, srcAddress, dstAddress, headerStart, ihl, vpnOutput)
            }
            17 -> {  // UDP
                processUdpPacket(packet, srcAddress, dstAddress, headerStart, ihl, vpnOutput)
            }
            1 -> {  // ICMP
                Log.d(TAG, "ICMP: ${formatIp(srcAddress)} -> ${formatIp(dstAddress)}")
                // ICMP 暂时忽略，可以根据需要实现
            }
        }
    }

    /**
     * 处理 UDP 包 - 主要用于 DNS 查询
     */
    private suspend fun processUdpPacket(
        packet: ByteBuffer,
        srcAddress: ByteArray,
        dstAddress: ByteArray,
        ipHeaderStart: Int,
        ipHeaderLength: Int,
        vpnOutput: FileOutputStream
    ) = withContext(Dispatchers.IO) {
        if (packet.remaining() < 8) return@withContext

        val udpStart = packet.position()
        val srcPort = packet.short.toInt() and 0xFFFF
        val dstPort = packet.short.toInt() and 0xFFFF
        val udpLength = packet.short.toInt() and 0xFFFF
        packet.short // checksum

        val dataLength = udpLength - 8
        if (dataLength <= 0) return@withContext

        val data = ByteArray(dataLength)
        packet.get(data)

        val dstHost = formatIp(dstAddress)

        Log.d(TAG, "UDP: ${formatIp(srcAddress)}:$srcPort -> $dstHost:$dstPort (${data.size} bytes)")

        // 处理 DNS 查询 (端口 53)
        if (dstPort == 53) {
            try {
                val dnsResponse = performDnsQuery(data, dstHost)

                // 发送 DNS 响应回客户端
                sendUdpPacket(
                    vpnOutput,
                    dstAddress,     // 交换源和目标
                    srcAddress,
                    dstPort,
                    srcPort,
                    dnsResponse
                )

                Log.d(TAG, "DNS 查询成功，返回 ${dnsResponse.size} bytes")
            } catch (e: Exception) {
                Log.e(TAG, "DNS 查询失败: ${e.message}")
            }
        } else {
            // 其他 UDP 流量暂时忽略或可以通过代理转发
            Log.d(TAG, "非 DNS UDP 流量，端口 $dstPort，暂时忽略")
        }
    }

    /**
     * 执行 DNS 查询
     */
    private fun performDnsQuery(query: ByteArray, dnsServer: String): ByteArray {
        val socket = DatagramSocket()
        socket.soTimeout = 5000  // 5秒超时

        try {
            // 发送 DNS 查询
            val address = InetAddress.getByName(dnsServer)
            val sendPacket = DatagramPacket(query, query.size, address, 53)
            socket.send(sendPacket)

            // 接收 DNS 响应
            val receiveBuffer = ByteArray(512)
            val receivePacket = DatagramPacket(receiveBuffer, receiveBuffer.size)
            socket.receive(receivePacket)

            return receiveBuffer.copyOf(receivePacket.length)
        } finally {
            socket.close()
        }
    }

    /**
     * 发送 UDP 包
     */
    private fun sendUdpPacket(
        vpnOutput: FileOutputStream,
        srcAddress: ByteArray,
        dstAddress: ByteArray,
        srcPort: Int,
        dstPort: Int,
        data: ByteArray
    ) {
        try {
            val udpHeaderLen = 8
            val ipHeaderLen = 20
            val totalLen = ipHeaderLen + udpHeaderLen + data.size

            val packet = ByteBuffer.allocate(totalLen)

            // IP 头
            packet.put(0x45.toByte())
            packet.put(0)
            packet.putShort(totalLen.toShort())
            packet.putShort(0)
            packet.putShort(0x4000.toShort())
            packet.put(64)
            packet.put(17)  // UDP
            packet.putShort(0)
            packet.put(srcAddress)
            packet.put(dstAddress)

            val ipChecksum = calculateChecksum(packet.array(), 0, ipHeaderLen)
            packet.putShort(10, ipChecksum)

            // UDP 头
            val udpStart = packet.position()
            packet.putShort(srcPort.toShort())
            packet.putShort(dstPort.toShort())
            packet.putShort((udpHeaderLen + data.size).toShort())
            packet.putShort(0)  // checksum (可以为0)

            // 数据
            packet.put(data)

            packet.position(0)
            packet.limit(totalLen)
            vpnOutput.channel.write(packet)

        } catch (e: Exception) {
            Log.e(TAG, "发送UDP包失败", e)
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

        Log.d(TAG, "$connKey [${flagsToString(flags)}] seq=$seqNum ack=$ackNum len=$payloadLength")

        try {
            when {
                // 第一步: 客户端发送 SYN
                syn && !ack -> {
                    handleTcpSyn(connKey, srcPort, dstHost, dstPort, seqNum, srcAddress, dstAddress, vpnOutput)
                }
                // 第三步: 客户端发送 ACK (三次握手完成)
                ack && !syn && !fin && !psh && payloadLength == 0 -> {
                    handleTcpAckForHandshake(connKey, ackNum)
                }
                // 数据传输
                payloadLength > 0 -> {
                    val payload = ByteArray(payloadLength)
                    packet.get(payload)
                    handleTcpData(connKey, payload, seqNum, ackNum, srcPort, dstPort, srcAddress, dstAddress, vpnOutput)
                }
                // FIN 关闭连接
                fin -> {
                    handleTcpFin(connKey, seqNum, ackNum, srcPort, dstPort, srcAddress, dstAddress, vpnOutput)
                }
                // RST 重置连接
                rst -> {
                    handleTcpRst(connKey)
                }
                // 其他 ACK
                ack -> {
                    handleTcpAck(connKey, ackNum)
                }
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
        if (tcpConnections.containsKey(connKey)) {
            Log.w(TAG, "连接已存在: $connKey")
            return
        }

        scope.launch {
            try {
                Log.i(TAG, "新连接 SYN: $connKey")

                val ws = connectionManager?.acquire() ?: throw Exception("无法获取WebSocket")

                ws.sendConnect(dstHost, dstPort)
                Log.d(TAG, "已连接远程: $dstHost:$dstPort")

                // 生成初始服务器序列号
                val serverSeq = (Math.random() * Int.MAX_VALUE).toInt()

                val connection = TcpConnection(
                    key = connKey,
                    localPort = srcPort,
                    remoteHost = dstHost,
                    remotePort = dstPort,
                    webSocket = ws,
                    state = TCP_STATE_SYN_RECEIVED,  // SYN_RECEIVED 状态
                    clientSeq = AtomicInteger(clientSeq + 1),
                    serverSeq = AtomicInteger(serverSeq + 1),
                    srcAddress = srcAddress,
                    dstAddress = dstAddress
                )

                tcpConnections[connKey] = connection

                // 发送 SYN-ACK (第二步)
                sendTcpPacket(
                    vpnOutput,
                    dstAddress, srcAddress,
                    dstPort, srcPort,
                    serverSeq,
                    clientSeq + 1,
                    TCP_SYN or TCP_ACK,
                    null
                )

                Log.d(TAG, "已发送 SYN-ACK: $connKey (seq=$serverSeq, ack=${clientSeq + 1})")

            } catch (e: Exception) {
                Log.e(TAG, "连接失败 ($connKey)", e)
                tcpConnections.remove(connKey)
                sendTcpPacket(vpnOutput, dstAddress, srcAddress, dstPort, srcPort, 0, 0, TCP_RST, null)
            }
        }
    }

    /**
     * 处理三次握手的第三步 ACK
     */
    private fun handleTcpAckForHandshake(connKey: String, ackNum: Int) {
        val connection = tcpConnections[connKey]
        if (connection == null) {
            Log.w(TAG, "收到未知连接的 ACK: $connKey")
            return
        }

        if (connection.state == TCP_STATE_SYN_RECEIVED) {
            connection.state = TCP_STATE_ESTABLISHED
            connection.connected = true
            Log.i(TAG, "连接已建立: $connKey")

            // 启动接收循环
            startReceiveLoop(connection, FileOutputStream(vpnInterface!!.fileDescriptor))
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
            Log.w(TAG, "收到未知连接的数据: $connKey")
            sendTcpPacket(vpnOutput, dstAddress, srcAddress, dstPort, srcPort, 0, 0, TCP_RST, null)
            return
        }

        if (!connection.connected || connection.state != TCP_STATE_ESTABLISHED) {
            Log.w(TAG, "连接未建立，忽略数据: $connKey")
            return
        }

        scope.launch {
            try {
                Log.d(TAG, "发送数据 ($connKey): ${data.size} bytes")

                connection.webSocket?.send(data)

                // 更新期望的客户端序列号
                connection.clientSeq.set(seqNum + data.size)

                // 发送 ACK
                sendTcpPacket(
                    vpnOutput,
                    dstAddress, srcAddress,
                    dstPort, srcPort,
                    connection.serverSeq.get(),
                    connection.clientSeq.get(),
                    TCP_ACK,
                    null
                )

                Log.d(TAG, "已确认接收 ($connKey): ${data.size} bytes")

            } catch (e: Exception) {
                Log.e(TAG, "发送失败 ($connKey)", e)
                closeTcpConnection(connKey, vpnOutput)
            }
        }
    }

    private fun handleTcpAck(connKey: String, ackNum: Int) {
        val connection = tcpConnections[connKey]
        if (connection != null) {
            Log.d(TAG, "[$connKey] 收到 ACK: $ackNum (state=${connection.state})")
        }
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

            connection.state = TCP_STATE_CLOSE_WAIT

            // 发送 ACK 确认 FIN
            sendTcpPacket(
                vpnOutput,
                dstAddress, srcAddress,
                dstPort, srcPort,
                connection.serverSeq.get(),
                seqNum + 1,
                TCP_ACK,
                null
            )

            // 发送我们自己的 FIN
            sendTcpPacket(
                vpnOutput,
                dstAddress, srcAddress,
                dstPort, srcPort,
                connection.serverSeq.get(),
                seqNum + 1,
                TCP_FIN or TCP_ACK,
                null
            )

            connection.serverSeq.incrementAndGet()
            connection.state = TCP_STATE_LAST_ACK
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

                while (connection.connected &&
                    connection.state == TCP_STATE_ESTABLISHED &&
                    connection.webSocket?.isConnected() == true) {

                    val data = connection.webSocket?.recv() ?: break

                    if (data.isEmpty()) break

                    Log.d(TAG, "收到数据 (${connection.key}): ${data.size} bytes")

                    val currentServerSeq = connection.serverSeq.get()

                    sendTcpPacket(
                        vpnOutput,
                        connection.dstAddress,
                        connection.srcAddress,
                        connection.remotePort,
                        connection.localPort,
                        currentServerSeq,
                        connection.clientSeq.get(),
                        TCP_PSH or TCP_ACK,
                        data
                    )

                    connection.serverSeq.addAndGet(data.size)

                    Log.d(TAG, "已发送数据 (${connection.key}): ${data.size} bytes")
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
            connection.state = TCP_STATE_CLOSED

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

    /**
     * TCP 连接 - 增加状态管理
     */
    private data class TcpConnection(
        val key: String,
        val localPort: Int,
        val remoteHost: String,
        val remotePort: Int,
        var webSocket: SecureWebSocket? = null,
        var connected: Boolean = false,
        var state: Int = TCP_STATE_LISTEN,  // TCP 状态
        val clientSeq: AtomicInteger,
        val serverSeq: AtomicInteger,
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