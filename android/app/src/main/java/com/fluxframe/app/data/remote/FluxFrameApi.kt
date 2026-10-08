package com.fluxframe.app.data.remote

import com.fluxframe.app.data.model.AddTagToImageRequest
import com.fluxframe.app.data.model.AiStatus
import com.fluxframe.app.data.model.AuditLogListResponse
import com.fluxframe.app.data.model.BatchTagImagesRequest
import com.fluxframe.app.data.model.BatchTagImagesResponse
import com.fluxframe.app.data.model.BiliQrCreateResponse
import com.fluxframe.app.data.model.BiliQrPollRequest
import com.fluxframe.app.data.model.BiliQrPollResponse
import com.fluxframe.app.data.model.CreateTagRequest
import com.fluxframe.app.data.model.CurrentUser
import com.fluxframe.app.data.model.DashboardResponse
import com.fluxframe.app.data.model.DeepseekConfigRequest
import com.fluxframe.app.data.model.DeepseekKeyCreateRequest
import com.fluxframe.app.data.model.DeepseekKeyCreateResponse
import com.fluxframe.app.data.model.DeepseekKeyUpdateRequest
import com.fluxframe.app.data.model.DeepseekMergeRequest
import com.fluxframe.app.data.model.DeepseekSummary
import com.fluxframe.app.data.model.DownloadSessionResponse
import com.fluxframe.app.data.model.EmbedBackfillRequest
import com.fluxframe.app.data.model.EmbedBackfillResponse
import com.fluxframe.app.data.model.EmbedCalibration
import com.fluxframe.app.data.model.EmbedGraph
import com.fluxframe.app.data.model.EmbedStatus
import com.fluxframe.app.data.model.EmbedThresholdRequest
import com.fluxframe.app.data.model.EmbedThresholdResponse
import com.fluxframe.app.data.model.EngineStatus
import com.fluxframe.app.data.model.HealthResponse
import com.fluxframe.app.data.model.ImageDeleteResponse
import com.fluxframe.app.data.model.ImageListResponse
import com.fluxframe.app.data.model.ImageViewResponse
import com.fluxframe.app.data.model.LoginRequest
import com.fluxframe.app.data.model.LoginResponse
import com.fluxframe.app.data.model.OkTaskResponse
import com.fluxframe.app.data.model.ParseImportCreated
import com.fluxframe.app.data.model.ParseImportRequest
import com.fluxframe.app.data.model.ParseImportStatus
import com.fluxframe.app.data.model.ParseResponse
import com.fluxframe.app.data.model.PasswordChangeRequest
import com.fluxframe.app.data.model.PersonDetail
import com.fluxframe.app.data.model.R18ModeRequest
import com.fluxframe.app.data.model.R18ModeResponse
import com.fluxframe.app.data.model.RenameImageRequest
import com.fluxframe.app.data.model.RenameImageResponse
import com.fluxframe.app.data.model.SimpleOkResponse
import com.fluxframe.app.data.model.SimilarImagesResponse
import com.fluxframe.app.data.model.SystemSettings
import com.fluxframe.app.data.model.TagItem
import com.fluxframe.app.data.model.TagListResponse
import com.fluxframe.app.data.model.UpdateTagRequest
import com.fluxframe.app.data.model.UploadAnalyzeResponse
import com.fluxframe.app.data.model.UploadCompleteRequest
import com.fluxframe.app.data.model.UploadCompleteResponse
import okhttp3.MultipartBody
import okhttp3.RequestBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Multipart
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Part
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * fluxframe 后端 REST 接口（与 server/src/index.ts 一一对应）。
 *
 * 基址是占位符 `http://fluxframe.local/`，真实 host:port 由
 * [com.fluxframe.app.core.net.HostSelectionInterceptor] 在请求发出前改写，
 * 这样切换服务器无需重建 Retrofit。
 *
 * 约定：需要登录的接口未登录时后端返回 401 + `{"message":"请先登录"}`；
 * 需要 ADMIN 的接口越权返回 403 + `{"message":"需要管理员权限"}`。
 */
interface FluxFrameApi {

    /* ------------------------------ 健康检查 ------------------------------ */

    @GET("api/health")
    suspend fun health(): HealthResponse

    /* ------------------------------ 认证 ------------------------------ */

    @POST("api/auth/login")
    suspend fun login(@Body body: LoginRequest): LoginResponse

    /**
     * 注册（首个注册用户会成为 ADMIN）。
     * 注意：该接口**不下发会话 Cookie**，返回的是 `{id, username, role, r18Mode}`，
     * 所以注册成功后还需要再调一次 [login]。
     */
    @POST("api/auth/register")
    suspend fun register(@Body body: LoginRequest): CurrentUser

    @POST("api/auth/logout")
    suspend fun logout(): Response<Unit>

    @GET("api/me")
    suspend fun me(): CurrentUser

    @PATCH("api/me/r18-mode")
    suspend fun setR18Mode(@Body body: R18ModeRequest): R18ModeResponse

    @PATCH("api/auth/password")
    suspend fun changePassword(@Body body: PasswordChangeRequest): SimpleOkResponse

    /** 取原始会话 token，供系统 DownloadManager（不共享 CookieJar）使用 */
    @GET("api/download/session")
    suspend fun downloadSession(): DownloadSessionResponse

    /* ------------------------------ 媒体列表 ------------------------------ */

    @GET("api/images")
    suspend fun images(
        @Query("search") search: String? = null,
        @Query("tag") tag: String? = null,
        /** views | newest | name */
        @Query("sort") sort: String = "views",
        @Query("trash") trash: Boolean = false,
    ): ImageListResponse

    @GET("api/dashboard")
    suspend fun dashboard(): DashboardResponse

    /* ------------------------------ 媒体操作 ------------------------------ */

    @POST("api/images/{id}/view")
    suspend fun markViewed(@Path("id") id: String): ImageViewResponse

    @PATCH("api/images/{id}")
    suspend fun renameImage(@Path("id") id: String, @Body body: RenameImageRequest): RenameImageResponse

    @DELETE("api/images/{id}")
    suspend fun trashImage(@Path("id") id: String): ImageDeleteResponse

    @POST("api/images/{id}/restore")
    suspend fun restoreImage(@Path("id") id: String): SimpleOkResponse

    /** 仅 ADMIN：同时删除物理文件与缩略图 */
    @DELETE("api/images/{id}/permanent")
    suspend fun purgeImage(@Path("id") id: String): SimpleOkResponse

    @POST("api/images/{id}/tags")
    suspend fun addTagToImage(@Path("id") id: String, @Body body: AddTagToImageRequest): SimpleOkResponse

    @DELETE("api/images/{id}/tags/{tagId}")
    suspend fun removeTagFromImage(@Path("id") id: String, @Path("tagId") tagId: String): SimpleOkResponse

    /* --------------------- 相似图 / 关系网（CCIP 视觉指纹） ---------------------
     * 语义：按「像素上看起来像不像」找同角色 / 同张图，与标签体系无关。
     * 注意两点（服务端 embed.ts 的既定行为）：
     *   1) 只有静态图片有指纹，视频永远返回 indexed=false；
     *   2) 首次请求某张还没建指纹的图时，服务端会**当场算完再返回**（约 0.7~3 秒），
     *      所以这个接口的读超时要比普通接口宽，客户端也要接受它偶尔慢一次。
     */

    /** 某张图的相似图列表，按相似度降序 */
    @GET("api/images/{id}/similar")
    suspend fun similarImages(
        @Path("id") id: String,
        @Query("limit") limit: Int = 24,
    ): SimilarImagesResponse

    /** 指纹索引概况（已建多少张、阈值、后台是否在算） */
    @GET("api/embed/status")
    suspend fun embedStatus(): EmbedStatus

    /**
     * 关系网：节点 + 相似边 + 相似分组 + 二维布局。
     * [mode] 决定「关系」的依据：`visual` 是 CCIP 视觉指纹（长得像），
     * `tag` 是标签的 TF-IDF 相似度（被打了同一批标记）。
     */
    @GET("api/embed/graph")
    suspend fun embedGraph(
        @Query("edges") edges: Int = 600,
        @Query("mode") mode: String = "visual",
        /** 星系视图用几维坐标：2d 总览 / 3d 星系空间 */
        @Query("space") space: String = "2d",
    ): EmbedGraph

    /** 仅 ADMIN：把还没建指纹的图排进后台队列 */
    @POST("api/embed/backfill")
    suspend fun embedBackfill(@Body body: com.fluxframe.app.data.model.EmbedBackfillRequest): EmbedBackfillResponse

    /** 仅 ADMIN：调整相似度阈值并重算关系 */
    @PATCH("api/embed/threshold")
    suspend fun embedSetThreshold(@Body body: EmbedThresholdRequest): EmbedThresholdResponse

    /** 当前阈值的实测校准数据（同角色 / 异角色的分数分布） */
    @GET("api/embed/calibration")
    suspend fun embedCalibration(): EmbedCalibration

    /* ------------------------------ 上传 ------------------------------ */

    /** 第一步：上传原文件到服务端 temp 目录并做 AI 打标 / 查重 */
    @Multipart
    @POST("api/images/upload/analyze")
    suspend fun uploadAnalyze(@Part parts: List<MultipartBody.Part>): UploadAnalyzeResponse

    /** 第三步：确认名称与标签，服务端落库并生成缩略图 */
    @POST("api/images/upload/complete")
    suspend fun uploadComplete(@Body body: UploadCompleteRequest): UploadCompleteResponse

    @DELETE("api/images/upload/pending/{tempId}")
    suspend fun discardPendingUpload(@Path("tempId") tempId: String): SimpleOkResponse

    /* ------------------------------ 标签 ------------------------------ */

    @GET("api/tags")
    suspend fun tags(): TagListResponse

    @POST("api/tags")
    suspend fun createTag(@Body body: CreateTagRequest): TagItem

    @PATCH("api/tags/{id}")
    suspend fun updateTag(@Path("id") id: String, @Body body: UpdateTagRequest): TagItem

    @DELETE("api/tags/{id}")
    suspend fun deleteTag(@Path("id") id: String): SimpleOkResponse

    @GET("api/tags/{id}/person")
    suspend fun personDetail(@Path("id") id: String): PersonDetail

    @POST("api/tags/{id}/images")
    suspend fun addTagToImages(@Path("id") id: String, @Body body: BatchTagImagesRequest): BatchTagImagesResponse

    /* ------------------------------ 访问日志 ------------------------------ */

    /** 服务端硬编码 take:500，无分页参数 */
    @GET("api/audit-logs")
    suspend fun auditLogs(): AuditLogListResponse

    /* ------------------------------ 系统设置 ------------------------------ */

    @GET("api/settings")
    suspend fun settings(): SystemSettings

    /**
     * 部分更新（服务端 `z.record(z.unknown())` 无字段白名单，
     * 但**数字必须发 JSON number**，发字符串会通过校验却不被运行时读取）。
     * 响应不含 deepseek/bili/ffmpeg 等计算字段，保存后需再 GET 一次。
     */
    @PATCH("api/settings")
    suspend fun patchSettings(@Body body: RequestBody): Response<okhttp3.ResponseBody>

    @GET("api/ffmpeg/status")
    suspend fun ffmpegStatus(): EngineStatus

    @POST("api/ffmpeg/download")
    suspend fun downloadFfmpeg(): OkTaskResponse

    @GET("api/ai/status")
    suspend fun aiStatus(): AiStatus

    /** body: `{"variant":"3b"|"7b","mirror":"https://hf-mirror.com"}`；火忘式，需轮询 ai/status */
    @POST("api/ai/download")
    suspend fun downloadAi(@Body body: RequestBody): OkTaskResponse

    /**
     * 同步阻塞最长 150 s，读超时需 ≥ 180 s（本工程用 [AppContainer.longApi]）。
     * 文件不完整时返回 200 `{ok:false,error}`，启动失败返回 500 `{message}`——两种都要处理。
     */
    @POST("api/ai/start")
    suspend fun startAi(@Body body: RequestBody): OkTaskResponse

    @POST("api/ai/stop")
    suspend fun stopAi(): OkTaskResponse

    /* ------------------------------ DeepSeek ------------------------------ */

    @GET("api/deepseek/summary")
    suspend fun deepseekSummary(): DeepseekSummary

    @POST("api/deepseek/refresh")
    suspend fun deepseekRefresh(): DeepseekSummary

    @POST("api/deepseek/keys")
    suspend fun deepseekAddKey(@Body body: DeepseekKeyCreateRequest): DeepseekKeyCreateResponse

    @PATCH("api/deepseek/keys/{id}")
    suspend fun deepseekUpdateKey(@Path("id") id: String, @Body body: DeepseekKeyUpdateRequest): DeepseekSummary

    @DELETE("api/deepseek/keys/{id}")
    suspend fun deepseekDeleteKey(@Path("id") id: String): DeepseekSummary

    @POST("api/deepseek/merge")
    suspend fun deepseekMerge(@Body body: DeepseekMergeRequest): DeepseekSummary

    @PUT("api/deepseek/config")
    suspend fun deepseekConfig(@Body body: DeepseekConfigRequest): DeepseekSummary

    /* ------------------------------ B站扫码 ------------------------------ */

    @POST("api/bili/qr/create")
    suspend fun biliQrCreate(): BiliQrCreateResponse

    @POST("api/bili/qr/poll")
    suspend fun biliQrPoll(@Body body: BiliQrPollRequest): BiliQrPollResponse

    /* ------------------------------ 视频解析 ------------------------------ */

    /**
     * 解析分享链接。**无需登录**。
     * 失败时是 HTTP 400 + `{ok:false,msg}`，因此用 [Response] 包裹以便读取 body。
     * 单次可能耗时十几秒，读超时建议 ≥ 60 s。
     */
    @POST("api/parse")
    suspend fun parse(@Body body: RequestBody): Response<ParseResponse>

    /** 把解析出的上游直链交给服务端后台下载入库 */
    @POST("api/parse/import")
    suspend fun parseImport(@Body body: ParseImportRequest): ParseImportCreated

    /** 建议 800 ms 轮询；status ∈ {done,error} 时停止 */
    @GET("api/parse/import/{id}")
    suspend fun parseImportStatus(@Path("id") id: String): ParseImportStatus

    /** 幂等：任务不存在也返回 {ok:true}；取消是异步生效的 */
    @DELETE("api/parse/import/{id}")
    suspend fun cancelParseImport(@Path("id") id: String): SimpleOkResponse
}
