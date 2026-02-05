package com.musicses.secureproxy

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.musicses.secureproxy.databinding.ActivityConfigEditorBinding
import com.musicses.secureproxy.model.ServerConfig
import com.musicses.secureproxy.model.ServerConfigManager
import kotlinx.coroutines.launch

/**
 * 配置编辑Activity
 */
class ConfigEditorActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_CONFIG_INDEX = "config_index"
        const val EXTRA_MODE = "mode"
        const val MODE_ADD = "add"
        const val MODE_EDIT = "edit"
    }

    private lateinit var binding: ActivityConfigEditorBinding
    private lateinit var configManager: ServerConfigManager

    private var mode: String = MODE_ADD
    private var configIndex: Int = -1
    private var existingConfig: ServerConfig? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityConfigEditorBinding.inflate(layoutInflater)
        setContentView(binding.root)

        configManager = ServerConfigManager(this)

        // 获取模式和索引
        mode = intent.getStringExtra(EXTRA_MODE) ?: MODE_ADD
        configIndex = intent.getIntExtra(EXTRA_CONFIG_INDEX, -1)

        setupToolbar()
        setupUI()
        loadExistingConfig()
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            title = if (mode == MODE_ADD) "添加配置" else "编辑配置"
        }

        binding.toolbar.setNavigationOnClickListener {
            finish()
        }
    }

    private fun setupUI() {
        // PSK输入框默认显示明文
        binding.editPsk.inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD

        binding.btnSave.setOnClickListener {
            saveConfig()
        }

        binding.btnCancel.setOnClickListener {
            finish()
        }

        // 切换PSK显示/隐藏
        binding.btnTogglePskVisibility.setOnClickListener {
            togglePskVisibility()
        }
    }

    private fun loadExistingConfig() {
        if (mode == MODE_EDIT && configIndex >= 0) {
            lifecycleScope.launch {
                val configs = configManager.loadConfigs()
                if (configIndex < configs.size) {
                    existingConfig = configs[configIndex]
                    fillFormWithConfig(existingConfig!!)
                }
            }
        }
    }

    private fun fillFormWithConfig(config: ServerConfig) {
        binding.editConfigName.setText(config.name)
        binding.editSniHost.setText(config.sniHost)
        binding.editProxyAddress.setText(config.proxyAddress)
        binding.editServerPort.setText(config.serverPort.toString())
        binding.editPath.setText(config.path)
        binding.editPsk.setText(config.preSharedKey)
    }

    private fun togglePskVisibility() {
        val currentType = binding.editPsk.inputType

        if (currentType and android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD != 0 ||
            currentType and android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD != 0) {
            // 当前是可见，切换为隐藏
            binding.editPsk.inputType = android.text.InputType.TYPE_CLASS_TEXT or
                    android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            binding.btnTogglePskVisibility.setIconResource(android.R.drawable.ic_menu_view)
        } else {
            // 当前是隐藏，切换为可见
            binding.editPsk.inputType = android.text.InputType.TYPE_CLASS_TEXT or
                    android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            binding.btnTogglePskVisibility.setIconResource(android.R.drawable.ic_menu_close_clear_cancel)
        }

        // 保持光标位置
        binding.editPsk.setSelection(binding.editPsk.text?.length ?: 0)
    }

    private fun saveConfig() {
        val config = getConfigFromForm()

        val validationError = config.getValidationError()
        if (validationError != null) {
            MaterialAlertDialogBuilder(this)
                .setTitle("配置错误")
                .setMessage(validationError)
                .setPositiveButton("确定", null)
                .show()
            return
        }

        lifecycleScope.launch {
            try {
                if (mode == MODE_ADD) {
                    configManager.addConfig(config)
                    Toast.makeText(this@ConfigEditorActivity, "配置已添加", Toast.LENGTH_SHORT).show()
                } else {
                    configManager.updateConfig(configIndex, config)
                    Toast.makeText(this@ConfigEditorActivity, "配置已更新", Toast.LENGTH_SHORT).show()
                }

                setResult(RESULT_OK)
                finish()
            } catch (e: Exception) {
                Toast.makeText(this@ConfigEditorActivity, "保存失败: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun getConfigFromForm(): ServerConfig {
        return ServerConfig(
            id = existingConfig?.id ?: java.util.UUID.randomUUID().toString(),
            name = binding.editConfigName.text.toString().trim(),
            sniHost = binding.editSniHost.text.toString().trim(),
            proxyAddress = binding.editProxyAddress.text.toString().trim(),
            serverPort = binding.editServerPort.text.toString().toIntOrNull() ?: 2053,
            path = binding.editPath.text.toString().trim(),
            preSharedKey = binding.editPsk.text.toString().trim(),
            createdAt = existingConfig?.createdAt ?: System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )
    }
}