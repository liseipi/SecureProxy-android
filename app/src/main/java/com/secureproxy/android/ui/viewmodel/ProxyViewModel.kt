package com.secureproxy.android.ui.viewmodel

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.secureproxy.android.data.model.ProxyConfig
import com.secureproxy.android.data.model.ProxyStatus
import com.secureproxy.android.data.model.TrafficStats
import com.secureproxy.android.data.repository.ConfigRepository
import com.secureproxy.android.service.ProxyVpnService
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

/**
 * 代理管理 ViewModel（增强版）
 */
class ProxyViewModel(application: Application) : AndroidViewModel(application) {
    
    private val repository = ConfigRepository(application)
    
    // 配置列表
    val configs: StateFlow<List<ProxyConfig>> = repository.getConfigsFlow()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    
    // 活跃配置
    val activeConfig: StateFlow<ProxyConfig?> = combine(
        configs,
        repository.getActiveConfigIdFlow()
    ) { configList, activeId ->
        configList.firstOrNull { it.id == activeId }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    
    // 代理状态
    private val _status = MutableStateFlow(ProxyStatus.DISCONNECTED)
    val status: StateFlow<ProxyStatus> = _status.asStateFlow()
    
    // 运行状态
    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()
    
    // 流量统计
    private val _trafficStats = MutableStateFlow(TrafficStats())
    val trafficStats: StateFlow<TrafficStats> = _trafficStats.asStateFlow()
    
    // 日志
    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs.asStateFlow()
    
    // VPN 服务连接
    private var vpnServiceBinder: ProxyVpnService.VpnServiceBinder? = null
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            // 可以用于获取服务实例和流量统计
        }
        
        override fun onServiceDisconnected(name: ComponentName?) {
            vpnServiceBinder = null
        }
    }
    
    init {
        // 启动流量统计模拟（实际应该从 VPN 服务获取）
        startTrafficMonitoring()
    }
    
    /**
     * 启动流量监控
     */
    private fun startTrafficMonitoring() {
        viewModelScope.launch {
            while (true) {
                if (_isRunning.value) {
                    // 模拟流量数据（实际应该从 VPN 服务获取）
                    _trafficStats.value = _trafficStats.value.copy(
                        uploadSpeed = kotlin.random.Random.nextDouble(0.0, 500.0),
                        downloadSpeed = kotlin.random.Random.nextDouble(0.0, 1000.0),
                        totalUpload = _trafficStats.value.totalUpload + kotlin.random.Random.nextLong(0, 10240),
                        totalDownload = _trafficStats.value.totalDownload + kotlin.random.Random.nextLong(0, 20480)
                    )
                }
                kotlinx.coroutines.delay(1000)
            }
        }
    }
    
    /**
     * 添加日志
     */
    private fun addLog(message: String) {
        val timestamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
            .format(java.util.Date())
        val logs = _logs.value.toMutableList()
        logs.add("[$timestamp] $message")
        if (logs.size > 500) {
            logs.removeAt(0)
        }
        _logs.value = logs
    }
    
    /**
     * 清除日志
     */
    fun clearLogs() {
        _logs.value = emptyList()
        addLog("🗑️ 日志已清除")
    }
    
    /**
     * 保存配置
     */
    fun saveConfig(config: ProxyConfig) {
        viewModelScope.launch {
            val existing = configs.value.firstOrNull { it.id == config.id }
            if (existing != null) {
                repository.updateConfig(config)
                addLog("💾 更新配置: ${config.name}")
            } else {
                repository.addConfig(config)
                addLog("💾 新增配置: ${config.name}")
            }
        }
    }
    
    /**
     * 删除配置
     */
    fun deleteConfig(config: ProxyConfig) {
        viewModelScope.launch {
            repository.deleteConfig(config.id)
            addLog("🗑️ 删除配置: ${config.name}")
        }
    }
    
    /**
     * 切换活跃配置
     */
    fun switchConfig(config: ProxyConfig) {
        viewModelScope.launch {
            repository.setActiveConfig(config.id)
            addLog("🔄 切换到: ${config.name}")
            
            // 如果正在运行，重启代理
            if (_isRunning.value) {
                addLog("⚠️ 重启代理...")
                stop()
                kotlinx.coroutines.delay(1000)
                start()
            }
        }
    }
    
    /**
     * 启动代理
     */
    fun start() {
        val config = activeConfig.value
        if (config == null) {
            addLog("❌ 未选择配置")
            return
        }
        
        if (_isRunning.value) {
            addLog("⚠️ 代理已在运行")
            return
        }
        
        val context = getApplication<Application>()
        _status.value = ProxyStatus.CONNECTING
        
        val cdnMode = if (config.isCdnMode) " (CDN)" else ""
        addLog("🚀 启动: ${config.sniHost}$cdnMode")
        
        // 启动 VPN 服务
        val intent = Intent(context, ProxyVpnService::class.java).apply {
            action = ProxyVpnService.ACTION_START
            putExtra(ProxyVpnService.EXTRA_CONFIG, config.toUrl())
        }
        
        try {
            context.startForegroundService(intent)
            _isRunning.value = true
            _status.value = ProxyStatus.CONNECTED
            _trafficStats.value = TrafficStats() // 重置流量统计
            addLog("✅ 代理已启动 - SOCKS5:${config.socksPort} HTTP:${config.httpPort}")
        } catch (e: Exception) {
            addLog("❌ 启动失败: ${e.message}")
            _status.value = ProxyStatus.DISCONNECTED
        }
    }
    
    /**
     * 停止代理
     */
    fun stop() {
        if (!_isRunning.value) {
            return
        }
        
        val context = getApplication<Application>()
        addLog("🛑 停止代理...")
        
        val intent = Intent(context, ProxyVpnService::class.java).apply {
            action = ProxyVpnService.ACTION_STOP
        }
        
        context.startService(intent)
        _isRunning.value = false
        _status.value = ProxyStatus.DISCONNECTED
        addLog("✅ 已停止")
    }
    
    /**
     * 从 URL 导入配置
     */
    fun importFromUrl(urlString: String): Boolean {
        val config = ProxyConfig.fromUrl(urlString.trim())
        if (config == null) {
            addLog("❌ 无效链接")
            return false
        }
        
        // 检查重名
        var finalConfig = config
        val existingNames = configs.value.map { it.name }
        if (config.name in existingNames) {
            finalConfig = config.copy(name = "${config.name} (导入)")
        }
        
        saveConfig(finalConfig)
        addLog("✅ 导入: ${finalConfig.name}")
        return true
    }
    
    /**
     * 导出配置 URL
     */
    fun getConfigUrl(config: ProxyConfig): String {
        return config.toUrl()
    }
    
    /**
     * 导出所有配置为 JSON
     */
    suspend fun exportAllConfigsJson(): String {
        return repository.exportConfigsJson()
    }
    
    /**
     * 从 JSON 导入配置
     */
    suspend fun importConfigsJson(jsonString: String): Result<Int> {
        val result = repository.importConfigsJson(jsonString)
        result.onSuccess { count ->
            addLog("✅ 导入 $count 个配置")
        }.onFailure {
            addLog("❌ 导入失败: ${it.message}")
        }
        return result
    }
    
    override fun onCleared() {
        super.onCleared()
        // 清理服务连接
        try {
            getApplication<Application>().unbindService(serviceConnection)
        } catch (e: Exception) {
            // 忽略
        }
    }
}
