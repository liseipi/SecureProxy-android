package com.secureproxy.android.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.secureproxy.android.data.model.ProxyConfig
import com.secureproxy.android.data.model.ProxyStatus
import com.secureproxy.android.ui.components.*
import com.secureproxy.android.ui.viewmodel.ProxyViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(viewModel: ProxyViewModel) {
    val configs by viewModel.configs.collectAsState()
    val activeConfig by viewModel.activeConfig.collectAsState()
    val status by viewModel.status.collectAsState()
    val isRunning by viewModel.isRunning.collectAsState()
    val trafficStats by viewModel.trafficStats.collectAsState()
    val logs by viewModel.logs.collectAsState()
    
    var showConfigEditor by remember { mutableStateOf(false) }
    var editingConfig by remember { mutableStateOf<ProxyConfig?>(null) }
    var showImportDialog by remember { mutableStateOf(false) }
    var showLogsDialog by remember { mutableStateOf(false) }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("SecureProxy") },
                actions = {
                    IconButton(onClick = { showLogsDialog = true }) {
                        Icon(Icons.Default.Info, "日志")
                    }
                    IconButton(onClick = { showImportDialog = true }) {
                        Icon(Icons.Default.Add, "导入")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = {
                    editingConfig = ProxyConfig(
                        name = "新配置",
                        sniHost = "example.com",
                        proxyIP = "example.com",
                        path = "/ws",
                        serverPort = 443,
                        socksPort = 1080,
                        httpPort = 1081,
                        preSharedKey = ""
                    )
                    showConfigEditor = true
                }
            ) {
                Icon(Icons.Default.Add, "添加配置")
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // 状态栏
            StatusCard(
                config = activeConfig,
                status = status,
                isRunning = isRunning,
                trafficStats = trafficStats,
                onToggle = { if (isRunning) viewModel.stop() else viewModel.start() }
            )
            
            Divider()
            
            // 配置列表
            if (configs.isEmpty()) {
                EmptyState(
                    onAddConfig = {
                        editingConfig = ProxyConfig(
                            name = "新配置",
                            sniHost = "example.com",
                            proxyIP = "example.com",
                            path = "/ws",
                            serverPort = 443,
                            socksPort = 1080,
                            httpPort = 1081,
                            preSharedKey = ""
                        )
                        showConfigEditor = true
                    },
                    onImport = { showImportDialog = true }
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(configs, key = { it.id }) { config ->
                        ConfigCard(
                            config = config,
                            isActive = activeConfig?.id == config.id,
                            onSelect = { viewModel.switchConfig(config) },
                            onEdit = {
                                editingConfig = config
                                showConfigEditor = true
                            },
                            onDelete = { viewModel.deleteConfig(config) },
                            onCopyUrl = {
                                // TODO: 复制到剪贴板
                            }
                        )
                    }
                }
            }
        }
    }
    
    // 配置编辑对话框
    if (showConfigEditor && editingConfig != null) {
        ConfigEditorDialog(
            config = editingConfig!!,
            onSave = { config ->
                viewModel.saveConfig(config)
                showConfigEditor = false
                editingConfig = null
            },
            onDismiss = {
                showConfigEditor = false
                editingConfig = null
            }
        )
    }
    
    // 导入对话框
    if (showImportDialog) {
        ImportDialog(
            onImport = { url ->
                viewModel.importFromUrl(url)
                showImportDialog = false
            },
            onDismiss = { showImportDialog = false }
        )
    }
    
    // 日志对话框
    if (showLogsDialog) {
        LogsDialog(
            logs = logs,
            onClear = { viewModel.clearLogs() },
            onDismiss = { showLogsDialog = false }
        )
    }
}

@Composable
fun EmptyState(
    onAddConfig: () -> Unit,
    onImport: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.Settings,
            contentDescription = null,
            modifier = Modifier.size(80.dp),
            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
        )
        
        Spacer(modifier = Modifier.height(16.dp))
        
        Text(
            text = "还没有配置",
            style = MaterialTheme.typography.titleLarge
        )
        
        Text(
            text = "添加或导入配置以开始使用",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        )
        
        Spacer(modifier = Modifier.height(32.dp))
        
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = onAddConfig) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("添加配置")
            }
            
            OutlinedButton(onClick = onImport) {
                Icon(Icons.Default.Download, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("导入链接")
            }
        }
    }
}
