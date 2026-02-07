package com.secureproxy.android.service

import android.os.ParcelFileDescriptor
import android.util.Log
import com.secureproxy.android.data.model.ProxyConfig
import com.secureproxy.android.network.SecureWebSocket
import kotlinx.coroutines.*
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer

/**
 * VPN 数据包处理器
 * 负责从 TUN 接口读取数据包,通过 WebSocket 转发,并写回响应
 */
class VpnPacketHandler(
    private val vpnInterface: ParcelFileDescriptor,
    private val config: ProxyConfig,
    private val scope: CoroutineScope,
    private val onTraffic: (upload: Long, download: Long) -> Unit
) {
    companion object {
        private const val TAG = "VpnPacketHandler"
        private const val MTU = 1500
        private const val BUFFER_SIZE = 32 * 1024 // 32KB
    }

    private val inputStream = FileInputStream(vpnInterface.fileDescriptor)
    private val outputStream = FileOutputStream(vpnInterface.fileDescriptor)

    private var webSocket: SecureWebSocket? = null
    private var readJob: Job? = null
    private var writeJob: Job? = null

    private var uploadBytes = 0L
    private var downloadBytes = 0L

    /**
     * 启动数据包处理
     */
    suspend fun start() {
        Log.d(TAG, "Starting packet handler")

        // 1. 建立 WebSocket 连接
        webSocket = SecureWebSocket(config, scope)
        try {
            webSocket?.connect()
            Log.d(TAG, "WebSocket connected")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to connect WebSocket", e)
            throw e
        }

        // 2. 启动读取和写入循环
        startReadLoop()
        startWriteLoop()
    }

    /**
     * 从 TUN 读取数据包 → WebSocket
     */
    private fun startReadLoop() {
        readJob = scope.launch(Dispatchers.IO) {
            val buffer = ByteBuffer.allocate(BUFFER_SIZE)

            try {
                while (isActive) {
                    buffer.clear()
                    val length = inputStream.channel.read(buffer)

                    if (length > 0) {
                        buffer.flip()
                        val packet = ByteArray(length)
                        buffer.get(packet)

                        // 解析 IP 数据包
                        val ipPacket = parseIpPacket(packet)
                        if (ipPacket != null) {
                            Log.v(TAG, "Read packet: ${ipPacket.protocol} ${ipPacket.srcAddr}:${ipPacket.srcPort} -> ${ipPacket.dstAddr}:${ipPacket.dstPort}")

                            // 通过 WebSocket 转发
                            forwardToRemote(ipPacket)

                            uploadBytes += length
                            onTraffic(length.toLong(), 0)
                        }
                    }
                }
            } catch (e: Exception) {
                if (isActive) {
                    Log.e(TAG, "Read loop error", e)
                }
            }
        }
    }

    /**
     * WebSocket → TUN 写入数据包
     */
    private fun startWriteLoop() {
        writeJob = scope.launch(Dispatchers.IO) {
            try {
                while (isActive) {
                    val data = webSocket?.receive() ?: break

                    // 将数据写回 TUN 接口
                    outputStream.write(data)
                    outputStream.flush()

                    downloadBytes += data.size
                    onTraffic(0, data.size.toLong())

                    Log.v(TAG, "Wrote ${data.size} bytes to TUN")
                }
            } catch (e: Exception) {
                if (isActive) {
                    Log.e(TAG, "Write loop error", e)
                }
            }
        }
    }

    /**
     * 转发数据包到远程服务器
     */
    private suspend fun forwardToRemote(packet: IpPacket) {
        try {
            when (packet.protocol) {
                Protocol.TCP -> {
                    // 发送 CONNECT 命令建立连接
                    if (!packet.isEstablished) {
                        webSocket?.sendConnect(packet.dstAddr, packet.dstPort)
                    }

                    // 转发 TCP 数据
                    if (packet.payload.isNotEmpty()) {
                        webSocket?.send(packet.payload)
                    }
                }

                Protocol.UDP -> {
                    // UDP 直接转发
                    webSocket?.send(packet.payload)
                }

                else -> {
                    Log.w(TAG, "Unsupported protocol: ${packet.protocol}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Forward error", e)
        }
    }

    /**
     * 解析 IP 数据包
     */
    private fun parseIpPacket(data: ByteArray): IpPacket? {
        if (data.size < 20) return null

        val version = (data[0].toInt() shr 4) and 0x0F
        if (version != 4) return null // 只支持 IPv4

        val headerLength = (data[0].toInt() and 0x0F) * 4
        val protocol = when (data[9].toInt() and 0xFF) {
            6 -> Protocol.TCP
            17 -> Protocol.UDP
            else -> Protocol.UNKNOWN
        }

        val srcAddr = "${data[12].toUByte()}.${data[13].toUByte()}.${data[14].toUByte()}.${data[15].toUByte()}"
        val dstAddr = "${data[16].toUByte()}.${data[17].toUByte()}.${data[18].toUByte()}.${data[19].toUByte()}"

        val srcPort: Int
        val dstPort: Int
        val payload: ByteArray

        when (protocol) {
            Protocol.TCP, Protocol.UDP -> {
                if (data.size < headerLength + 4) return null

                srcPort = ((data[headerLength].toInt() and 0xFF) shl 8) or (data[headerLength + 1].toInt() and 0xFF)
                dstPort = ((data[headerLength + 2].toInt() and 0xFF) shl 8) or (data[headerLength + 3].toInt() and 0xFF)

                val dataOffset = if (protocol == Protocol.TCP) {
                    headerLength + ((data[headerLength + 12].toInt() shr 4) and 0x0F) * 4
                } else {
                    headerLength + 8 // UDP header
                }

                payload = if (data.size > dataOffset) {
                    data.copyOfRange(dataOffset, data.size)
                } else {
                    ByteArray(0)
                }
            }

            else -> {
                srcPort = 0
                dstPort = 0
                payload = ByteArray(0)
            }
        }

        return IpPacket(
            protocol = protocol,
            srcAddr = srcAddr,
            dstAddr = dstAddr,
            srcPort = srcPort,
            dstPort = dstPort,
            payload = payload,
            rawData = data
        )
    }

    /**
     * 停止处理
     */
    fun stop() {
        Log.d(TAG, "Stopping packet handler")

        readJob?.cancel()
        writeJob?.cancel()

        runBlocking {
            webSocket?.close()
        }

        try {
            inputStream.close()
            outputStream.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing streams", e)
        }
    }

    /**
     * IP 数据包
     */
    data class IpPacket(
        val protocol: Protocol,
        val srcAddr: String,
        val dstAddr: String,
        val srcPort: Int,
        val dstPort: Int,
        val payload: ByteArray,
        val rawData: ByteArray,
        val isEstablished: Boolean = false
    )

    /**
     * 协议类型
     */
    enum class Protocol {
        TCP, UDP, UNKNOWN
    }
}