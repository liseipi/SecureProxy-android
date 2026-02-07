package com.secureproxy.android.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.secureproxy.android.data.model.ProxyConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "proxy_configs")

/**
 * 配置数据仓库
 */
class ConfigRepository(private val context: Context) {
    
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    
    companion object {
        private val CONFIGS_KEY = stringPreferencesKey("configs")
        private val ACTIVE_CONFIG_ID_KEY = stringPreferencesKey("active_config_id")
    }
    
    /**
     * 获取所有配置
     */
    fun getConfigsFlow(): Flow<List<ProxyConfig>> {
        return context.dataStore.data.map { preferences ->
            val configsJson = preferences[CONFIGS_KEY] ?: "[]"
            try {
                json.decodeFromString<List<ProxyConfig>>(configsJson)
            } catch (e: Exception) {
                emptyList()
            }
        }
    }
    
    /**
     * 获取活跃配置 ID
     */
    fun getActiveConfigIdFlow(): Flow<String?> {
        return context.dataStore.data.map { preferences ->
            preferences[ACTIVE_CONFIG_ID_KEY]
        }
    }
    
    /**
     * 保存配置列表
     */
    suspend fun saveConfigs(configs: List<ProxyConfig>) {
        context.dataStore.edit { preferences ->
            preferences[CONFIGS_KEY] = json.encodeToString(configs)
        }
    }
    
    /**
     * 添加配置
     */
    suspend fun addConfig(config: ProxyConfig) {
        context.dataStore.edit { preferences ->
            val configsJson = preferences[CONFIGS_KEY] ?: "[]"
            val configs = try {
                json.decodeFromString<List<ProxyConfig>>(configsJson).toMutableList()
            } catch (e: Exception) {
                mutableListOf()
            }
            
            configs.add(config)
            preferences[CONFIGS_KEY] = json.encodeToString(configs)
        }
    }
    
    /**
     * 更新配置
     */
    suspend fun updateConfig(config: ProxyConfig) {
        context.dataStore.edit { preferences ->
            val configsJson = preferences[CONFIGS_KEY] ?: "[]"
            val configs = try {
                json.decodeFromString<List<ProxyConfig>>(configsJson).toMutableList()
            } catch (e: Exception) {
                mutableListOf()
            }
            
            val index = configs.indexOfFirst { it.id == config.id }
            if (index >= 0) {
                configs[index] = config
                preferences[CONFIGS_KEY] = json.encodeToString(configs)
            }
        }
    }
    
    /**
     * 删除配置
     */
    suspend fun deleteConfig(configId: String) {
        context.dataStore.edit { preferences ->
            val configsJson = preferences[CONFIGS_KEY] ?: "[]"
            val configs = try {
                json.decodeFromString<List<ProxyConfig>>(configsJson).toMutableList()
            } catch (e: Exception) {
                mutableListOf()
            }
            
            configs.removeAll { it.id == configId }
            preferences[CONFIGS_KEY] = json.encodeToString(configs)
            
            // 如果删除的是活跃配置，清除活跃状态
            if (preferences[ACTIVE_CONFIG_ID_KEY] == configId) {
                preferences.remove(ACTIVE_CONFIG_ID_KEY)
            }
        }
    }
    
    /**
     * 设置活跃配置
     */
    suspend fun setActiveConfig(configId: String) {
        context.dataStore.edit { preferences ->
            preferences[ACTIVE_CONFIG_ID_KEY] = configId
        }
    }
    
    /**
     * 清除活跃配置
     */
    suspend fun clearActiveConfig() {
        context.dataStore.edit { preferences ->
            preferences.remove(ACTIVE_CONFIG_ID_KEY)
        }
    }
    
    /**
     * 导出所有配置为 JSON 字符串
     */
    suspend fun exportConfigsJson(): String {
        val configs = getConfigsFlow().map { it }.toString()
        return configs
    }
    
    /**
     * 从 JSON 字符串导入配置
     */
    suspend fun importConfigsJson(jsonString: String): Result<Int> {
        return try {
            val newConfigs = json.decodeFromString<List<ProxyConfig>>(jsonString)
            
            context.dataStore.edit { preferences ->
                val configsJson = preferences[CONFIGS_KEY] ?: "[]"
                val existingConfigs = try {
                    json.decodeFromString<List<ProxyConfig>>(configsJson).toMutableList()
                } catch (e: Exception) {
                    mutableListOf()
                }
                
                existingConfigs.addAll(newConfigs)
                preferences[CONFIGS_KEY] = json.encodeToString(existingConfigs)
            }
            
            Result.success(newConfigs.size)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
