package com.ohmymeme.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory

object Thumbnailer {

    private const val TAG = "OhMyMeme/Thumbnailer"

    fun getThumbBitmap(context: Context, memeId: Long, filename: String, size: Int = 150): Bitmap? {
        val thumbDir = StoragePaths.thumbnailDir(context)
        val thumb = thumbDir.child("${memeId}_${size}.png")
        if (thumb.exists) {
            decodeStorFile(thumb)?.let { return it }
            thumb.delete()
        }
        val memePath = findMemeFile(context, filename) ?: return null
        return try {
            val bitmap = decodeScaled(memePath, size) ?: return null
            val out = thumbDir.createFile("${memeId}_${size}.png", "image/png")
            out.openOutputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            android.util.Log.d(TAG, "generated thumb for $filename")
            bitmap
        } catch (e: Exception) {
            android.util.Log.w(TAG, "thumb failed for $filename: $e")
            null
        }
    }

    fun findMemeFile(context: Context, filename: String): StorFile? {
        val cacheDir = StoragePaths.cacheDir(context)
        val direct = cacheDir.child(filename)
        if (direct.exists) return direct
        return cacheDir.listFilesRecursive().firstOrNull { !it.isDirectory && it.name == filename }
    }

    fun cloudThumbFile(context: Context, sha256: String): StorFile? {
        if (sha256.isEmpty()) return null
        val thumb = StoragePaths.thumbnailDir(context).child("$sha256.webp")
        return if (thumb.exists) thumb else null
    }

    fun cloudThumbBitmap(context: Context, sha256: String): Bitmap? {
        val thumb = cloudThumbFile(context, sha256) ?: return null
        return decodeStorFile(thumb)
    }

    /** 从原图生成云端缩略图字节（最长边 ≤ maxSide 保持宽高比，WebP q85，对齐桌面端 thumbnails/{sha}.webp） */
    fun cloudThumbWebpBytes(context: Context, filename: String, maxSide: Int = 150): ByteArray? {
        val src = findMemeFile(context, filename) ?: return null
        return try {
            val bitmap = decodeFit(src, maxSide) ?: return null
            val out = java.io.ByteArrayOutputStream()
            @Suppress("DEPRECATION")
            val format = if (android.os.Build.VERSION.SDK_INT >= 30) {
                Bitmap.CompressFormat.WEBP_LOSSY
            } else {
                Bitmap.CompressFormat.WEBP
            }
            bitmap.compress(format, 85, out)
            bitmap.recycle()
            out.toByteArray()
        } catch (e: Exception) {
            null
        }
    }

    private fun decodeStorFile(stor: StorFile): Bitmap? {
        return try {
            stor.openInputStream().use { BitmapFactory.decodeStream(it, null, null) }
        } catch (e: Exception) {
            null
        }
    }

    private fun decodeScaled(stor: StorFile, size: Int): Bitmap? {
        val bounds = BitmapFactory.Options()
        bounds.inJustDecodeBounds = true
        stor.openInputStream().use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / sample > size * 2 || bounds.outHeight / sample > size * 2) {
            sample *= 2
        }
        val opts = BitmapFactory.Options()
        opts.inSampleSize = sample
        val src = stor.openInputStream().use { BitmapFactory.decodeStream(it, null, opts) } ?: return null
        val scaled = Bitmap.createScaledBitmap(src, size, size, true)
        if (scaled !== src) src.recycle()
        return scaled
    }

    private fun decodeFit(stor: StorFile, maxSide: Int): Bitmap? {
        val bounds = BitmapFactory.Options()
        bounds.inJustDecodeBounds = true
        stor.openInputStream().use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / sample > maxSide * 2 || bounds.outHeight / sample > maxSide * 2) {
            sample *= 2
        }
        val opts = BitmapFactory.Options()
        opts.inSampleSize = sample
        val src = stor.openInputStream().use { BitmapFactory.decodeStream(it, null, opts) } ?: return null
        val longest = maxOf(src.width, src.height)
        if (longest <= maxSide) return src
        val scale = maxSide.toFloat() / longest
        val w = maxOf(1, Math.round(src.width * scale))
        val h = maxOf(1, Math.round(src.height * scale))
        val scaled = Bitmap.createScaledBitmap(src, w, h, true)
        if (scaled !== src) src.recycle()
        return scaled
    }
}
