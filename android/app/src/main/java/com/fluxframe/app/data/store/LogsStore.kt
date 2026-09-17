package com.fluxframe.app.data.store

import com.fluxframe.app.core.di.AppContainer
import com.fluxframe.app.data.model.AuditLogItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 访问日志（仅 ADMIN）。
 *
 * 服务端固定返回最近 500 条且**不提供分页参数**，所以筛选全部在本地完成 ——
 * 这样切换分类是瞬时的，不会再打一次接口。
 */
class LogsStore(private val container: AppContainer) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _logs = MutableStateFlow<List<AuditLogItem>>(emptyList())
    val logs: StateFlow<List<AuditLogItem>> = _logs.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _filter = MutableStateFlow(LOG_GROUPS.first())
    val filter: StateFlow<String> = _filter.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    val visibleLogs: List<AuditLogItem>
        get() = if (_filter.value == LOG_GROUPS.first()) {
            _logs.value
        } else {
            _logs.value.filter { logGroupOf(it.action) == _filter.value }
        }

    fun refresh() {
        scope.launch {
            _loading.value = true
            container.adminRepository.auditLogs()
                .onSuccess {
                    _logs.value = it
                    _error.value = null
                }
                .onFailure { _error.value = it.message }
            _loading.value = false
        }
    }

    fun setFilter(value: String) {
        _filter.value = value
    }

    companion object {
        /** 与网页端一致的日志分类顺序 */
        val LOG_GROUPS = listOf("全部", "查看", "上传", "下载", "提取", "改名", "标签", "删除", "其他")

        fun logGroupOf(action: String): String = when {
            action.contains("查看") -> "查看"
            action.contains("上传") -> "上传"
            action.contains("下载") -> "下载"
            action.contains("提取") -> "提取"
            action.contains("改名") || action.contains("名称") -> "改名"
            action.contains("标签") || action.contains("人物") -> "标签"
            action.contains("删除") -> "删除"
            else -> "其他"
        }
    }
}
