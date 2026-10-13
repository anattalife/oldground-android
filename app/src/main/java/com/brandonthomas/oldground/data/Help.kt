package com.brandonthomas.oldground.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

@Serializable
data class HelpBlock(
    val p: String? = null,
    val h: String? = null,
    val steps: List<String>? = null,
    val list: List<String>? = null,
    val tip: String? = null,
)

@Serializable
data class HelpTopic(val id: String, val title: String, val summary: String, val blocks: List<HelpBlock> = emptyList())

@Serializable
data class HelpDoc(val version: Int = 0, val topics: List<HelpTopic> = emptyList())

/**
 * The guide shown under Settings › Help. It's the same text as oldgroundapp.com/help:
 * a copy ships with the app (so it works without signal) and a newer one is fetched
 * from the website when there's a connection.
 */
object Help {
    private const val URL_JSON = "https://oldgroundapp.com/help.json"
    private val json = Json { ignoreUnknownKeys = true }
    private val _doc = MutableStateFlow(HelpDoc())
    val doc: StateFlow<HelpDoc> = _doc

    private fun cache(ctx: Context) = File(ctx.filesDir, "help.json")

    suspend fun load(ctx: Context) = withContext(Dispatchers.IO) {
        if (_doc.value.topics.isEmpty()) {
            val bundled = runCatching { ctx.assets.open("help.json").use { json.decodeFromString<HelpDoc>(it.readBytes().decodeToString()) } }.getOrNull()
            val cached = runCatching { json.decodeFromString<HelpDoc>(cache(ctx).readText()) }.getOrNull()
            _doc.value = listOfNotNull(bundled, cached).maxByOrNull { it.version } ?: HelpDoc()
        }
        // Then try for a newer copy from the website.
        runCatching {
            val c = URL(URL_JSON).openConnection() as HttpURLConnection
            c.connectTimeout = 8000; c.readTimeout = 8000
            val text = c.inputStream.use { it.readBytes().decodeToString() }
            val fresh = json.decodeFromString<HelpDoc>(text)
            if (fresh.topics.isNotEmpty() && fresh.version >= _doc.value.version) {
                _doc.value = fresh
                cache(ctx).writeText(text)
            }
        }
    }
}
