package com.fluxframe.app.data.store

import com.fluxframe.app.core.di.AppContainer
import com.fluxframe.app.data.model.DeepseekSummary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * DeepSeek 余额与用量记账。
 * 所有写操作（增删 KEY、合并账户、改配置）都直接返回最新 [DeepseekSummary]，
 * 因此写成功后不需要再拉一次。
 */
class DeepseekStore(private val container: AppContainer) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _summary = MutableStateFlow<DeepseekSummary?>(null)
    val summary: StateFlow<DeepseekSummary?> = _summary.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    fun refresh() {
        scope.launch {
            _loading.value = true
            container.deepseekRepository.summary()
                .onSuccess { _summary.value = it }
                .onFailure { _message.value = it.message }
            _loading.value = false
        }
    }

    /** 同步触发一次全量刷新（会真实调用 DeepSeek 官方余额接口） */
    fun forceRefresh() {
        scope.launch {
            _busy.value = true
            container.deepseekRepository.refresh()
                .onSuccess { result ->
                    _summary.value = result
                    _message.value = when {
                        result.refresh?.ok == true -> "已刷新"
                        result.refresh?.skipped == true -> "正在刷新中，已跳过"
                        else -> result.refresh?.error ?: "已刷新"
                    }
                }
                .onFailure { _message.value = it.message }
            _busy.value = false
        }
    }

    fun addKey(name: String, apiKey: String, accountName: String) {
        scope.launch {
            _busy.value = true
            container.deepseekRepository.addKey(name, apiKey, accountName)
                .onSuccess { result ->
                    _summary.value = result.summary
                    _message.value = if (result.probe.ok) {
                        "已添加并校验通过"
                    } else {
                        // 服务端先探测后无条件落库：探测失败 KEY 依然会被创建
                        "已保存，但密钥校验失败：${result.probe.error ?: "未知原因"}"
                    }
                }
                .onFailure { _message.value = it.message }
            _busy.value = false
        }
    }

    fun toggleKey(id: String, enabled: Boolean) {
        scope.launch {
            container.deepseekRepository.updateKey(id = id, enabled = enabled)
                .onSuccess { _summary.value = it }
                .onFailure { _message.value = it.message }
        }
    }

    fun renameKey(id: String, name: String, accountName: String) {
        scope.launch {
            container.deepseekRepository.updateKey(id = id, name = name, accountName = accountName)
                .onSuccess { _summary.value = it; _message.value = "已保存" }
                .onFailure { _message.value = it.message }
        }
    }

    fun deleteKey(id: String) {
        scope.launch {
            container.deepseekRepository.deleteKey(id)
                .onSuccess { _summary.value = it; _message.value = "已删除" }
                .onFailure { _message.value = it.message }
        }
    }

    /** 合并账户会迁移历史记账流水，属于不可逆操作 */
    fun merge(keyIds: List<String>, name: String?) {
        scope.launch {
            _busy.value = true
            container.deepseekRepository.merge(keyIds, name)
                .onSuccess { _summary.value = it; _message.value = "已合并" }
                .onFailure { _message.value = it.message }
            _busy.value = false
        }
    }

    /** [refreshSeconds] 必须落在 30..3600 */
    fun configure(enabled: Boolean?, refreshSeconds: Int?, platformToken: String?) {
        scope.launch {
            container.deepseekRepository.configure(enabled, refreshSeconds, platformToken)
                .onSuccess { _summary.value = it; _message.value = "已保存" }
                .onFailure { _message.value = it.message }
        }
    }

    fun clearMessage() {
        _message.value = null
    }
}
