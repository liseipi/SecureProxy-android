package com.musicses.secureproxy

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.musicses.secureproxy.adapter.ServerConfigAdapter
import com.musicses.secureproxy.databinding.ActivityMainBinding
import com.musicses.secureproxy.model.ProxyConfig
import com.musicses.secureproxy.model.ServerConfig
import com.musicses.secureproxy.model.ServerConfigManager
import com.musicses.secureproxy.service.VpnProxyService
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
        private const val REQUEST_NOTIFICATION_PERMISSION = 100
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var configManager: ServerConfigManager
    private lateinit var configAdapter: ServerConfigAdapter

    private var vpnService: VpnProxyService? = null
    private var serviceBound = false

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            actuallyStartVpn()
        } else {
            Toast.makeText(this, "需要 VPN 权限才能启用全局代理", Toast.LENGTH_LONG).show()
            updateUIState(running = false)
        }
    }

    private val configEditorLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            loadConfigs()
        }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            try {
                val binder = service as VpnProxyService.VpnBinder
                vpnService = binder.getService()
                serviceBound = true

                vpnService?.setStatusCallback { status ->
                    runOnUiThread {
                        updateUI(status)
                    }
                }

                startStatsUpdate()
                Log.d(TAG, "Service connected successfully")
            } catch (e: Exception) {
                Log.e(TAG, "Error in onServiceConnected", e)
                Toast.makeText(this@MainActivity, "服务连接失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            vpnService = null
            serviceBound = false
            Log.d(TAG, "Service disconnected")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        try {
            Log.d(TAG, "MainActivity onCreate started")

            binding = ActivityMainBinding.inflate(layoutInflater)
            setContentView(binding.root)

            Log.d(TAG, "ViewBinding initialized")

            configManager = ServerConfigManager(this)

            requestNotificationPermissionIfNeeded()
            setupUI()
            setupConfigList()
            loadConfigs()

            Log.d(TAG, "MainActivity created successfully")

        } catch (e: Exception) {
            Log.e(TAG, "Error in onCreate", e)
            Toast.makeText(this, "启动错误: ${e.message}", Toast.LENGTH_LONG).show()

            MaterialAlertDialogBuilder(this)
                .setTitle("启动错误")
                .setMessage("应用启动失败:\n${e.message}\n\n${e.stackTraceToString()}")
                .setPositiveButton("退出") { _, _ -> finish() }
                .setCancelable(false)
                .show()
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(
                    arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),
                    REQUEST_NOTIFICATION_PERMISSION
                )
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == REQUEST_NOTIFICATION_PERMISSION) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Log.d(TAG, "Notification permission granted")
            } else {
                Toast.makeText(this, "通知权限被拒绝，部分功能可能无法使用", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun setupUI() {
        try {
            binding.btnStart.setOnClickListener {
                startVpn()
            }

            binding.btnStop.setOnClickListener {
                stopVpnService()
            }

            binding.btnAddConfig.setOnClickListener {
                addNewConfig()
            }

            binding.btnHelp.setOnClickListener {
                showHelp()
            }

            // 初始化空状态视图
            updateEmptyState()

            Log.d(TAG, "UI setup completed")
        } catch (e: Exception) {
            Log.e(TAG, "Error in setupUI", e)
            throw e
        }
    }

    private fun setupConfigList() {
        configAdapter = ServerConfigAdapter(
            onItemClick = { position, config ->
                selectConfig(position)
            },
            onEditClick = { position, config ->
                editConfig(position)
            },
            onDeleteClick = { position, config ->
                deleteConfig(position, config)
            }
        )

        binding.recyclerConfigs.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = configAdapter
        }
    }

    private fun loadConfigs() {
        lifecycleScope.launch {
            try {
                val configs = configManager.loadConfigs()
                configAdapter.submitList(configs)

                val selectedIndex = configManager.getSelectedIndex()
                configAdapter.setSelectedPosition(selectedIndex)

                updateEmptyState()
                updateSelectedConfigInfo()

            } catch (e: Exception) {
                Log.e(TAG, "Error loading configs", e)
                Toast.makeText(this@MainActivity, "加载配置失败", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun selectConfig(position: Int) {
        configManager.setSelectedIndex(position)
        configAdapter.setSelectedPosition(position)
        updateSelectedConfigInfo()
        Toast.makeText(this, "已选择配置", Toast.LENGTH_SHORT).show()
    }

    private fun addNewConfig() {
        val intent = Intent(this, ConfigEditorActivity::class.java).apply {
            putExtra(ConfigEditorActivity.EXTRA_MODE, ConfigEditorActivity.MODE_ADD)
        }
        configEditorLauncher.launch(intent)
    }

    private fun editConfig(position: Int) {
        val intent = Intent(this, ConfigEditorActivity::class.java).apply {
            putExtra(ConfigEditorActivity.EXTRA_MODE, ConfigEditorActivity.MODE_EDIT)
            putExtra(ConfigEditorActivity.EXTRA_CONFIG_INDEX, position)
        }
        configEditorLauncher.launch(intent)
    }

    private fun deleteConfig(position: Int, config: ServerConfig) {
        MaterialAlertDialogBuilder(this)
            .setTitle("删除配置")
            .setMessage("确定要删除配置 \"${config.name}\" 吗？")
            .setPositiveButton("删除") { _, _ ->
                lifecycleScope.launch {
                    try {
                        configManager.deleteConfig(position)
                        loadConfigs()
                        Toast.makeText(this@MainActivity, "配置已删除", Toast.LENGTH_SHORT).show()
                    } catch (e: Exception) {
                        Toast.makeText(this@MainActivity, "删除失败: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun updateEmptyState() {
        val isEmpty = configAdapter.currentList.isEmpty()
        binding.emptyView.visibility = if (isEmpty) View.VISIBLE else View.GONE
        binding.recyclerConfigs.visibility = if (isEmpty) View.GONE else View.VISIBLE
        binding.btnStart.isEnabled = !isEmpty
    }

    private fun updateSelectedConfigInfo() {
        lifecycleScope.launch {
            val selectedConfig = configManager.getSelectedConfig()
            if (selectedConfig != null) {
                binding.selectedConfigInfo.visibility = View.VISIBLE
                binding.selectedConfigInfo.text = "当前选中: ${selectedConfig.name}"
            } else {
                binding.selectedConfigInfo.visibility = View.GONE
            }
        }
    }

    private fun startVpn() {
        lifecycleScope.launch {
            val selectedConfig = configManager.getSelectedConfig()

            if (selectedConfig == null) {
                Toast.makeText(this@MainActivity, "请先选择一个配置", Toast.LENGTH_SHORT).show()
                return@launch
            }

            val validationError = selectedConfig.getValidationError()
            if (validationError != null) {
                MaterialAlertDialogBuilder(this@MainActivity)
                    .setTitle("配置错误")
                    .setMessage(validationError)
                    .setPositiveButton("确定", null)
                    .show()
                return@launch
            }

            requestVpnPermission()
        }
    }

    private fun requestVpnPermission() {
        try {
            val intent = VpnService.prepare(this)
            if (intent != null) {
                vpnPermissionLauncher.launch(intent)
            } else {
                actuallyStartVpn()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error requesting VPN permission", e)
            Toast.makeText(this, "请求 VPN 权限失败: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun actuallyStartVpn() {
        lifecycleScope.launch {
            try {
                val selectedConfig = configManager.getSelectedConfig()
                if (selectedConfig == null) {
                    Toast.makeText(this@MainActivity, "未选择配置", Toast.LENGTH_SHORT).show()
                    return@launch
                }

                val proxyConfig = selectedConfig.toProxyConfig()

                val intent = Intent(this@MainActivity, VpnProxyService::class.java).apply {
                    action = VpnProxyService.ACTION_START
                    putExtra(VpnProxyService.EXTRA_CONFIG, proxyConfig)
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(intent)
                } else {
                    startService(intent)
                }

                bindService(Intent(this@MainActivity, VpnProxyService::class.java), serviceConnection, Context.BIND_AUTO_CREATE)

                updateUIState(running = true)

                Log.d(TAG, "VPN service starting with config: ${selectedConfig.name}")
            } catch (e: Exception) {
                Log.e(TAG, "Error starting VPN", e)
                Toast.makeText(this@MainActivity, "启动 VPN 失败: ${e.message}", Toast.LENGTH_LONG).show()
                updateUIState(running = false)
            }
        }
    }

    private fun stopVpnService() {
        try {
            val intent = Intent(this, VpnProxyService::class.java).apply {
                action = VpnProxyService.ACTION_STOP
            }

            startService(intent)

            if (serviceBound) {
                unbindService(serviceConnection)
                serviceBound = false
            }

            updateUIState(running = false)

            Log.d(TAG, "VPN service stopping")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping VPN", e)
            Toast.makeText(this, "停止 VPN 失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateUIState(running: Boolean) {
        try {
            binding.btnStart.isEnabled = !running && configAdapter.currentList.isNotEmpty()
            binding.btnStop.isEnabled = running
            binding.btnAddConfig.isEnabled = !running

            binding.recyclerConfigs.isEnabled = !running

            if (running) {
                binding.statusText.text = "VPN 运行中"
                binding.statusText.setTextColor(getColor(android.R.color.holo_green_dark))
            } else {
                binding.statusText.text = "VPN 已停止"
                binding.statusText.setTextColor(getColor(android.R.color.holo_red_dark))
                binding.statsText.text = ""
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error updating UI state", e)
        }
    }

    private fun updateUI(status: VpnProxyService.ServiceStatus) {
        try {
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
        } catch (e: Exception) {
            Log.e(TAG, "Error updating UI", e)
        }
    }

    private fun startStatsUpdate() {
        lifecycleScope.launch {
            try {
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
            } catch (e: Exception) {
                Log.e(TAG, "Error in stats update", e)
            }
        }
    }

    private fun showHelp() {
        val message = """
            SecureProxy VPN 客户端
            
            配置管理:
            • 点击"添加配置"创建新的服务器配置
            • 点击配置卡片选择要使用的配置
            • 点击"编辑"修改配置
            • 点击"删除"删除配置
            
            配置说明:
            • 配置名称: 用于标识配置
            • SNI 主机: 服务器域名
            • 代理地址: 服务器 IP 或域名
            • 服务器端口: 通常为 2053
            • 路径: WebSocket 路径,默认 /v1
            • PSK: 64 位十六进制预共享密钥
            
            使用方法:
            1. 添加并选择一个配置
            2. 点击"启动 VPN"
            3. 授予 VPN 权限
            4. 所有应用将自动通过代理连接
            
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
        try {
            if (serviceBound) {
                unbindService(serviceConnection)
            }
            Log.d(TAG, "MainActivity destroyed")
        } catch (e: Exception) {
            Log.e(TAG, "Error in onDestroy", e)
        }
    }
}