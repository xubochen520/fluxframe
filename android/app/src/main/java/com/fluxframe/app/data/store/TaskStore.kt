package com.fluxframe.app.data.store

import com.fluxframe.app.core.di.AppContainer
import com.fluxframe.app.core.net.ApiException
import com.fluxframe.app.data.model.ImageItem
import com.fluxframe.app.data.model.ParseImportRequest
import com.fluxframe.app.data.model.UploadAnalyzeResponse
import com.fluxframe.app.data.model.UploadCompleteItem
import com.fluxframe.app.data.repo.MediaRepository
import com.fluxframe.app.data.repo.UploadSource
import com.fluxframe.app.fluidcloud.CapsuleKind
import com.fluxframe.app.fluidcloud.CapsuleTone
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

enum class UploadPhase { ANALYZING, REVIEW, SAVING, DONE, FAILED }

data class UploadTask(
    val phase: UploadPhase,
    val totalFiles: Int = 0,
    /** 0..1 */
    val progress: Float = 0f,
    val message: String = "",
    val sources: List<UploadSource> = emptyList(),
    /** analyze 成功后待用户确认的条目 */
    val analyze: UploadAnalyzeResponse? = null,
    val saved: List<ImageItem> = emptyList(),
    val error: String? = null,
    /** 最近一次提交的入库参数，失败后可直接重试而不用重新选文件 */
    val lastPayload: List<UploadCompleteItem> = emptyList(),
) {
    val inFlight: Boolean
        get() = phase == UploadPhase.ANALYZING || phase == UploadPhase.SAVING
}

/** 服务端「提取入库」任务（视频 / 封面 / 图文图片） */
data class ImportTask(
    val id: String,
    val title: String,
    /** video | cover | image */
    val kind: String,
    /** 原始请求，用于失败重试 */
    val request: ParseImportRequest? = null,
    val status: String = "working",
    val progress: Float? = null,
    val message: String = "准备导入…",
    val items: List<ImageItem> = emptyList(),
    val duplicate: Boolean = false,
) {
    val isWorking: Boolean get() = status == "working"
    val isDone: Boolean get() = status == "done"
    val isError: Boolean get() = status == "error"
}

/**
 * 长任务中心：上传与「提取入库」。
 *
 * 它是流体云 / 实况通知 / 应用内胶囊的唯一数据来源 —— 任何长任务开始时
 * 都会通过 [AppContainer.fluidCloud] 上报进度，因此用户切到后台也能看到
 * 「正在上传 42%」这类实时状态。
 */
class TaskStore(private val container: AppContainer) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val bridge get() = container.fluidCloud

    private val _upload = MutableStateFlow<UploadTask?>(null)
    val upload: StateFlow<UploadTask?> = _upload.asStateFlow()

    private val _imports = MutableStateFlow<List<ImportTask>>(emptyList())
    val imports: StateFlow<List<ImportTask>> = _imports.asStateFlow()

    val activeImports: List<ImportTask> get() = _imports.value.filter { it.isWorking }

    /* ============================== 上传 ============================== */

    /**
     * 第一步：把所选文件传上去并做 AI 打标 / 查重。
     * 完成后进入 [UploadPhase.REVIEW]，由用户确认名称与标签。
     */
    fun startAnalyze(sources: List<UploadSource>) {
        if (sources.isEmpty()) return
        if (_upload.value?.inFlight == true) return
        val limitMb = container.settingsStore.settings.value?.uploadLimitMb ?: 50
        val invalid = MediaRepository.validate(sources, limitMb)
        if (invalid != null) {
            _upload.value = UploadTask(
                phase = UploadPhase.FAILED,
                totalFiles = sources.size,
                sources = sources,
                error = invalid,
            )
            return
        }

        scope.launch {
            _upload.value = UploadTask(
                phase = UploadPhase.ANALYZING,
                totalFiles = sources.size,
                message = "正在上传原文件…",
                sources = sources,
            )
            bridge.begin(
                kind = CapsuleKind.UPLOAD,
                title = "上传 ${sources.size} 个文件",
                subtitle = "正在上传原文件…",
            )
            container.mediaRepository.analyzeUpload(sources) { fraction ->
                val percent = (fraction * 100).toInt()
                _upload.value = _upload.value?.copy(
                    progress = fraction,
                    message = "已上传 $percent%",
                )
                bridge.update(fraction, "已上传 $percent%")
            }
                .onSuccess { response ->
                    _upload.value = _upload.value?.copy(
                        phase = UploadPhase.REVIEW,
                        progress = 1f,
                        analyze = response,
                        message = "待确认",
                    )
                    val duplicateCount = response.items.count { it.duplicate }
                    val subtitle = if (duplicateCount > 0) {
                        "分析完成，其中 $duplicateCount 个与库中重复"
                    } else {
                        "分析完成，请确认名称与标签"
                    }
                    bridge.finish(subtitle, CapsuleTone.SUCCESS)
                }
                .onFailure { error ->
                    _upload.value = _upload.value?.copy(
                        phase = UploadPhase.FAILED,
                        error = error.message,
                        message = "",
                    )
                    bridge.finish(error.message ?: "上传失败", CapsuleTone.ERROR)
                }
        }
    }

    /** 第三步：提交用户确认后的名称与标签 */
    fun completeUpload(items: List<UploadCompleteItem>) {
        if (items.isEmpty()) {
            discardUpload()
            return
        }
        scope.launch {
            _upload.value = _upload.value?.copy(
                phase = UploadPhase.SAVING,
                message = "正在入库并生成缩略图…",
                lastPayload = items,
                error = null,
            )
            bridge.begin(
                kind = CapsuleKind.UPLOAD,
                title = "保存 ${items.size} 个文件",
                subtitle = "生成缩略图中…",
                withForegroundService = false,
            )
            bridge.update(null, "服务端正在生成 320/768/1600 缩略图…")
            container.mediaRepository.completeUpload(items)
                .onSuccess { saved ->
                    _upload.value = _upload.value?.copy(
                        phase = UploadPhase.DONE,
                        saved = saved,
                        message = "已保存 ${saved.size} 个文件",
                    )
                    bridge.finish("已保存 ${saved.size} 个文件到图片库", CapsuleTone.SUCCESS)
                    container.mediaStore.refreshAll()
                }
                .onFailure { error ->
                    _upload.value = _upload.value?.copy(phase = UploadPhase.FAILED, error = error.message)
                    bridge.finish(error.message ?: "保存失败", CapsuleTone.ERROR)
                }
        }
    }

    /** 放弃本次上传：把已上传的临时文件从服务端删掉 */
    fun discardUpload() {
        val task = _upload.value ?: return
        scope.launch {
            task.analyze?.items?.forEach { item ->
                item.tempIdOrNull?.let { tempId ->
                    runCatching { container.mediaRepository.discardPending(tempId) }
                }
            }
            _upload.value = null
            bridge.dismiss()
        }
    }

    fun dismissUpload() {
        _upload.value = null
    }

    /** 入库失败后重试（沿用上次的名称与标签，不必重新选文件） */
    fun retryUpload() {
        val payload = _upload.value?.lastPayload.orEmpty()
        if (payload.isEmpty()) {
            dismissUpload()
            return
        }
        completeUpload(payload)
    }

    /* ========================= 提取入库（解析） ========================= */

    fun startImport(request: ParseImportRequest, title: String) {
        scope.launch {
            container.parseRepository.startImport(request)
                .onSuccess { jobId ->
                    val task = ImportTask(
                        id = jobId,
                        title = title,
                        kind = request.kind,
                        request = request,
                        message = "已加入后台任务…",
                    )
                    _imports.value = listOf(task) + _imports.value
                    bridge.begin(
                        kind = CapsuleKind.PARSE,
                        title = "视频提取",
                        subtitle = title,
                    )
                    bridge.update(null, "服务端开始下载…")
                    pollImport(jobId)
                }
                .onFailure { error ->
                    bridge.finish(error.message ?: "启动导入失败", CapsuleTone.ERROR)
                }
        }
    }

    private fun pollImport(jobId: String) {
        scope.launch {
            // 服务端没有 SSE，只能短轮询；800 ms 与网页端实现保持一致
            while (isActive) {
                delay(POLL_INTERVAL_MS)
                val result = container.parseRepository.importStatus(jobId)
                val error = result.exceptionOrNull()
                if (error != null) {
                    // 取消后继续轮询一定会拿到 404，这不算异常
                    val message = if (error is ApiException && error.status == 404) {
                        "任务已取消或已过期"
                    } else {
                        error.message ?: "查询任务失败"
                    }
                    updateImport(jobId) { it.copy(status = "error", message = message) }
                    bridge.finish(message, CapsuleTone.ERROR)
                    return@launch
                }
                val status = result.getOrNull() ?: continue
                updateImport(jobId) {
                    it.copy(
                        status = status.status,
                        progress = status.progress?.toFloat(),
                        message = status.message.ifBlank { it.message },
                        items = status.items,
                        duplicate = status.duplicate,
                    )
                }
                bridge.update(status.progress?.toFloat(), status.message)
                if (!status.isWorking) {
                    if (status.isDone) {
                        bridge.finish(status.message.ifBlank { "已保存到图片库" }, CapsuleTone.SUCCESS)
                        container.mediaStore.refreshAll()
                    } else {
                        bridge.finish(status.message.ifBlank { "提取失败" }, CapsuleTone.ERROR)
                    }
                    return@launch
                }
            }
        }
    }

    fun cancelImport(jobId: String) {
        scope.launch {
            container.parseRepository.cancelImport(jobId)
            updateImport(jobId) { it.copy(status = "error", message = "已取消") }
            bridge.cancel("提取已取消")
        }
    }

    fun dismissImport(jobId: String) {
        _imports.value = _imports.value.filterNot { it.id == jobId }
    }

    /** 用原请求重新发起一次提取（服务端任务失效后也能重试） */
    fun retryImport(jobId: String) {
        val task = _imports.value.firstOrNull { it.id == jobId } ?: return
        val request = task.request ?: run {
            dismissImport(jobId)
            return
        }
        _imports.value = _imports.value.filterNot { it.id == jobId }
        startImport(request, task.title)
    }

    fun clearFinishedImports() {
        _imports.value = _imports.value.filter { it.isWorking }
    }

    private inline fun updateImport(jobId: String, transform: (ImportTask) -> ImportTask) {
        _imports.value = _imports.value.map { if (it.id == jobId) transform(it) else it }
    }

    private companion object {
        const val POLL_INTERVAL_MS = 800L
    }
}
