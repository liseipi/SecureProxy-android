package com.secureproxy.android.service

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

/**
 * 流量统计管理器
 */
class TrafficStatsManager(private val scope: CoroutineScope) {
    
    private val uploadBytes = AtomicLong(0)
    private val downloadBytes = AtomicLong(0)
    
    private val _uploadSpeed = MutableStateFlow(0.0) // KB/s
    private val _downloadSpeed = MutableStateFlow(0.0) // KB/s
    
    val uploadSpeed: StateFlow<Double> = _uploadSpeed.asStateFlow()
    val downloadSpeed: StateFlow<Double> = _downloadSpeed.asStateFlow()
    
    private var lastUploadBytes = 0L
    private var lastDownloadBytes = 0L
    private var lastUpdateTime = System.currentTimeMillis()
    
    init {
        startMonitoring()
    }
    
    /**
     * 记录上传流量
     */
    fun recordUpload(bytes: Long) {
        uploadBytes.addAndGet(bytes)
    }
    
    /**
     * 记录下载流量
     */
    fun recordDownload(bytes: Long) {
        downloadBytes.addAndGet(bytes)
    }
    
    /**
     * 获取总上传流量
     */
    fun getTotalUpload(): Long = uploadBytes.get()
    
    /**
     * 获取总下载流量
     */
    fun getTotalDownload(): Long = downloadBytes.get()
    
    /**
     * 启动监控
     */
    private fun startMonitoring() {
        scope.launch(Dispatchers.Default) {
            while (isActive) {
                delay(1000) // 每秒更新一次
                
                val now = System.currentTimeMillis()
                val timeDiff = (now - lastUpdateTime) / 1000.0
                
                if (timeDiff > 0) {
                    val currentUpload = uploadBytes.get()
                    val currentDownload = downloadBytes.get()
                    
                    val uploadDiff = currentUpload - lastUploadBytes
                    val downloadDiff = currentDownload - lastDownloadBytes
                    
                    _uploadSpeed.value = (uploadDiff / timeDiff) / 1024.0 // KB/s
                    _downloadSpeed.value = (downloadDiff / timeDiff) / 1024.0 // KB/s
                    
                    lastUploadBytes = currentUpload
                    lastDownloadBytes = currentDownload
                    lastUpdateTime = now
                }
            }
        }
    }
    
    /**
     * 重置统计
     */
    fun reset() {
        uploadBytes.set(0)
        downloadBytes.set(0)
        lastUploadBytes = 0
        lastDownloadBytes = 0
        _uploadSpeed.value = 0.0
        _downloadSpeed.value = 0.0
    }
}
