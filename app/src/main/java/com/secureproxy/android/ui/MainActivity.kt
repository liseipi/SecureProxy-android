package com.secureproxy.android.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.secureproxy.android.ui.screens.MainScreen
import com.secureproxy.android.ui.theme.SecureProxyTheme
import com.secureproxy.android.ui.viewmodel.ProxyViewModel

class MainActivity : ComponentActivity() {

    private val viewModel: ProxyViewModel by viewModels()

    // VPN 权限请求启动器
    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            viewModel.onVpnPermissionGranted()
        } else {
            viewModel.onVpnPermissionDenied()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // 处理从浏览器打开的配置链接
        handleIntent(intent)

        setContent {
            SecureProxyTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    // 监听 VPN 权限请求
                    val vpnPermissionIntent by viewModel.vpnPermissionNeeded.collectAsState()

                    LaunchedEffect(vpnPermissionIntent) {
                        vpnPermissionIntent?.let { intent ->
                            vpnPermissionLauncher.launch(intent)
                        }
                    }

                    MainScreen(viewModel = viewModel)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        // 处理 wss:// 链接导入
        intent?.data?.toString()?.let { url ->
            if (url.startsWith("wss://")) {
                viewModel.importFromUrl(url)
            }
        }
    }
}