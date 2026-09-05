package com.pupurin.loom.bridge

import com.google.gson.annotations.SerializedName

// 与渲染层 preload/index.ts 的类型定义精确对齐（camelCase 字段）

data class ProjectMeta(
    val id: String,
    val name: String,
    val path: String,
    val createdAt: Long,
    val lastOpenedAt: Long,
    @SerializedName("_missing") val missing: Boolean? = null
)

data class SpriteMeta(
    val id: String,
    val name: String,
    val path: String
)

data class CharacterMeta(
    val id: String,
    val name: String,
    val varName: String,
    val color: String,
    val description: String,
    val sprites: List<SpriteMeta> = emptyList(),
    val avatar: AvatarConfig? = null
)

data class AvatarConfig(
    val type: String, // initial | sprite | custom
    val spriteId: String? = null,
    val customPath: String? = null
)

data class VariableMeta(
    val id: String,
    val name: String,
    val varName: String,
    val type: String, // int | float | str | bool
    val defaultValue: String,
    val description: String
)

data class PluginMeta(
    val id: String,
    val name: String,
    val version: String,
    val description: String,
    val author: String,
    val main: String,
    val builtin: Boolean,
    val enabled: Boolean,
    val trusted: Boolean,
    val hasMain: Boolean,
    val scaffolded: Boolean? = null
)

data class StorePlugin(
    val id: String,
    val name: String,
    val version: String,
    val description: String,
    val author: String,
    val repo: String,
    val subpath: String? = null,
    val tag: String? = null,
    val sha256: String? = null,
    val homepage: String? = null,
    val minLoomVersion: String? = null
)

data class RpyFileNode(
    val name: String,
    val path: String,
    val isDir: Boolean,
    val isStoryFile: Boolean,
    val children: List<RpyFileNode>? = null
)

data class FsEntry(
    val name: String,
    val isDir: Boolean,
    val path: String,
    val size: Long = 0,
    val isStoryFile: Boolean = false
)

data class BackendStatus(
    val running: Boolean,
    val port: Int? = null,
    val pid: Int? = null
)

data class UpdateCheckResult(
    val configured: Boolean,
    val current: String,
    val hasUpdate: Boolean? = null,
    val latest: String? = null,
    val url: String? = null,
    val notes: String? = null,
    val error: String? = null,
    val source: String? = null,
    val pageUrl: String? = null
)

data class SdkStatus(
    val found: Boolean = false,
    val exe: String? = null,
    val sdkDir: String? = null,
    val platform: String = "android",
    val downloadUrl: String = "https://www.renpy.org/latest.html",
    val webOk: Boolean = false,
    val androidOk: Boolean = false,
    val iosOk: Boolean = false,
    val androidSdkOk: Boolean = false,
    val jdkOk: Boolean = false,
    val xcodeOk: Boolean = false,
    val sdkWritable: Boolean = false
)