package com.secureproxy.android.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.secureproxy.android.data.model.ProxyConfig
import com.secureproxy.android.data.model.ProxyStatus
import com.secureproxy.android.data.model.TrafficStats

@Composable
fun StatusCard(
    config: ProxyConfig?,
    status: ProxyStatus,
    isRunning: Boolean,
    trafficStats: TrafficStats,
    onToggle: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp)
        ) {
            // 顶部：配置名称和开关
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = config?.name ?: "未选择配置",
                        style = MaterialTheme.typography.titleLarge
                    )
                    
                    Spacer(modifier = Modifier.height(4.dp))
                    
                    StatusChip(status = status)
                }
                
                Switch(
                    checked = isRunning,
                    onCheckedChange = { onToggle() },
                    enabled = config != null
                )
            }
            
            // 配置信息
            if (config != null) {
                Spacer(modifier = Modifier.height(16.dp))
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    InfoChip(
                        icon = Icons.Default.Cloud,
                        text = config.sniHost,
                        modifier = Modifier.weight(1f)
                    )
                    
                    if (config.isCdnMode) {
                        InfoChip(
                            icon = Icons.Default.Storage,
                            text = "CDN",
                            color = MaterialTheme.colorScheme.tertiary
                        )
                    }
                }
            }
            
            // 流量统计
            if (isRunning) {
                Spacer(modifier = Modifier.height(16.dp))
                Divider()
                Spacer(modifier = Modifier.height(16.dp))
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    TrafficItem(
                        icon = Icons.Default.ArrowUpward,
                        label = "上传",
                        value = trafficStats.formatSpeed(trafficStats.uploadSpeed),
                        color = MaterialTheme.colorScheme.primary
                    )
                    
                    TrafficItem(
                        icon = Icons.Default.ArrowDownward,
                        label = "下载",
                        value = trafficStats.formatSpeed(trafficStats.downloadSpeed),
                        color = MaterialTheme.colorScheme.secondary
                    )
                }
                
                if (config != null) {
                    Spacer(modifier = Modifier.height(12.dp))
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "SOCKS5: ${config.socksPort}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                        
                        Spacer(modifier = Modifier.width(16.dp))
                        
                        Text(
                            text = "HTTP: ${config.httpPort}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun StatusChip(status: ProxyStatus) {
    val color = when (status) {
        ProxyStatus.DISCONNECTED -> Color.Gray
        ProxyStatus.CONNECTING -> Color(0xFFFF9800)
        ProxyStatus.CONNECTED -> Color(0xFF4CAF50)
    }
    
    val infiniteTransition = rememberInfiniteTransition(label = "status")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 0.3f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000),
            repeatMode = RepeatMode.Reverse
        ),
        label = "alpha"
    )
    
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(MaterialTheme.shapes.small)
            .background(color.copy(alpha = 0.15f))
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(MaterialTheme.shapes.extraSmall)
                .background(
                    if (status == ProxyStatus.CONNECTING) {
                        color.copy(alpha = alpha)
                    } else {
                        color
                    }
                )
        )
        
        Spacer(modifier = Modifier.width(8.dp))
        
        Text(
            text = status.displayName,
            style = MaterialTheme.typography.bodySmall,
            color = color
        )
    }
}

@Composable
fun InfoChip(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    text: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary
) {
    Row(
        modifier = modifier
            .clip(MaterialTheme.shapes.small)
            .background(color.copy(alpha = 0.1f))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = color
        )
        
        Spacer(modifier = Modifier.width(8.dp))
        
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = color
        )
    }
}

@Composable
fun TrafficItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    value: String,
    color: Color
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(24.dp)
        )
        
        Spacer(modifier = Modifier.height(4.dp))
        
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        )
        
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = color
        )
    }
}
