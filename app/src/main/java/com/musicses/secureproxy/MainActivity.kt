package com.musicses.secureproxy

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.VpnService
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.musicses.secureproxy.databinding.ActivityMainBinding
import com.musicses.secureproxy.model.ProxyConfig
import com.musicses.secureproxy.service.VpnProxyService
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
        private const val PREFS_NAME = "proxy_config"
        private const val KEY_SNI_HOST = "sni_host"
        private const val KEY_PROXY_IP = "proxy_ip"
        private const val KEY_SERVER_PORT = "server_port"
        private const val KEY_PATH = "path"
        private const val KEY_PSK = "psk"
    }

    private lateinit var binding: ActivityMainBinding
    private var vpnService: VpnProxyService? = null
    private var serviceBound = false

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            // VPN 权限已授予，启动服务
            actuallyStartVpn()
        } else {
            Toast.makeText(this, "需要 VPN 权限才能启用全局代理", Toast.LENGTH_LONG).show()
            updateUIState(running = false)
        }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as VpnProxyService.VpnBinder
            vpnService = binder.getService()
            serviceBound = true

            vpnService?.setStatusCallback { status ->
                runOnUiThread {
                    updateUI(status)
                }
            }

            startStatsUpdate()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            vpnService = null
            serviceBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupUI()
        loadConfig()
    }

    /**
     * 设置 UI
     */
    private fun setupUI() {
        binding.btnStart.setOnClickListener {
            if (validateConfig()) {
                requestVpnPermission()
            }
        }

        binding.btnStop.setOnClickListener {
            stopVpnService()
        }

        binding.btnSave.setOnClickListener {
            saveConfig()
            Toast.makeText(this, "配置已保存", Toast.LENGTH_SHORT).show()
        }

        binding.btnHelp.setOnClickListener {
            showHelp()
        }
    }

    /**
     * 加载配置
     */
    private fun loadConfig() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        binding.editSniHost.setText(prefs.getString(KEY_SNI_HOST, ""))
        binding.editProxyIp.setText(prefs.getString(KEY_PROXY_IP, ""))
        binding.editServerPort.setText(prefs.getString(KEY_SERVER_PORT, "2053"))
        binding.editPath.setText(prefs.getString(KEY_PATH, "/v1"))
        binding.editPsk.setText(prefs.getString(KEY_PSK, ""))
    }

    /**
     * 保存配置
     */
    private fun saveConfig() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().apply {
            putString(KEY_SNI_HOST, binding.editSniHost.text.toString())
            putString(KEY_PROXY_IP, binding.editProxyIp.text.toString())
            putString(KEY_SERVER_PORT, binding.editServerPort.text.toString())
            putString(KEY_PATH, binding.editPath.text.toString())
            putString(KEY_PSK, binding.editPsk.text.toString())
            apply()
        }
    }

    /**
     * 验证配置
     */
    private fun validateConfig(): Boolean {
        val config = getConfigFromUI()

        if (!config.validate()) {
            MaterialAlertDialogBuilder(this)
                .setTitle("配置错误")
                .setMessage("请检查配置:\n" +
                        "- SNI 主机不能为空\n" +
                        "- 代理 IP 不能为空\n" +
                        "- 服务器端口范围: 1-65535\n" +
                        "- 路径不能为空\n" +
                        "- PSK 必须是 64 位十六进制字符")
                .setPositiveButton("确定", null)
                .show()
            return false
        }

        return true
    }

    /**
     * 从 UI 获取配置
     */
    private fun getConfigFromUI(): ProxyConfig {
        return ProxyConfig(
            sniHost = binding.editSniHost.text.toString().trim(),
            proxyIp = binding.editProxyIp.text.toString().trim(),
            serverPort = binding.editServerPort.text.toString().toIntOrNull() ?: 2053,
            path = binding.editPath.text.toString().trim(),
            preSharedKey = binding.editPsk.text.toString().trim(),
            socksPort = 0,  // VPN 模式不需要
            httpPort = 0    // VPN 模式不需要
        )
    }

    /**
     * 请求 VPN 权限
     */
    private fun requestVpnPermission() {
        val intent = VpnService.prepare(this)
        if (intent != null) {
            // 需要请求权限
            vpnPermissionLauncher.launch(intent)
        } else {
            // 已经有权限
            actuallyStartVpn()
        }
    }

    /**
     * 实际启动 VPN
     */
    private fun actuallyStartVpn() {
        saveConfig()

        val config = getConfigFromUI()

        val intent = Intent(this, VpnProxyService::class.java).apply {
            action = VpnProxyService.ACTION_START
            putExtra(VpnProxyService.EXTRA_CONFIG, config)
        }

        startForegroundService(intent)
        bindService(Intent(this, VpnProxyService::class.java), serviceConnection, Context.BIND_AUTO_CREATE)

        updateUIState(running = true)
    }

    /**
     * 停止 VPN 服务
     */
    private fun stopVpnService() {
        val intent = Intent(this, VpnProxyService::class.java).apply {
            action = VpnProxyService.ACTION_STOP
        }

        startService(intent)

        if (serviceBound) {
            unbindService(serviceConnection)
            serviceBound = false
        }

        updateUIState(running = false)
    }

    /**
     * 更新 UI 状态
     */
    private fun updateUIState(running: Boolean) {
        binding.btnStart.isEnabled = !running
        binding.btnStop.isEnabled = running
        binding.btnSave.isEnabled = !running

        binding.editSniHost.isEnabled = !running
        binding.editProxyIp.isEnabled = !running
        binding.editServerPort.isEnabled = !running
        binding.editPath.isEnabled = !running
        binding.editPsk.isEnabled = !running

        if (running) {
            binding.statusText.text = "VPN 运行中"
            binding.statusText.setTextColor(getColor(android.R.color.holo_green_dark))
        } else {
            binding.statusText.text = "VPN 已停止"
            binding.statusText.setTextColor(getColor(android.R.color.holo_red_dark))
            binding.statsText.text = ""
        }
    }

    /**
     * 更新 UI
     */
    private fun updateUI(status: VpnProxyService.ServiceStatus) {
        when (status.state) {
            VpnProxyService.ServiceStatus.State.STARTING -> {
                binding.statusText.text = "正在启动 VPN..."
                binding.statusText.setTextColor(getColor(android.R.color.holo_orange_dark))
            }
            VpnProxyService.ServiceStatus.State.RUNNING -> {
                binding.statusText.text = "VPN 已连接"
                binding.statusText.setTextColor(getColor(android.R.color.holo_green_dark))
            }
            VpnProxyService.ServiceStatus.State.STOPPING -> {
                binding.statusText.text = "正在停止 VPN..."
                binding.statusText.setTextColor(getColor(android.R.color.holo_orange_dark))
            }
            VpnProxyService.ServiceStatus.State.STOPPED -> {
                binding.statusText.text = "VPN 已断开"
                binding.statusText.setTextColor(getColor(android.R.color.holo_red_dark))
                updateUIState(running = false)
            }
            VpnProxyService.ServiceStatus.State.ERROR -> {
                binding.statusText.text = "错误: ${status.message}"
                binding.statusText.setTextColor(getColor(android.R.color.holo_red_dark))
                updateUIState(running = false)
            }
            else -> {}
        }
    }

    /**
     * 启动统计更新
     */
    private fun startStatsUpdate() {
        lifecycleScope.launch {
            while (isActive && serviceBound) {
                vpnService?.let { service ->
                    val stats = service.getStats()

                    if (stats.running) {
                        val statsText = buildString {
                            appendLine("全局代理已启用")
                            appendLine("TCP 连接: ${stats.tcpConnections}")
                            appendLine("连接池: ${stats.poolAvailable}/${stats.poolTotal} 可用")
                        }

                        binding.statsText.text = statsText
                    }
                }

                delay(2000)
            }
        }
    }

    /**
     * 显示帮助
     */
    private fun showHelp() {
        val message = """
            SecureProxy VPN 客户端
            
            配置说明:
            • SNI 主机: 服务器域名
            • 代理 IP: 服务器 IP 地址
            • 服务器端口: 通常为 2053
            • 路径: WebSocket 路径,默认 /v1
            • PSK: 64 位十六进制预共享密钥
            
            使用方法:
            1. 填写服务器配置信息
            2. 点击"保存配置"
            3. 点击"启动 VPN"
            4. 授予 VPN 权限
            5. 所有应用将自动通过代理连接
            
            注意:
            - VPN 模式会接管所有网络流量
            - 启用后无需单独配置代理
            - 系统状态栏会显示 VPN 图标
        """.trimIndent()

        MaterialAlertDialogBuilder(this)
            .setTitle("帮助")
            .setMessage(message)
            .setPositiveButton("确定", null)
            .show()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (serviceBound) {
            unbindService(serviceConnection)
        }
    }
}