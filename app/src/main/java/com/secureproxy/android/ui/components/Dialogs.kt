package com.secureproxy.android.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportDialog(
    onImport: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var urlString by remember { mutableStateOf("") }
    val isValid = urlString.trim().startsWith("wss://")
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("粘贴链接导入配置") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "格式: wss://host:port/path?psk=xxx&socks=1080&http=1081",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
                
                OutlinedTextField(
                    value = urlString,
                    onValueChange = { urlString = it },
                    label = { Text("配置链接") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                    maxLines = 5,
                    isError = urlString.isNotEmpty() && !isValid,
                    supportingText = {
                        if (urlString.isEmpty()) {
                            Text("请粘贴配置链接")
                        } else if (!isValid) {
                            Text("链接应以 wss:// 开头", color = MaterialTheme.colorScheme.error)
                        } else {
                            Text("格式正确 ✓", color = MaterialTheme.colorScheme.secondary)
                        }
                    }
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onImport(urlString)
                },
                enabled = isValid
            ) {
                Text("导入")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogsDialog(
    logs: List<String>,
    onClear: () -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .fillMaxHeight(0.8f)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                TopAppBar(
                    title = { Text("运行日志") },
                    navigationIcon = {
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Default.Close, "关闭")
                        }
                    },
                    actions = {
                        TextButton(
                            onClick = onClear,
                            enabled = logs.isNotEmpty()
                        ) {
                            Text("清除")
                        }
                    }
                )
                
                if (logs.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = androidx.compose.ui.Alignment.Center
                    ) {
                        Text(
                            text = "暂无日志",
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        items(logs) { log ->
                            Text(
                                text = log,
                                style = MaterialTheme.typography.bodySmall,
                                color = when {
                                    log.contains("✅") || log.contains("成功") -> MaterialTheme.colorScheme.secondary
                                    log.contains("❌") || log.contains("错误") -> MaterialTheme.colorScheme.error
                                    log.contains("⚠️") || log.contains("警告") -> MaterialTheme.colorScheme.tertiary
                                    else -> MaterialTheme.colorScheme.onSurface
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}
