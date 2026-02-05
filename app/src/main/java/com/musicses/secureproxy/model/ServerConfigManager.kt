package com.musicses.secureproxy.model

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * 服务器配置管理器 - 支持多个配置的保存和管理
 */
class ServerConfigManager(context: Context) {

    companion object {
        private const val PREFS_NAME = "server_configs"
        private const val KEY_CONFIGS = "configs"
        private const val KEY_SELECTED_INDEX = "selected_index"
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * 保存配置列表
     */
    suspend fun saveConfigs(configs: List<ServerConfig>) = withContext(Dispatchers.IO) {
        val jsonArray = JSONArray()
        configs.forEach { config ->
            jsonArray.put(config.toJson())
        }
        prefs.edit().putString(KEY_CONFIGS, jsonArray.toString()).apply()
    }

    /**
     * 加载配置列表
     */
    suspend fun loadConfigs(): List<ServerConfig> = withContext(Dispatchers.IO) {
        val jsonString = prefs.getString(KEY_CONFIGS, null) ?: return@withContext emptyList()

        try {
            val jsonArray = JSONArray(jsonString)
            val configs = mutableListOf<ServerConfig>()

            for (i in 0 until jsonArray.length()) {
                val jsonObject = jsonArray.getJSONObject(i)
                ServerConfig.fromJson(jsonObject)?.let { configs.add(it) }
            }

            configs
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * 添加配置
     */
    suspend fun addConfig(config: ServerConfig) {
        val configs = loadConfigs().toMutableList()
        configs.add(config)
        saveConfigs(configs)
    }

    /**
     * 更新配置
     */
    suspend fun updateConfig(index: Int, config: ServerConfig) {
        val configs = loadConfigs().toMutableList()
        if (index in configs.indices) {
            configs[index] = config
            saveConfigs(configs)
        }
    }

    /**
     * 删除配置
     */
    suspend fun deleteConfig(index: Int) {
        val configs = loadConfigs().toMutableList()
        if (index in configs.indices) {
            configs.removeAt(index)
            saveConfigs(configs)

            // 如果删除的是当前选中的配置，需要调整选中索引
            val selectedIndex = getSelectedIndex()
            if (selectedIndex == index) {
                setSelectedIndex(if (configs.isEmpty()) -1 else 0)
            } else if (selectedIndex > index) {
                setSelectedIndex(selectedIndex - 1)
            }
        }
    }

    /**
     * 获取选中的配置索引
     */
    fun getSelectedIndex(): Int {
        return prefs.getInt(KEY_SELECTED_INDEX, -1)
    }

    /**
     * 设置选中的配置索引
     */
    fun setSelectedIndex(index: Int) {
        prefs.edit().putInt(KEY_SELECTED_INDEX, index).apply()
    }

    /**
     * 获取当前选中的配置
     */
    suspend fun getSelectedConfig(): ServerConfig? {
        val configs = loadConfigs()
        val index = getSelectedIndex()
        return if (index in configs.indices) configs[index] else null
    }
}

/**
 * 服务器配置数据类
 */
data class ServerConfig(
    val id: String = java.util.UUID.randomUUID().toString(),
    val name: String,
    val sniHost: String,
    val proxyAddress: String,  // 可以是IP或域名
    val serverPort: Int,
    val path: String,
    val preSharedKey: String,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
) {

    /**
     * 转换为 ProxyConfig
     */
    fun toProxyConfig(): ProxyConfig {
        return ProxyConfig(
            sniHost = sniHost,
            proxyIp = proxyAddress,  // 实际上可以是域名
            serverPort = serverPort,
            path = path,
            preSharedKey = preSharedKey
        )
    }

    /**
     * 转换为 JSON
     */
    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("id", id)
            put("name", name)
            put("sniHost", sniHost)
            put("proxyAddress", proxyAddress)
            put("serverPort", serverPort)
            put("path", path)
            put("preSharedKey", preSharedKey)
            put("createdAt", createdAt)
            put("updatedAt", updatedAt)
        }
    }

    /**
     * 验证配置有效性
     */
    fun validate(): Boolean {
        return name.isNotBlank() &&
                sniHost.isNotBlank() &&
                proxyAddress.isNotBlank() &&
                serverPort in 1..65535 &&
                path.isNotBlank() &&
                preSharedKey.length == 64 &&
                preSharedKey.matches(Regex("^[0-9a-fA-F]{64}$"))
    }

    /**
     * 获取详细验证错误信息
     */
    fun getValidationError(): String? {
        return when {
            name.isBlank() -> "配置名称不能为空"
            sniHost.isBlank() -> "SNI 主机不能为空"
            proxyAddress.isBlank() -> "代理地址不能为空"
            serverPort !in 1..65535 -> "服务器端口必须在 1-65535 之间"
            path.isBlank() -> "路径不能为空"
            !path.startsWith("/") -> "路径必须以 / 开头"
            preSharedKey.length != 64 -> "PSK 必须是 64 位十六进制字符"
            !preSharedKey.matches(Regex("^[0-9a-fA-F]{64}$")) -> "PSK 只能包含十六进制字符 (0-9, a-f)"
            else -> null
        }
    }

    companion object {
        /**
         * 从 JSON 解析
         */
        fun fromJson(json: JSONObject): ServerConfig? {
            return try {
                ServerConfig(
                    id = json.optString("id", java.util.UUID.randomUUID().toString()),
                    name = json.getString("name"),
                    sniHost = json.getString("sniHost"),
                    proxyAddress = json.getString("proxyAddress"),
                    serverPort = json.getInt("serverPort"),
                    path = json.getString("path"),
                    preSharedKey = json.getString("preSharedKey"),
                    createdAt = json.optLong("createdAt", System.currentTimeMillis()),
                    updatedAt = json.optLong("updatedAt", System.currentTimeMillis())
                )
            } catch (e: Exception) {
                null
            }
        }

        /**
         * 从 ProxyConfig 创建
         */
        fun fromProxyConfig(name: String, config: ProxyConfig): ServerConfig {
            return ServerConfig(
                name = name,
                sniHost = config.sniHost,
                proxyAddress = config.proxyIp,
                serverPort = config.serverPort,
                path = config.path,
                preSharedKey = config.preSharedKey
            )
        }
    }
}