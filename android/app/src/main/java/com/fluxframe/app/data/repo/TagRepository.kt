package com.fluxframe.app.data.repo

import com.fluxframe.app.core.di.AppContainer
import com.fluxframe.app.core.net.apiCall
import com.fluxframe.app.data.model.CreateTagRequest
import com.fluxframe.app.data.model.PersonDetail
import com.fluxframe.app.data.model.TagItem
import com.fluxframe.app.data.model.UpdateTagRequest
import com.fluxframe.app.data.model.BatchTagImagesRequest
import com.fluxframe.app.data.model.AddTagToImageRequest

/** 标签 / 人物组 */
class TagRepository(private val container: AppContainer) {

    private val api get() = container.api

    suspend fun list(): Result<List<TagItem>> = apiCall { api.tags().items }

    suspend fun create(
        name: String,
        color: String = "#a78bfa",
        r18: Boolean = false,
        person: Boolean = false,
    ): Result<TagItem> = apiCall {
        api.createTag(CreateTagRequest(name.trim(), color, r18, person))
    }

    suspend fun update(
        id: String,
        name: String? = null,
        r18: Boolean? = null,
        color: String? = null,
        person: Boolean? = null,
    ): Result<TagItem> = apiCall { api.updateTag(id, UpdateTagRequest(name, r18, color, person)) }

    suspend fun delete(id: String): Result<Unit> = apiCall {
        api.deleteTag(id)
        Unit
    }

    /** 人物详情：该人物标签下所有图片的标签聚合 + 最新 8 张预览 */
    suspend fun personDetail(id: String): Result<PersonDetail> = apiCall { api.personDetail(id) }

    /** 给单张图片打标签（tagId 与 name 二选一，name 不存在会 404） */
    suspend fun addToImage(imageId: String, tagId: String? = null, name: String? = null): Result<Unit> =
        apiCall {
            api.addTagToImage(imageId, AddTagToImageRequest(tagId = tagId, name = name))
            Unit
        }

    suspend fun removeFromImage(imageId: String, tagId: String): Result<Unit> = apiCall {
        api.removeTagFromImage(imageId, tagId)
        Unit
    }

    /** 批量给多张图片贴同一个标签，返回真正新增的数量 */
    suspend fun addToImages(tagId: String, imageIds: List<String>): Result<Int> = apiCall {
        api.addTagToImages(tagId, BatchTagImagesRequest(imageIds)).added
    }
}
