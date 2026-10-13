package com.brandonthomas.oldground.map

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.sinh
import kotlin.math.tan
import kotlin.math.cos

/**
 * Public land from USGS PAD-US (Protected Areas Database of the United States): who
 * manages each piece of federal, state, local and tribal land. Free, US government data.
 *
 * The service only answers queries (no map tiles), so the country is split into fixed
 * squares (zoom-12 tiles, about 10 km across). Each square is fetched once, saved on the
 * phone and reused, so public land also shows offline in places you've looked at or saved.
 */
object PublicLand {
    private const val QUERY =
        "https://services.arcgis.com/v01gqwM5QqNysAAi/arcgis/rest/services/Manager_Type_PADUS/FeatureServer/0/query"
    private const val FIELDS = "OBJECTID,Unit_Nm,Loc_Nm,Mang_Type,Mang_Name,Loc_Mang,Pub_Access,Des_Tp"
    private const val Z = 12
    /** Below this zoom there's too much land to fetch; the layer hides. */
    const val MIN_ZOOM = 10.0
    private const val MAX_TILES = 30
    private const val MAX_AGE_MS = 90L * 24 * 3600 * 1000

    private val mem = object : LinkedHashMap<String, List<Feature>>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<Feature>>?) = size > 120
    }
    private val fetchLock = Mutex()

    private fun dir(ctx: Context) = File(ctx.filesDir, "publicland").apply { mkdirs() }

    private fun tiles(b: DoubleArray): List<Pair<Int, Int>> {
        val n = 1 shl Z
        fun col(lng: Double) = floor((lng + 180) / 360 * n).toInt().coerceIn(0, n - 1)
        fun row(lat: Double): Int {
            val r = Math.toRadians(lat.coerceIn(-85.0, 85.0))
            return floor((1 - ln(tan(r) + 1 / cos(r)) / PI) / 2 * n).toInt().coerceIn(0, n - 1)
        }
        val out = ArrayList<Pair<Int, Int>>()
        for (x in col(b[0])..col(b[2])) for (y in row(b[3])..row(b[1])) out.add(x to y)
        return out
    }

    private fun tileBounds(x: Int, y: Int): DoubleArray {
        val n = (1 shl Z).toDouble()
        fun lat(yy: Int) = Math.toDegrees(atan(sinh(PI * (1 - 2 * yy / n))))
        return doubleArrayOf(x / n * 360 - 180, lat(y + 1), (x + 1) / n * 360 - 180, lat(y))
    }

    /**
     * Public land for a view (west, south, east, north), or null when zoomed out too far.
     * Uses the network when it can, saved squares otherwise.
     */
    suspend fun features(ctx: Context, b: DoubleArray, zoom: Double): FeatureCollection? = withContext(Dispatchers.IO) {
        if (zoom < MIN_ZOOM) return@withContext null
        val list = tiles(b)
        if (list.size > MAX_TILES) return@withContext null
        val seen = HashSet<String>()
        val out = ArrayList<Feature>()
        for ((x, y) in list) for (f in load(ctx, x, y)) {
            // The same piece of land comes back in every square it touches; keep one.
            val id = runCatching { f.getProperty("OBJECTID")?.asString }.getOrNull() ?: f.id() ?: f.toJson().hashCode().toString()
            if (seen.add(id)) out.add(f)
        }
        FeatureCollection.fromFeatures(out)
    }

    /** Saves public land for an offline area. */
    suspend fun prefetch(ctx: Context, b: DoubleArray) = withContext(Dispatchers.IO) {
        for ((x, y) in tiles(b).take(400)) runCatching { load(ctx, x, y) }
    }

    private suspend fun load(ctx: Context, x: Int, y: Int): List<Feature> {
        val key = "$Z-$x-$y"
        synchronized(mem) { mem[key] }?.let { return it }
        val file = File(dir(ctx), "$key.geojson")
        val fresh = file.exists() && System.currentTimeMillis() - file.lastModified() < MAX_AGE_MS
        val text = if (fresh) file.readText() else fetchLock.withLock {
            runCatching { fetch(x, y) }.getOrNull()?.also { t ->
                val tmp = File(file.path + ".tmp")
                tmp.writeText(t)
                tmp.renameTo(file)
            } ?: file.takeIf { it.exists() }?.readText()
        } ?: return emptyList()
        val feats = runCatching { FeatureCollection.fromJson(text).features().orEmpty() }.getOrDefault(emptyList())
        synchronized(mem) { mem[key] = feats }
        return feats
    }

    /** All public land touching one square, as one GeoJSON feature collection. */
    private fun fetch(x: Int, y: Int): String {
        val b = tileBounds(x, y)
        val all = ArrayList<String>()
        var offset = 0
        for (page in 0 until 10) {
            val url = QUERY +
                "?where=1%3D1&geometryType=esriGeometryEnvelope&inSR=4326&spatialRel=esriSpatialRelIntersects" +
                "&geometry=${"%.6f,%.6f,%.6f,%.6f".format(java.util.Locale.US, b[0], b[1], b[2], b[3])}" +
                "&outFields=$FIELDS&returnGeometry=true&outSR=4326&maxAllowableOffset=0.00008&geometryPrecision=5" +
                "&orderByFields=OBJECTID&resultOffset=$offset&f=geojson"
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 10000
            c.readTimeout = 30000
            c.setRequestProperty("User-Agent", "OldGround-Android")
            val body = c.inputStream.bufferedReader().use { it.readText() }
            if (body.contains("\"error\"") && !body.contains("\"features\"")) error("PAD-US query failed")
            val fc = FeatureCollection.fromJson(body)
            val feats = fc.features().orEmpty()
            feats.forEach { all.add(it.toJson()) }
            if (!Regex("\"exceededTransferLimit\"\\s*:\\s*true").containsMatchIn(body) || feats.isEmpty()) break
            offset += feats.size
        }
        return """{"type":"FeatureCollection","features":[${all.joinToString(",")}]}"""
    }

    // ---------- describing a piece of land ----------

    data class Info(val title: String, val manager: String, val access: String, val advice: String)

    private val agencies = mapOf(
        "NPS" to "National Park Service", "USFS" to "U.S. Forest Service", "BLM" to "Bureau of Land Management",
        "FWS" to "U.S. Fish & Wildlife Service", "USACE" to "U.S. Army Corps of Engineers", "BOR" to "Bureau of Reclamation",
        "DOD" to "Department of Defense", "TVA" to "Tennessee Valley Authority", "NOAA" to "NOAA", "DOE" to "Department of Energy",
        "SDNR" to "State Dept. of Natural Resources", "SPR" to "State Parks", "SFW" to "State Fish & Wildlife",
        "SDOL" to "State Land Dept.", "SLB" to "State Land Board", "SDC" to "State Dept. of Conservation",
        "OTHS" to "State agency", "CITY" to "City", "CNTY" to "County", "RWD" to "Regional water district",
        "REG" to "Regional agency", "JNT" to "Joint management", "NGO" to "Nonprofit", "PVT" to "Private", "UNK" to "Unknown",
    )

    fun describe(p: Map<String, String>): Info {
        val type = p["Mang_Type"].orEmpty()
        val manager = p["Loc_Mang"]?.takeIf { it.isNotBlank() } ?: agencies[p["Mang_Name"]] ?: p["Mang_Name"].orEmpty()
        val title = listOf(p["Unit_Nm"], p["Loc_Nm"]).firstOrNull { !it.isNullOrBlank() } ?: "Public land"
        val access = when (p["Pub_Access"]) {
            "OA" -> "Open to the public"
            "RA" -> "Restricted access"
            "XA" -> "Closed to the public"
            else -> "Public access unknown"
        }
        val advice = when (type) {
            "FED" -> if (p["Mang_Name"] == "NPS") "National Park land. Metal detecting is prohibited."
            else "Federal land. Detecting is prohibited in many federal areas, and digging up anything historic is a federal crime (Archaeological Resources Protection Act). Some Forest Service and BLM land allows detecting for modern items. Check with the local office first."
            "STAT" -> "State land. Rules depend on the state and the agency, and many require a permit. Check with them first."
            "LOC" -> "City or county land. Many parks allow detecting, some with a permit. Check with them first."
            "TRIB" -> "Tribal land. You need permission from the tribe."
            "DIST" -> "Managed by a regional agency. Check their rules first."
            "NGO", "PVT" -> "Conservation land, not public. Get permission from the owner first."
            else -> "Check who manages this land and their rules before detecting."
        }
        return Info(title, manager, access, "$advice Always confirm the rules yourself; boundaries are approximate.")
    }
}
