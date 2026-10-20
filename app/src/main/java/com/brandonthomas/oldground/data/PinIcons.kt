package com.brandonthomas.oldground.data

import android.graphics.Matrix
import android.graphics.Path
import androidx.core.graphics.PathParser

/**
 * The icons and colours a point can have. Each icon is a filled shape on a 24 × 24 grid
 * (SVG path data), drawn white on a coloured circle on the map and in lists. The same
 * shapes are used by the iPhone app, so keep the ids and paths in step with it.
 */
object PinIcons {
    data class Icon(val id: String, val label: String, val path: String, val evenOdd: Boolean = true)

    val ICONS = listOf(
        Icon("house", "House", "M12 3 2 11h3v9h5v-6h4v6h5v-9h3z"),
        Icon("cellar", "Cellar hole", "M3 6h18v12H3zm3 3v6h12V9z"),
        Icon("grave", "Grave", "M7 21V9a5 5 0 0 1 10 0v12zm4-12v2H9v2h2v4h2v-4h2v-2h-2V9z"),
        Icon("spring", "Spring", "M12 2s7 8 7 13a7 7 0 0 1-14 0c0-5 7-13 7-13z"),
        Icon("tree", "Tree", "M12 2 5 12h3l-3 5h6v5h2v-5h6l-3-5h3z"),
        Icon("fence", "Fence or gate", "M3 4h2v16H3zm16 0h2v16h-2zM6 7h12v2H6zm0 8h12v2H6zm2-6h2v6H8zm6 0h2v6h-2z"),
        Icon("coin", "Coin", "M12 2a10 10 0 1 0 0 20 10 10 0 0 0 0-20zm0 4a6 6 0 1 1 0 12 6 6 0 0 1 0-12z"),
        Icon("arrowhead", "Arrowhead", "M12 2 6 15l4 1v6h4v-6l4-1z"),
        Icon("bottle", "Bottle", "M10 2h4v4l2 3v12a1 1 0 0 1-1 1H9a1 1 0 0 1-1-1V9l2-3z"),
        Icon("key", "Key", "M8 7a5 5 0 1 0 4.6 7H15v3h3v-3h3v-3h-8.4A5 5 0 0 0 8 7zm0 3a2 2 0 1 1 0 4 2 2 0 0 1 0-4z"),
        Icon("dig", "Dig", "M14 2l8 8-2 2-2.5-2.5-5 5 1 1-4 4c-1.5 1.5-4 1.5-5.5 0s-1.5-4 0-5.5l4-4 1 1 5-5L12 4z"),
        Icon("look", "Look here", "M12 5C6 5 2 12 2 12s4 7 10 7 10-7 10-7-4-7-10-7zm0 3a4 4 0 1 1 0 8 4 4 0 0 1 0-8z"),
        Icon("flag", "Flag", "M5 2h2v20H5zM8 3h11l-3 4 3 4H8z"),
        Icon("star", "Star", "m12 2 3 7 7 .6-5.3 4.6L18.4 22 12 18l-6.4 4 1.7-7.8L2 9.6 9 9z"),
        Icon("camera", "Photo spot", "M9 4h6l2 3h3a1 1 0 0 1 1 1v11a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1V8a1 1 0 0 1 1-1h3zm3 5a4 4 0 1 0 0 8 4 4 0 0 0 0-8z"),
        Icon("question", "Unknown", "M12 2a10 10 0 1 0 0 20 10 10 0 0 0 0-20zm1 16h-2v-2h2zm2-7c-.6 1-2 1.4-2 3h-2c0-2.6 2-3 2.4-4A1.6 1.6 0 0 0 12 8a2 2 0 0 0-2 2H8a4 4 0 0 1 8 0c0 .4-.4.6-1 1z"),
        Icon("well", "Well", "M3 8 12 3l9 5zM5 9h2v3h10V9h2v3h1v9H4v-9h1zm3 6v4h8v-4z"),
        Icon("bridge", "Bridge", "M2 8h20v2h-2v10h-3v-4a5 5 0 0 0-10 0v4H4V10H2z"),
        Icon("rail", "Railroad", "M7 2h2v20H7zm8 0h2v20h-2zM4 5h16v2H4zm0 6h16v2H4zm0 6h16v2H4z", evenOdd = false),
        Icon("church", "Church or school", "M11 1h2v3h3v2h-3v3l6 4v10h-5v-5a2 2 0 0 0-4 0v5H5V13l6-4V6H8V4h3z"),
        Icon("mine", "Mine or cave", "M2 21V11a10 8 0 0 1 20 0v10h-4v-8a6 5 0 0 0-12 0v8z"),
        Icon("camp", "Camp", "M12 3l10 18H2zm0 7-4 11h8z"),
        Icon("x", "X marks it", "M5 3l7 7 7-7 2 2-7 7 7 7-2 2-7-7-7 7-2-2 7-7-7-7z", evenOdd = false),
        Icon("dot", "Dot", "M12 6a6 6 0 1 0 0 12 6 6 0 0 0 0-12z"),
    )

    val COLORS = listOf("#C0392B", "#C2410C", "#B45309", "#15803D", "#0F766E", "#2563EB", "#7C3AED", "#8D5524", "#374151")

    fun icon(id: String?) = ICONS.firstOrNull { it.id == id } ?: ICONS.last()

    /** The icon a pin made before icons existed shows. */
    fun defaultIcon(kind: PinKind) = when (kind) {
        PinKind.HOMESITE -> "house"
        PinKind.FIND -> "coin"
        PinKind.LIDAR -> "look"
        PinKind.PERMISSION -> "flag"
        PinKind.OTHER -> "dot"
    }

    private val cache = HashMap<String, Path>()

    /** The icon's shape scaled to [sizePx] × [sizePx], with its top left at [x], [y]. */
    fun path(id: String?, sizePx: Float, x: Float = 0f, y: Float = 0f): Path {
        val ic = icon(id)
        val base = cache.getOrPut(ic.id) {
            PathParser.createPathFromPathData(ic.path).apply { fillType = if (ic.evenOdd) Path.FillType.EVEN_ODD else Path.FillType.WINDING }
        }
        return Path(base).apply {
            transform(Matrix().apply { setScale(sizePx / 24f, sizePx / 24f); postTranslate(x, y) })
        }
    }
}

/** The pin's icon id (its own, or the default for its kind). */
val Pin.iconId: String get() = icon ?: PinIcons.defaultIcon(kind)

/** The pin's colour as #RRGGBB (its own, or the default for its kind). */
val Pin.colorHex: String get() = color ?: kind.color
