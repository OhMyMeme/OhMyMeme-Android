package com.ohmymeme.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File

object Thumbnailer {

    fun getThumbPath(context: Context, memeId: Long, filename: String, size: Int = 150): String? {
        val thumbDir = StoragePaths.thumbnailDir(context)
        val thumbPath = File(thumbDir, "${memeId}_${size}.png")
        if (thumbPath.exists()) return thumbPath.absolutePath
        val memePath = findMemeFile(context, filename) ?: return null
        return try {
            val bitmap = decodeScaled(memePath, size) ?: return null
            thumbDir.mkdirs()
            thumbPath.outputStream().use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            bitmap.recycle()
            thumbPath.absolutePath
        } catch (e: Exception) {
            null
        }
    }

    fun findMemeFile(context: Context, filename: String): File? {
        val cacheDir = StoragePaths.cacheDir(context)
        val direct = File(cacheDir, filename)
        if (direct.exists()) return direct
        return cacheDir.walkTopDown().firstOrNull { it.isFile && it.name == filename }
    }

    private fun decodeScaled(file: File, size: Int): Bitmap? {
        val bounds = BitmapFactory.Options()
        bounds.inJustDecodeBounds = true
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / sample > size * 2 || bounds.outHeight / sample > size * 2) {
            sample *= 2
        }
        val opts = BitmapFactory.Options()
        opts.inSampleSize = sample
        val src = BitmapFactory.decodeFile(file.absolutePath, opts) ?: return null
        val scaled = Bitmap.createScaledBitmap(src, size, size, true)
        if (scaled !== src) src.recycle()
        return scaled
    }
}
