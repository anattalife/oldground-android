package com.brandonthomas.oldground.data

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/**
 * Everything the app keeps, on the phone only: one JSON file for records, a folder per
 * old map (scan and tiles), and one plain-text file per hunt track (a line per point, so
 * a crash loses nothing already written).
 */
object Repo {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private lateinit var root: File
    private val dataFile get() = File(root, "data.json")

    private val _data = MutableStateFlow(AppData())
    val data: StateFlow<AppData> = _data

    private val _trackPoints = MutableStateFlow<Map<String, List<TrackPt>>>(emptyMap())
    val trackPoints: StateFlow<Map<String, List<TrackPt>>> = _trackPoints

    private var saveJob: Job? = null
    private val writeLock = Mutex()
    @Volatile private var started = false

    fun init(context: Context) {
        if (started) return
        started = true
        root = File(context.filesDir, "oldground").apply { mkdirs() }
        File(root, "maps").mkdirs()
        File(root, "tracks").mkdirs()
        val loaded = try {
            if (dataFile.exists()) json.decodeFromString(AppData.serializer(), dataFile.readText()) else AppData()
        } catch (e: Exception) {
            // Keep a copy of anything unreadable rather than overwriting it.
            dataFile.copyTo(File(root, "data-unreadable-${System.currentTimeMillis()}.json"), overwrite = true)
            AppData()
        }
        _data.value = loaded
        _trackPoints.value = loaded.tracks.associate { it.id to readTrack(it.id) }
    }

    val current get() = _data.value

    fun update(fn: (AppData) -> AppData) {
        _data.update(fn)
        scheduleSave()
    }

    private fun scheduleSave() {
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(300)
            saveNow()
        }
    }

    suspend fun saveNow() = withContext(Dispatchers.IO) {
        writeLock.withLock {
            val tmp = File(root, "data.json.tmp")
            tmp.writeText(json.encodeToString(AppData.serializer(), _data.value))
            if (!tmp.renameTo(dataFile)) {
                dataFile.delete()
                tmp.renameTo(dataFile)
            }
        }
    }

    fun newId(): String = UUID.randomUUID().toString().substring(0, 13).replace("-", "")

    // ---------- old maps ----------

    fun mapDir(id: String) = File(root, "maps/$id").apply { mkdirs() }
    fun mapFile(id: String, name: String) = File(mapDir(id), name)

    /** Copies a picked image into the app. Returns the new map, or an error message. */
    suspend fun importMap(context: Context, uri: Uri, name: String): Result<OldMap> = withContext(Dispatchers.IO) {
        runCatching {
            val id = newId()
            val type = context.contentResolver.getType(uri) ?: ""
            val ext = when {
                type.contains("png") -> "png"
                type.contains("webp") -> "webp"
                else -> "jpg"
            }
            val dest = mapFile(id, "original.$ext")
            context.contentResolver.openInputStream(uri)!!.use { input -> dest.outputStream().use { input.copyTo(it) } }
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(dest.absolutePath, opts)
            if (opts.outWidth <= 0 || opts.outHeight <= 0) {
                mapDir(id).deleteRecursively()
                error("That file isn't an image this phone can read. Use a JPEG, PNG or WebP scan.")
            }
            val map = OldMap(id, name, System.currentTimeMillis(), dest.name, opts.outWidth, opts.outHeight)
            update { it.copy(maps = listOf(map) + it.maps) }
            map
        }
    }

    fun updateMap(id: String, fn: (OldMap) -> OldMap) = update { d -> d.copy(maps = d.maps.map { if (it.id == id) fn(it) else it }) }

    fun deleteMap(id: String) {
        update { d -> d.copy(maps = d.maps.filter { it.id != id }) }
        scope.launch { File(root, "maps/$id").deleteRecursively() }
    }

    fun map(id: String?) = current.maps.firstOrNull { it.id == id }

    // ---------- pins ----------

    fun addPin(pin: Pin) = update { it.copy(pins = listOf(pin) + it.pins) }
    fun updatePin(id: String, fn: (Pin) -> Pin) = update { d -> d.copy(pins = d.pins.map { if (it.id == id) fn(it) else it }) }
    fun deletePin(id: String) = update { d -> d.copy(pins = d.pins.filter { it.id != id }) }

    // ---------- sites ----------

    fun addSite(site: Site) = update { it.copy(sites = listOf(site) + it.sites) }
    fun updateSite(id: String, fn: (Site) -> Site) = update { d -> d.copy(sites = d.sites.map { if (it.id == id) fn(it) else it }) }
    /** The visit happening now, if any. */
    val activeVisit get() = current.visits.lastOrNull { it.endedAt == null }

    fun beginVisit(siteId: String) = update { d ->
        val now = System.currentTimeMillis()
        d.copy(visits = d.visits.map { if (it.endedAt == null) it.copy(endedAt = now) else it } + VisitSession(siteId, now))
    }

    fun finishVisit() = update { d ->
        val now = System.currentTimeMillis()
        d.copy(visits = d.visits.map { if (it.endedAt == null) it.copy(endedAt = now) else it })
    }

    /** Removes the site only; its finds stay as pins. */
    fun deleteSite(id: String) = update { d -> d.copy(sites = d.sites.filter { it.id != id }) }

    // ---------- tracks ----------

    private fun trackFile(id: String) = File(root, "tracks/$id.csv")

    private fun readTrack(id: String): List<TrackPt> = try {
        trackFile(id).readLines().mapNotNull { line ->
            val p = line.split(',')
            if (p.size < 3) null else TrackPt(p[0].toDouble(), p[1].toDouble(), p[2].toLong())
        }
    } catch (e: Exception) {
        emptyList()
    }

    fun startTrack(): TrackMeta {
        val now = System.currentTimeMillis()
        val t = TrackMeta(newId(), "Track " + java.text.DateFormat.getDateInstance().format(java.util.Date(now)), now)
        trackFile(t.id).createNewFile()
        _trackPoints.update { it + (t.id to emptyList()) }
        update { it.copy(tracks = listOf(t) + it.tracks, recording = t.id) }
        return t
    }

    @Synchronized
    fun appendTrackPoint(id: String, pt: TrackPt) {
        trackFile(id).appendText("${pt.lng},${pt.lat},${pt.timeMs}\n")
        _trackPoints.update { it + (id to ((it[id] ?: emptyList()) + pt)) }
    }

    fun lastTrackPoint(id: String) = _trackPoints.value[id]?.lastOrNull()

    fun stopRecording() = update { it.copy(recording = null) }

    fun deleteTrack(id: String) {
        update { d -> d.copy(tracks = d.tracks.filter { it.id != id }, recording = if (d.recording == id) null else d.recording) }
        _trackPoints.update { it - id }
        trackFile(id).delete()
    }

    // ---------- settings ----------

    fun updateSettings(fn: (Settings) -> Settings) = update { it.copy(settings = fn(it.settings)) }

    fun cacheDir(context: Context) = context.cacheDir
}
