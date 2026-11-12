package me.bmax.apatch.util

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.bmax.apatch.apApp
import me.bmax.apatch.Natives
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.Locale
import kotlin.math.roundToInt

private const val TAG = "ModuleRepo"

/**
 * Client for folk single entry php api: https://folk.mysqil.com/api/modules.php
 * All request use this single url, distinguished by action param.
 */
object ModuleRepo {
    const val BASE_URL = "https://folk.mysqil.com/api/modules.php"

    /** How many catalogue entries are read per request. */
    const val PAGE_SIZE = 30

    // 由上层 ViewModel 传入模块类型
    var globalModuleType: String = ""
    private fun getApiToken(): String = Natives.getApiToken(apApp)
    private fun getAppLang(): String {
        val lang = Locale.getDefault().language
        return if (lang == "zh") "zh" else "en"
    }

    /** One entry of list action */
    data class RepoModule(
        val id: String,
        val kpm: Boolean,
        val author: String,
        val description: String,
        val stars: Int = 0,
    )

    /** Result of reading a module document: content, a server error, or no pointer yet. */
    data class ModuleDocResult(
        val doc: RepoModuleDoc?,
        val error: String? = null,
        val unavailable: Boolean = false,
    )

    /** A module's module.json info */
    data class RepoModuleDoc(
        val moduleId: String,
        val name: String,
        val version: String,
        val versionCode: Int,
        val license: String,
        val authors: List<String>,
        val metamodule: Boolean,
        val sourceUrl: String,
        val downloadUrl: String,
        val summary: Map<String, String>,
        val description: Map<String, String>,
    ) {
        /** One-line summary in the device language, falling back to English. */
        fun summaryText(locale: Locale = Locale.getDefault()): String = localized(summary, locale)

        /** Longer description in the device language, falling back to English. */
        fun descriptionText(locale: Locale = Locale.getDefault()): String = localized(description, locale)
    }

    /** Reads one page of the catalogue. [kpm] filters by category when non-null. */
    suspend fun listModules(
        page: Int = 1,
        perPage: Int = PAGE_SIZE,
        kpm: Boolean? = null,
        search: String = "",
    ): List<RepoModule> = withContext(Dispatchers.IO) {
        val url = BASE_URL.toHttpUrl().newBuilder()
            .addQueryParameter("action", "list")
            .addQueryParameter("type", globalModuleType)
            .addQueryParameter("lang", getAppLang())
            .addQueryParameter("token", getApiToken())
            .addQueryParameter("page", page.toString())
            .addQueryParameter("per_page", perPage.toString())
            .apply {
                if (kpm != null) addQueryParameter("kpm", if (kpm) "1" else "0")
                if (search.isNotBlank()) addQueryParameter("search", search)
            }
            .build()
        val body = getStrict(url.toString())
        val root = JSONObject(body)
        root.optString("error").takeIf { it.isNotBlank() }?.let { throw IOException(it) }
        val array = root.optJSONArray("modules") ?: JSONArray()
        (0 until array.length()).mapNotNull { index ->
            val obj = array.optJSONObject(index) ?: return@mapNotNull null
            val id = obj.optString("id")
            if (id.isBlank()) null else RepoModule(
                id = id,
                kpm = obj.optBoolean("kpm", false),
                author = obj.optString("author"),
                description = obj.optString("description"),
                stars = obj.optInt("stars", 0),
            )
        }
    }

    suspend fun moduleDoc(module: RepoModule): RepoModuleDoc? = moduleDoc(module.id, module.kpm)

    suspend fun moduleDoc(id: String, kpm: Boolean): RepoModuleDoc? = withContext(Dispatchers.IO) {
        val url = BASE_URL.toHttpUrl().newBuilder()
            .addQueryParameter("action", "info")
            .addQueryParameter("type", globalModuleType)
            .addQueryParameter("lang", getAppLang())
            .addQueryParameter("token", getApiToken())
            .addQueryParameter("id", id)
            .apply { if (kpm) addQueryParameter("kpm", "1") }
            .build()
        val body = get(url.toString()) ?: return@withContext null
        parseDoc(body)
    }

    suspend fun moduleDocResult(id: String, kpm: Boolean): ModuleDocResult =
        withContext(Dispatchers.IO) {
            val url = BASE_URL.toHttpUrl().newBuilder()
                .addQueryParameter("action", "info")
                .addQueryParameter("type", globalModuleType)
                .addQueryParameter("lang", getAppLang())
                .addQueryParameter("token", getApiToken())
                .addQueryParameter("id", id)
                .apply { if (kpm) addQueryParameter("kpm", "1") }
                .build()
            val body = get(url.toString()) ?: return@withContext ModuleDocResult(null)
            parseDocResult(body)
        }

    suspend fun downloadModuleFile(
        id: String,
        kpm: Boolean,
        fileName: String,
        authorUrl: String = "",
        cancel: DownloadCancelHandle? = null,
        onProgress: (Int) -> Unit = {},
    ): File? {
        val candidates = LinkedHashSet<String>()
        val author = authorUrl.trim()
        when {
            author.startsWith("https://", ignoreCase = true) -> candidates += author
            author.isNotBlank() -> Log.i(TAG, "$id: author url is not https, using the relay")
        }
        candidates += downloadUrl(id, kpm)
        for (url in candidates) {
            downloadToCache(url, fileName, cancel, onProgress)?.let { return it }
            if (cancel?.isCancelled == true) return null
        }
        return null
    }

    fun downloadUrl(id: String, kpm: Boolean): String {
        val builder = BASE_URL.toHttpUrl().newBuilder()
            .addQueryParameter("action", "download")
            .addQueryParameter("type", globalModuleType)
            .addQueryParameter("lang", getAppLang())
            .addQueryParameter("token", getApiToken())
            .addQueryParameter("id", id)
        if (kpm) builder.addQueryParameter("kpm", "1")
        return builder.build().toString()
    }

    fun parseDoc(body: String): RepoModuleDoc? = runCatching {
        val obj = JSONObject(body)
        if (obj.has("error")) return null
        parseDocObject(obj)
    }.onFailure { Log.w(TAG, "parse module document failed", it) }.getOrNull()

    private fun parseDocResult(body: String): ModuleDocResult = runCatching {
        val obj = JSONObject(body)
        val error = obj.optString("error")
        if (error.isNotBlank()) {
            return ModuleDocResult(null, error, unavailable = true)
        }
        val doc = parseDocObject(obj) ?: return ModuleDocResult(null, unavailable = true)
        if (doc.version.isBlank() && doc.downloadUrl.isBlank()) {
            return ModuleDocResult(null, unavailable = true)
        }
        ModuleDocResult(doc)
    }.onFailure { Log.w(TAG, "parse module document failed", it) }
        .getOrElse { ModuleDocResult(null) }

    private fun parseDocObject(obj: JSONObject): RepoModuleDoc? {
        val moduleId = obj.optString("moduleid").ifBlank { obj.optString("module_id") }
        val name = obj.optString("modulename").ifBlank { moduleId }
        if (moduleId.isBlank() && name.isBlank()) return null
        return RepoModuleDoc(
            moduleId = moduleId,
            name = name,
            version = obj.optString("version"),
            versionCode = if (obj.has("VersionCode")) obj.optInt("VersionCode") else obj.optInt("versionCode"),
            license = obj.optString("license"),
            authors = obj.optJSONArray("authors").toStringList(),
            metamodule = obj.optBoolean("metamodule", false),
            sourceUrl = obj.optString("sourceUrl"),
            downloadUrl = obj.optString("downloadUrl").ifBlank { obj.optString("download_url") }.trim(),
            summary = obj.optJSONObject("summary").toStringMap(),
            description = obj.optJSONObject("description").toStringMap(),
        )
    }

    fun formatStars(stars: Int): String = when {
        stars < 1000 -> stars.toString()
        stars < 1_000_000 -> {
            val thousands = stars / 1000.0
            if (thousands >= 100) "${thousands.roundToInt()}k" else "${trimZero(thousands)}k"
        }
        else -> trimZero(stars / 1_000_000.0) + "M"
    }

    private fun trimZero(value: Double): String {
        val tenths = (value * 10).roundToInt()
        return if (tenths % 10 == 0) (tenths / 10).toString() else "%.1f".format(Locale.ROOT, tenths / 10.0)
    }

    fun isNewerVersion(remote: RepoModuleDoc, installedVersion: String, installedVersionCode: Int): Boolean {
        if (remote.versionCode > 0 && installedVersionCode > 0) {
            return remote.versionCode > installedVersionCode
        }
        return remote.version.isNotBlank() && remote.version != installedVersion
    }

    private fun localized(texts: Map<String, String>, locale: Locale): String {
        if (texts.isEmpty()) return ""
        for (key in listOf(locale.toLanguageTag(), locale.language, "en")) {
            texts[key]?.takeIf { it.isNotBlank() }?.let { return it }
        }
        return texts.values.firstOrNull { it.isNotBlank() } ?: ""
    }

    private fun getStrict(url: String): String =
        apApp.okhttpClient.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            response.body.string()
        }

    private fun get(url: String): String? = runCatching {
        apApp.okhttpClient.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (!response.isSuccessful) {
                Log.w(TAG, "GET $url -> ${response.code}")
                null
            } else {
                response.body.string()
            }
        }
    }.onFailure { Log.w(TAG, "GET $url failed", it) }.getOrNull()

    private fun JSONArray?.toStringList(): List<String> {
        if (this == null) return emptyList()
        return (0 until length()).mapNotNull { optString(it).takeIf { value -> value.isNotBlank() } }
    }

    private fun JSONObject?.toStringMap(): Map<String, String> {
        if (this == null) return emptyMap()
        return keys().asSequence()
            .mapNotNull { key -> optString(key).takeIf { it.isNotBlank() }?.let { key to it } }
            .toMap()
    }
}
