package com.ohmymeme.app

import android.content.Context
import java.io.File

object CacheScanner {

    fun scan(context: Context): Int {
        val db = MemeDb.get(context)
        val cacheDir = StoragePaths.cacheDir(context)
        if (!cacheDir.exists()) return 0
        var added = 0
        cacheDir.walkTopDown().forEach { f ->
            if (f.isDirectory) return@forEach
            val ext = f.extension.lowercase().let { if (it.isEmpty()) "" else ".$it" }
            if (ext !in FileUtils.ALLOWED_EXT) return@forEach
            if (f.absolutePath.contains("thumbnails")) return@forEach
            // 跳过由 WebP 动图自动生成的 GIF（同名 .webp 存在即为生成物）
            val stem = f.name.substringBeforeLast('.')
            if (ext == ".gif" && File(f.parentFile, "$stem.webp").exists()) return@forEach
            if (db.getByFilename(f.name) != null) return@forEach
            val fhash = try {
                FileUtils.sha256(f)
            } catch (e: Exception) {
                return@forEach
            }
            if (db.getByHash(fhash) != null) return@forEach
            val dims = decodeBounds(f)
            val mime = if (ext.isNotEmpty()) "image/${ext.substring(1)}" else "image/png"
            val oname = f.name.substringBeforeLast('.')
            db.addMeme(
                filename = f.name,
                fileHash = fhash,
                width = dims.first,
                height = dims.second,
                fileSize = f.length(),
                mimeType = mime,
                originalName = oname
            )
            added++
        }
        return added
    }

    fun decodeBounds(file: File): Pair<Int, Int> {
        return try {
            val opts = android.graphics.BitmapFactory.Options()
            opts.inJustDecodeBounds = true
            android.graphics.BitmapFactory.decodeFile(file.absolutePath, opts)
            Pair(opts.outWidth, opts.outHeight)
        } catch (e: Exception) {
            Pair(0, 0)
        }
    }
}
