package com.fluxframe.app.data.store

import com.fluxframe.app.core.di.AppContainer
import com.fluxframe.app.data.model.SystemSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/**
 * 服务器端系统设置（需要 ADMIN）。
 *
 * 保存策略：`PATCH /api/settings` 的响应不含深算字段（DeepSeek / B站 / ffmpeg 状态），
 * 因此每次保存成功后再 `GET` 一次，保证界面显示的是真实配置。
 */
class SettingsStore(private val container: AppContainer) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _settings = MutableStateFlow<SystemSettings?>(null)
    val settings: StateFlow<SystemSettings?> = _settings.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _saving = MutableStateFlow(false)
    val saving: StateFlow<Boolean> = _saving.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    fun refresh() {
        scope.launch {
            _loading.value = true
            container.adminRepository.settings()
                .onSuccess { _settings.value = it }
                .onFailure { _message.value = it.message }
            _loading.value = false
        }
    }

    /** 部分更新；[fields] 中的数值会自动以 JSON number 提交（服务端只认数字） */
    fun save(fields: Map<String, JsonElement>, successText: String = "已保存") {
        if (fields.isEmpty()) return
        scope.launch {
            _saving.value = true
            container.adminRepository.patchSettings(fields)
                .onSuccess {
                    _message.value = successText
                    container.adminRepository.settings().onSuccess { _settings.value = it }
                }
                .onFailure { _message.value = it.message }
            _saving.value = false
        }
    }

    fun saveBoolean(key: String, value: Boolean, successText: String = "已保存") =
        save(mapOf(key to JsonPrimitive(value)), successText)

    fun saveNumber(key: String, value: Number, successText: String = "已保存") =
        save(mapOf(key to JsonPrimitive(value)), successText)

    fun saveString(key: String, value: String, successText: String = "已保存") =
        save(mapOf(key to JsonPrimitive(value)), successText)

    fun clearMessage() {
        _message.value = null
    }
}
