package com.brandonthomas.oldground.map

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.brandonthomas.oldground.data.Repo
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.net.HttpURLConnection
import java.net.URL
import java.util.Collections
import java.util.WeakHashMap

/**
 * The token for Esri satellite imagery. No permanent key ships in the app: oldgroundapp.com
 * hands out a shared token that lasts two weeks and is replaced a few days before it runs
 * out (tiles are cached by URL, so a token that changed daily would empty the cache).
 * Without a token, Satellite shows the free USGS imagery.
 */
object EsriToken {
    private const val ENDPOINT = "https://oldgroundapp.com/api/esri-token"
    private const val REFRESH_BEFORE_MS = 3L * 24 * 3600 * 1000

    @Volatile private var token: String? = null
    @Volatile private var expires: Long = 0
    @Volatile private var loading = false
    private val maps: MutableSet<OgMap> = Collections.newSetFromMap(WeakHashMap())

    /** The token if it's still good, else null. */
    fun current(): String? = token?.takeIf { expires > System.currentTimeMillis() }

    internal fun track(m: OgMap) = synchronized(maps) { maps.add(m) }

    /** Loads the saved token and fetches a new one when it's missing or close to expiring. */
    fun refresh(context: Context) {
        if (loading) return
        loading = true
        val prefs = context.applicationContext.getSharedPreferences("esri", Context.MODE_PRIVATE)
        Repo.scope.launch {
            try {
                val before = current()
                if (token == null) {
                    token = prefs.getString("token", null)
                    expires = prefs.getLong("expires", 0)
                }
                if (current() == null || expires - System.currentTimeMillis() < REFRESH_BEFORE_MS) {
                    runCatching {
                        val c = URL(ENDPOINT).openConnection() as HttpURLConnection
                        c.connectTimeout = 10_000; c.readTimeout = 10_000
                        if (c.responseCode == 200) {
                            val o = Json.parseToJsonElement(c.inputStream.bufferedReader().readText()).jsonObject
                            val t = o["token"]?.jsonPrimitive?.content
                            val e = o["expires"]?.jsonPrimitive?.long ?: 0
                            if (!t.isNullOrBlank() && e > System.currentTimeMillis()) {
                                token = t; expires = e
                                prefs.edit().putString("token", t).putLong("expires", e).apply()
                            }
                        }
                        c.disconnect()
                    }
                }
                if (current() != before) {
                    // Redraw open maps so Satellite picks up (or drops) Esri imagery.
                    val open = synchronized(maps) { maps.toList() }
                    Handler(Looper.getMainLooper()).post { open.forEach { it.refreshBase() } }
                }
            } finally {
                loading = false
            }
        }
    }
}
