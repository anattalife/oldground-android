package com.brandonthomas.oldground.map

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Shader
import android.os.Build
import com.brandonthomas.oldground.geo.TileMath
import com.brandonthomas.oldground.geo.XY
import java.io.File
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Renders one map tile of an old map: for each tile pixel, which scan pixel belongs there.
 *
 * The tile is covered by a grid of small triangles. Each grid point is mapped from map
 * coordinates to the scan with [toImg]; only the part of the scan under the tile is
 * decoded (so huge scans never load whole), and Android's canvas stretches it onto the
 * triangles. Anything off the scan stays transparent.
 */
class TileRenderer(private val scanPath: String, private val imgW: Int, private val imgH: Int) : AutoCloseable {
    private val decoder: BitmapRegionDecoder =
        if (Build.VERSION.SDK_INT >= 31) BitmapRegionDecoder.newInstance(scanPath)
        else @Suppress("DEPRECATION") BitmapRegionDecoder.newInstance(scanPath, false)!!

    private val out = Bitmap.createBitmap(TileMath.TILE, TileMath.TILE, Bitmap.Config.ARGB_8888)
    private val canvas = Canvas(out)

    /**
     * @param grid cells per side (1 for a straight scaling, more for a bent overlay)
     * @return false if the tile is entirely off the scan (nothing written)
     */
    fun render(z: Int, x: Int, y: Int, grid: Int, file: File, toImg: (XY) -> XY?): Boolean {
        val b = TileMath.bounds(z, x, y)
        val n = grid + 1
        val img = arrayOfNulls<XY>(n * n)
        var minX = Double.POSITIVE_INFINITY; var minY = Double.POSITIVE_INFINITY
        var maxX = Double.NEGATIVE_INFINITY; var maxY = Double.NEGATIVE_INFINITY
        for (j in 0 until n) for (i in 0 until n) {
            val mx = b[0] + (b[2] - b[0]) * i / grid
            val my = b[3] - (b[3] - b[1]) * j / grid
            val p = toImg(XY(mx, my)) ?: return false
            img[j * n + i] = p
            if (p.x < minX) minX = p.x; if (p.y < minY) minY = p.y
            if (p.x > maxX) maxX = p.x; if (p.y > maxY) maxY = p.y
        }
        // The part of the scan under this tile, with a pixel of margin.
        val rx0 = max(0, floor(minX).toInt() - 1)
        val ry0 = max(0, floor(minY).toInt() - 1)
        val rx1 = min(imgW, ceil(maxX).toInt() + 1)
        val ry1 = min(imgH, ceil(maxY).toInt() + 1)
        if (rx1 - rx0 < 1 || ry1 - ry0 < 1) return false

        // Decode only as sharply as the tile needs.
        val density = max(rx1 - rx0, ry1 - ry0) / (TileMath.TILE * 1.25)
        var sample = 1
        while (sample * 2 <= density) sample *= 2
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val part = decoder.decodeRegion(Rect(rx0, ry0, rx1, ry1), opts) ?: return false

        // One transparent pixel all round, so stretching past the scan's edge shows nothing.
        val padded = Bitmap.createBitmap(part.width + 2, part.height + 2, Bitmap.Config.ARGB_8888)
        Canvas(padded).drawBitmap(part, 1f, 1f, null)
        part.recycle()
        val sx = (rx1 - rx0).toDouble() / (padded.width - 2)
        val sy = (ry1 - ry0).toDouble() / (padded.height - 2)

        val verts = FloatArray(n * n * 2)
        val texs = FloatArray(n * n * 2)
        val step = TileMath.TILE.toFloat() / grid
        for (j in 0 until n) for (i in 0 until n) {
            val k = (j * n + i) * 2
            verts[k] = i * step
            verts[k + 1] = j * step
            val p = img[j * n + i]!!
            texs[k] = ((p.x - rx0) / sx + 1).toFloat()
            texs[k + 1] = ((p.y - ry0) / sy + 1).toFloat()
        }
        val idx = ShortArray(grid * grid * 6)
        var q = 0
        for (j in 0 until grid) for (i in 0 until grid) {
            val a = (j * n + i).toShort()
            val bb = (j * n + i + 1).toShort()
            val c = ((j + 1) * n + i).toShort()
            val d = ((j + 1) * n + i + 1).toShort()
            idx[q++] = a; idx[q++] = bb; idx[q++] = c
            idx[q++] = bb; idx[q++] = d; idx[q++] = c
        }

        out.eraseColor(Color.TRANSPARENT)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG).apply {
            shader = BitmapShader(padded, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        }
        canvas.drawVertices(Canvas.VertexMode.TRIANGLES, verts.size, verts, 0, texs, 0, null, 0, idx, 0, idx.size, paint)
        padded.recycle()

        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        tmp.outputStream().use {
            val fmt = if (Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSY else @Suppress("DEPRECATION") Bitmap.CompressFormat.WEBP
            out.compress(fmt, 82, it)
        }
        tmp.renameTo(file)
        return true
    }

    override fun close() {
        decoder.recycle()
        out.recycle()
    }
}
