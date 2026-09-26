package com.mobai.jm.util

import android.content.Context
import com.mobai.jm.data.BlockTagsStore
import com.mobai.jm.data.FavItem
import com.mobai.jm.data.FavStore
import com.mobai.jm.data.HistoryItem
import com.mobai.jm.data.HistoryStore
import com.mobai.jm.data.SearchHistoryStore
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * 数据导入导出（.json 备份文件）。
 * 兼容性策略：未知字段忽略、缺失字段跳过、按数据项合并（不覆盖现有数据），
 * 文件里的 schema 版本仅作提示，不阻断导入（向下兼容）。
 */
object DataTransfer {
    const val SCHEMA = 1

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
    }

    // ───────────── 导出 ─────────────

    fun buildExport(
        context: Context,
        includeFav: Boolean,
        includeHistory: Boolean,
        includeConfig: Boolean,
    ): String {
        val prefs = AppPrefs(context)
        val root = buildJsonObject {
            put("app", "mobai")
            put("schema", SCHEMA)
            put("exportedAt", System.currentTimeMillis())
            if (includeFav) {
                putJsonObject("favorites") {
                    putJsonArray("items") {
                        FavStore.list.forEach { add(json.encodeToJsonElement(FavItem.serializer(), it)) }
                    }
                    putJsonArray("tags") { FavStore.favTags.forEach { add(it) } }
                }
            }
            if (includeHistory) {
                putJsonObject("history") {
                    putJsonArray("browse") {
                        HistoryStore.list.forEach { add(json.encodeToJsonElement(HistoryItem.serializer(), it)) }
                    }
                    putJsonArray("search") { SearchHistoryStore.list.forEach { add(it) } }
                }
            }
            if (includeConfig) {
                putJsonObject("config") {
                    put("theme", ThemePrefs(context).mode.name)
                    put("autoCache", prefs.autoCache)
                    put("randomPageSize", prefs.randomPageSize)
                    put("logLevel", prefs.logLevel)
                    put("downloadPath", prefs.downloadPath)
                    put("historyEnabled", prefs.historyEnabled)
                    put("favSort", prefs.favSort)
                    put("historySort", prefs.historySort)
                    putJsonArray("blockedTags") { BlockTagsStore.list.forEach { add(it) } }
                }
            }
        }
        return json.encodeToString(JsonObject.serializer(), root)
    }

    // ───────────── 解析与描述 ─────────────

    fun parse(text: String): JsonObject? =
        runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()

    private fun countArray(parent: JsonObject?, key: String): Int =
        runCatching { parent?.get(key)?.jsonArray?.size ?: 0 }.getOrDefault(0)

    fun describe(obj: JsonObject): String {
        val schema = runCatching { (obj["schema"] as? JsonPrimitive)?.intOrNull ?: 0 }.getOrDefault(0)
        val fav = runCatching { obj["favorites"]?.jsonObject }.getOrNull()
        val his = runCatching { obj["history"]?.jsonObject }.getOrNull()
        val cfg = runCatching { obj["config"]?.jsonObject }.getOrNull()
        val favItems = countArray(fav, "items")
        val favTags = countArray(fav, "tags")
        val browse = countArray(his, "browse")
        val search = countArray(his, "search")
        return buildString {
            append("备份内容：\n")
            append("· 收藏：$favItems 项（标签 $favTags 个）\n")
            append("· 历史：浏览 $browse 条 / 搜索 $search 条\n")
            append("· 系统配置：")
            append(if (cfg != null) "有" else "无")
            append("\n（schema v$schema · 当前支持 v$SCHEMA · 向下兼容）")
        }
    }

    // ───────────── 导入（合并） ─────────────

    fun applyImport(
        context: Context,
        obj: JsonObject,
        includeFav: Boolean,
        includeHistory: Boolean,
        includeConfig: Boolean,
    ): String {
        var favAdded = 0
        var histAdded = 0

        if (includeFav) {
            runCatching {
                val fav = obj["favorites"]?.jsonObject ?: return@runCatching
                val items = fav["items"]?.jsonArray?.mapNotNull {
                    runCatching { json.decodeFromJsonElement(FavItem.serializer(), it) }.getOrNull()
                }.orEmpty()
                favAdded = FavStore.importItems(items)
                val t = fav["tags"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty()
                FavStore.importTags(t)
            }
        }

        if (includeHistory) {
            runCatching {
                val his = obj["history"]?.jsonObject ?: return@runCatching
                val browse = his["browse"]?.jsonArray?.mapNotNull {
                    runCatching { json.decodeFromJsonElement(HistoryItem.serializer(), it) }.getOrNull()
                }.orEmpty()
                histAdded = HistoryStore.importItems(browse)
                val sq = his["search"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty()
                SearchHistoryStore.importAll(sq)
            }
        }

        if (includeConfig) {
            runCatching {
                val cfg = obj["config"]?.jsonObject ?: return@runCatching
                val prefs = AppPrefs(context)
                cfg["theme"]?.jsonPrimitive?.contentOrNull?.let {
                    runCatching { ThemePrefs(context).mode = ThemeMode.valueOf(it) }
                }
                cfg["autoCache"]?.jsonPrimitive?.booleanOrNull?.let { prefs.autoCache = it }
                cfg["randomPageSize"]?.jsonPrimitive?.intOrNull?.let { prefs.randomPageSize = it }
                cfg["logLevel"]?.jsonPrimitive?.contentOrNull?.let { prefs.logLevel = it }
                cfg["downloadPath"]?.jsonPrimitive?.contentOrNull?.let { prefs.downloadPath = it }
                cfg["historyEnabled"]?.jsonPrimitive?.booleanOrNull?.let { prefs.historyEnabled = it }
                cfg["favSort"]?.jsonPrimitive?.contentOrNull?.let { prefs.favSort = it }
                cfg["historySort"]?.jsonPrimitive?.contentOrNull?.let { prefs.historySort = it }
                cfg["blockedTags"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }?.let {
                    BlockTagsStore.importAll(it)
                }
            }
        }

        return "导入完成：收藏 +$favAdded 项，历史 +$histAdded 条，" +
            "配置已应用（主题等部分设置重启后生效）"
    }
}
