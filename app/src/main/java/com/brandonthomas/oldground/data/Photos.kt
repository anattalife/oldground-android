package com.brandonthomas.oldground.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import java.io.File
import kotlin.math.max
import kotlin.math.min

/**
 * Photos of finds, kept inside the app (files/oldground/photos). Each is shrunk to at
 * most 1600 px on its long side and saved as a JPEG of roughly 0.3–1 MB.
 */
object Photos {
    private const val MAX_SIDE = 1600

    fun dir(context: Context) = File(context.filesDir, "oldground/photos").apply { mkdirs() }
    fun file(context: Context, name: String) = File(dir(context), name)

    /** Copies a picture from the camera or library into the app. Returns its file name. */
    fun import(context: Context, uri: Uri): String? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_SIDE) sample *= 2
        val raw = context.contentResolver.openInputStream(uri)!!.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: return null
        // Turn it the right way up (phone cameras store the rotation separately).
        val rotation = runCatching {
            context.contentResolver.openInputStream(uri)!!.use {
                when (ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                    else -> 0f
                }
            }
        }.getOrDefault(0f)
        val scale = min(1f, MAX_SIDE.toFloat() / max(raw.width, raw.height))
        val m = Matrix().apply { postScale(scale, scale); postRotate(rotation) }
        val bmp = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true)
        val name = "p-${System.currentTimeMillis()}-${(1000..9999).random()}.jpg"
        file(context, name).outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        if (bmp !== raw) raw.recycle()
        bmp.recycle()
        name
    }.getOrNull()

    fun delete(context: Context, name: String) { file(context, name).delete(); thumbs.remove(name) }

    private val thumbs = object : LinkedHashMap<String, Bitmap>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>?) = size > 150
    }

    /** A small square picture for lists (cached). */
    fun thumb(context: Context, name: String, px: Int = 240): Bitmap? = synchronized(thumbs) {
        thumbs["$name@$px"]?.let { return it }
        val f = file(context, name)
        if (!f.exists()) return null
        val b = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, b)
        var sample = 1
        while (min(b.outWidth, b.outHeight) / (sample * 2) >= px) sample *= 2
        val src = BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val side = min(src.width, src.height)
        val sq = Bitmap.createBitmap(src, (src.width - side) / 2, (src.height - side) / 2, side, side)
        val out = Bitmap.createScaledBitmap(sq, px, px, true)
        // createBitmap/createScaledBitmap can hand back the same bitmap, so only free true copies.
        if (sq !== src && sq !== out) sq.recycle()
        if (src !== out) src.recycle()
        thumbs["$name@$px"] = out
        out
    }

    /** A round picture with a white ring and a soft shadow, for pins on the map. */
    fun mapMarker(context: Context, name: String, sizePx: Int): Bitmap? {
        val sq = thumb(context, name, sizePx) ?: return null
        val pad = (sizePx * 0.12f).toInt()
        val full = sizePx + pad * 2
        val out = Bitmap.createBitmap(full, full, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val cx = full / 2f
        val ring = sizePx * 0.09f
        val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x55000000; setShadowLayer(pad * 0.6f, 0f, pad * 0.25f, 0x55000000) }
        c.drawCircle(cx, cx, sizePx / 2f, shadow)
        c.drawCircle(cx, cx, sizePx / 2f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE })
        val inner = sizePx / 2f - ring
        val layer = Bitmap.createBitmap(full, full, Bitmap.Config.ARGB_8888)
        val lc = Canvas(layer)
        lc.drawCircle(cx, cx, inner, Paint(Paint.ANTI_ALIAS_FLAG))
        lc.drawBitmap(sq, null, RectF(cx - inner, cx - inner, cx + inner, cx + inner),
            Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN) })
        c.drawBitmap(layer, 0f, 0f, null)
        layer.recycle()
        return out
    }
}
