package com.secureproxy.android.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.secureproxy.android.data.model.ProxyConfig

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConfigEditorDialog(
    config: ProxyConfig,
    onSave: (ProxyConfig) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf(config.name) }
    var sniHost by remember { mutableStateOf(config.sniHost) }
    var proxyIP by remember { mutableStateOf(config.proxyIP) }
    var path by remember { mutableStateOf(config.path) }
    var serverPort by remember { mutableStateOf(config.serverPort.toString()) }
    var socksPort by remember { mutableStateOf(config.socksPort.toString()) }
    var httpPort by remember { mutableStateOf(config.httpPort.toString()) }
    var preSharedKey by remember { mutableStateOf(config.preSharedKey) }
    
    val isValid = name.isNotBlank() &&
            sniHost.isNotBlank() &&
            proxyIP.isNotBlank() &&
            path.isNotBlank() &&
            serverPort.toIntOrNull()?.let { it in 1..65535 } == true &&
            socksPort.toIntOrNull()?.let { it in 1024..65535 } == true &&
            httpPort.toIntOrNull()?.let { it in 1024..65535 } == true &&
            preSharedKey.length == 64 &&
            preSharedKey.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }
    
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .fillMaxHeight(0.9f)
        ) {
            Column(
                modifier = Modifier.fillMaxSize()
            ) {
                // 标题栏
                TopAppBar(
                    title = { Text(if (config.name.isEmpty() || config.name == "新配置") "添加配置" else "编辑配置") },
                    navigationIcon = {
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Default.Close, "关闭")
                        }
                    },
                    actions = {
                        TextButton(
                            onClick = {
                                val newConfig = config.copy(
                                    name = name,
                                    sniHost = sniHost,
                                    proxyIP = proxyIP,
                                    path = path,
                                    serverPort = serverPort.toInt(),
                                    socksPort = socksPort.toInt(),
                                    httpPort = httpPort.toInt(),
                                    preSharedKey = preSharedKey
                                )
                                onSave(newConfig)
                            },
                            enabled = isValid
                        ) {
                            Text("保存")
                        }
                    }
                )
                
                // 表单内容
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // 基本信息
                    Text(
                        text = "基本信息",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("配置名称") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    
                    OutlinedTextField(
                        value = sniHost,
                        onValueChange = { sniHost = it },
                        label = { Text("SNI 主机名") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        supportingText = { Text("用于 TLS 握手的域名") },
                        trailingIcon = {
                            IconButton(onClick = { proxyIP = sniHost }) {
                                Icon(Icons.Default.ContentCopy, "同步到代理地址")
                            }
                        }
                    )
                    
                    OutlinedTextField(
                        value = proxyIP,
                        onValueChange = { proxyIP = it },
                        label = { Text("代理地址 (Proxy IP)") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        supportingText = {
                            if (proxyIP == sniHost) {
                                Text("直连模式", color = MaterialTheme.colorScheme.secondary)
                            } else {
                                Text("CDN 模式", color = MaterialTheme.colorScheme.tertiary)
                            }
                        }
                    )
                    
                    OutlinedTextField(
                        value = path,
                        onValueChange = { path = it },
                        label = { Text("WebSocket 路径") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    
                    Divider()
                    
                    // 端口设置
                    Text(
                        text = "端口设置",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = serverPort,
                            onValueChange = { serverPort = it },
                            label = { Text("服务器端口") },
                            modifier = Modifier.weight(1f),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            isError = serverPort.toIntOrNull()?.let { it !in 1..65535 } == true
                        )
                        
                        OutlinedTextField(
                            value = socksPort,
                            onValueChange = { socksPort = it },
                            label = { Text("SOCKS5") },
                            modifier = Modifier.weight(1f),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            isError = socksPort.toIntOrNull()?.let { it !in 1024..65535 } == true
                        )
                        
                        OutlinedTextField(
                            value = httpPort,
                            onValueChange = { httpPort = it },
                            label = { Text("HTTP") },
                            modifier = Modifier.weight(1f),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            isError = httpPort.toIntOrNull()?.let { it !in 1024..65535 } == true
                        )
                    }
                    
                    Divider()
                    
                    // 预共享密钥
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "预共享密钥 (PSK)",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        
                        OutlinedButton(
                            onClick = { preSharedKey = ProxyConfig.generatePSK() }
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = null)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("生成")
                        }
                    }
                    
                    OutlinedTextField(
                        value = preSharedKey,
                        onValueChange = { if (it.length <= 64) preSharedKey = it },
                        label = { Text("64 位十六进制字符串") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 3,
                        maxLines = 4,
                        isError = preSharedKey.isNotEmpty() && 
                            (preSharedKey.length != 64 || !preSharedKey.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }),
                        supportingText = {
                            if (preSharedKey.isEmpty()) {
                                Text("请输入密钥或点击生成")
                            } else if (preSharedKey.length != 64) {
                                Text("长度: ${preSharedKey.length}/64", color = MaterialTheme.colorScheme.error)
                            } else if (!preSharedKey.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) {
                                Text("只能包含 0-9, a-f", color = MaterialTheme.colorScheme.error)
                            } else {
                                Text("格式正确 ✓", color = MaterialTheme.colorScheme.secondary)
                            }
                        }
                    )
                }
            }
        }
    }
}
