package com.brandonthomas.oldground.data

import com.brandonthomas.oldground.geo.AnchorPoint
import com.brandonthomas.oldground.geo.LngLat
import com.brandonthomas.oldground.geo.XY
import kotlinx.serialization.Serializable

@Serializable
data class Anchor(val id: String, val imgX: Double, val imgY: Double, val lng: Double, val lat: Double) {
    fun toPoint() = AnchorPoint(id, XY(imgX, imgY), LngLat(lng, lat))
}

/**
 * A set of map tiles on the phone: dir/{z}/{x}/{y}.webp, built level by level from
 * [zMin] up. [zDone] is the highest finished level (the map shows up to that and
 * enlarges it beyond), so a map becomes usable before every level is built.
 */
@Serializable
data class Pyramid(
    val dir: String,
    val zMin: Int,
    val zMax: Int,
    val zDone: Int = -1,
    /** Mercator bounds: minX, minY, maxX, maxY. */
    val bounds: List<Double>,
    /** For overlays: the anchors this was built from. */
    val key: String? = null,
) {
    val ready get() = zDone >= zMin
    val complete get() = zDone >= zMax
}

@Serializable
data class OldMap(
    val id: String,
    val name: String,
    val createdAt: Long,
    /** File name of the original scan inside the map's folder. */
    val image: String,
    val width: Int,
    val height: Int,
    val anchors: List<Anchor> = emptyList(),
    /** The scan cut into tiles (shown in the anchor editor, Split and Drive). */
    val scan: Pyramid? = null,
    /** The old map bent into place on today's map (shown in Overlay and Swipe). */
    val warp: Pyramid? = null,
) {
    fun anchorPoints() = anchors.map { it.toPoint() }
    val aligned get() = anchors.size >= 2
    fun anchorsKey() = anchors.joinToString("|") { "%.1f,%.1f,%.7f,%.7f".format(it.imgX, it.imgY, it.lng, it.lat) }
}

enum class PinKind(val label: String, val color: String) {
    HOMESITE("Old house", "#C0392B"),
    FIND("Find", "#D4A017"),
    LIDAR("LiDAR feature", "#7D3C98"),
    PERMISSION("Permission", "#1E8449"),
    OTHER("Other", "#2E86C1"),
}

@Serializable
data class Pin(
    val id: String,
    val name: String,
    val kind: PinKind,
    val lng: Double,
    val lat: Double,
    val note: String = "",
    val mapId: String? = null,
    val createdAt: Long,
    /** Photo file names in the photos folder, first one shown on the map. */
    val photos: List<String> = emptyList(),
    // Find details (used when kind is FIND).
    val category: String? = null,
    val material: String? = null,
    /** Depth in inches (shown in cm when metric). */
    val depthIn: Double? = null,
    val vdi: Int? = null,
    val detector: String? = null,
    /** 0 = not rated, 1..5 stars. */
    val rating: Int = 0,
) {
    val geo get() = LngLat(lng, lat)
}

/**
 * A place you detect, outlined on the map. Finds logged inside the outline belong to it,
 * and each day you found something or recorded a track there is a visit.
 */
@Serializable
data class Site(
    val id: String,
    val name: String,
    val access: String = "Permission",
    /** Outline corners as [lng, lat]. */
    val outline: List<List<Double>> = emptyList(),
    /** A photo of everything found here. */
    val photo: String? = null,
    val createdAt: Long,
    val note: String = "",
    /** The landowner's signed permission, if there is one. */
    val permission: Permission? = null,
    /** Calls, texts and visits with the landowner, newest last. */
    val contacts: List<ContactNote> = emptyList(),
) {
    val ring get() = outline.map { LngLat(it[0], it[1]) }
}

/** A permission slip signed on the phone. Stays on the phone; never uploaded. */
@Serializable
data class Permission(
    val ownerName: String,
    val ownerPhone: String = "",
    val ownerEmail: String = "",
    val hunterName: String,
    val startsAt: Long,
    /** Null: until the owner withdraws it. */
    val expiresAt: Long? = null,
    val conditions: List<String> = emptyList(),
    val extra: String = "",
    val signedAt: Long,
    /** Signature image and the slip as a PDF, in the permissions folder. */
    val signature: String,
    val pdf: String,
)

@Serializable
data class ContactNote(val at: Long, val how: String, val note: String = "")

/** Conditions offered on a permission slip; the first two start ticked. */
val PERMISSION_CONDITIONS = listOf(
    "Fill every hole and leave the ground as I found it.",
    "Call or text before each visit.",
    "Not during hunting season.",
    "Stay out of the yard, garden and planted fields.",
    "Show the owner everything I find.",
    "The owner may keep any find they want.",
    "I accept the risks of being on the property and won't hold the owner responsible for injuries.",
)

val CONTACT_KINDS = listOf("Call", "Text", "In person", "Email", "Other")

/** One trip to a site: from Start a visit to End visit. */
@Serializable
data class VisitSession(val siteId: String, val startedAt: Long, val endedAt: Long? = null)

val SITE_ACCESS = listOf("Permission", "Public land", "My property", "Club hunt")

val FIND_CATEGORIES = listOf("Coin", "Relic", "Button", "Jewelry", "Token", "Other")
val FIND_MATERIALS = listOf("Copper", "Silver", "Gold", "Brass", "Lead", "Iron", "Other")

@Serializable
data class TrackMeta(val id: String, val name: String, val startedAt: Long)

data class TrackPt(val lng: Double, val lat: Double, val timeMs: Long)

/** Anything a map can show. */
enum class Layer(val label: String) {
    OLDMAP("Old map"), LIDAR("LiDAR"), AERIAL("Satellite"), TOPO("Topo"), STREETS("Streets")
}

enum class LidarStyle(val label: String) { STRETCH("Stretched"), MULTI("Multi"), SUN("Move sun"), SLOPE("Slope") }

/** The places a layer can go in each hunt-map view. */
enum class Slot(val title: String, val default: Layer, val options: List<Layer>, val primary: Boolean) {
    OVERLAY_TOP("Top layer", Layer.LIDAR, listOf(Layer.OLDMAP, Layer.LIDAR, Layer.AERIAL, Layer.TOPO), true),
    OVERLAY_BOTTOM("Bottom layer", Layer.AERIAL, listOf(Layer.AERIAL, Layer.TOPO, Layer.STREETS, Layer.LIDAR), false),
    SWIPE_LEFT("Left of the divider", Layer.LIDAR, listOf(Layer.OLDMAP, Layer.LIDAR, Layer.AERIAL, Layer.TOPO, Layer.STREETS), true),
    SWIPE_RIGHT("Right of the divider", Layer.AERIAL, listOf(Layer.AERIAL, Layer.TOPO, Layer.STREETS, Layer.LIDAR), false),
    SPLIT_TOP("Top pane", Layer.LIDAR, Layer.entries, true),
    SPLIT_BOTTOM("Bottom pane", Layer.AERIAL, Layer.entries, false),
    DRIVE("Drive map", Layer.AERIAL, Layer.entries, true),
}

@Serializable
data class Settings(
    val lidarStyle: LidarStyle = LidarStyle.STRETCH,
    val lidarAzimuth: Int = 315,
    val overlayOpacity: Float = 0.75f,
    val metric: Boolean = false,
    /** Detector and settings from the last find, offered for the next one. */
    val lastDetector: String = "",
    /** Show public land (USGS PAD-US) on real-world maps. */
    val publicLand: Boolean = true,
    /** Background of the lower map in the anchor editor. */
    val alignBase: Layer = Layer.STREETS,
    /** The old map last shown on the Map tab, reopened next time. */
    val lastOldMap: String? = null,
    /** Record your path (GPS track) while a visit is on. */
    val recordPath: Boolean = true,
    /** Your name, for permission slips. */
    val myName: String = "",
    /** Keep the screen on while the Map tab is showing. */
    val keepScreenOn: Boolean = true,
    /** Remembered layer choices (never OLDMAP, which depends on the map that's open). */
    val slots: Map<Slot, Layer> = emptyMap(),
)

@Serializable
data class AppData(
    val maps: List<OldMap> = emptyList(),
    val pins: List<Pin> = emptyList(),
    val tracks: List<TrackMeta> = emptyList(),
    val sites: List<Site> = emptyList(),
    /** Every visit started at a site; the one with no end is happening now. */
    val visits: List<VisitSession> = emptyList(),
    /** The track being recorded, if any. */
    val recording: String? = null,
    val settings: Settings = Settings(),
)
