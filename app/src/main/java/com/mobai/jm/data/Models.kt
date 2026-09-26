package com.mobai.jm.data

import kotlinx.serialization.Serializable

@Serializable
data class Comic(
    val id: String,
    val title: String,
    val author: String,
    val coverUrl: String,
    val category: String? = null,
    val categorySub: String? = null,
)

data class JmCategory(
    val id: String,
    val name: String,
    val slug: String,
    val total: String,
)

/** 本子详情（可持久化到本地缓存） */
@Serializable
data class JmAlbum(
    val id: String,
    val name: String,
    val author: List<String>,
    val tags: List<String>,
    val likes: String,
    val views: String,
    val episodes: List<Episode>,
)

/** 章节（photo） */
@Serializable
data class Episode(
    val id: String,
    val title: String,
    val sort: String,
)
