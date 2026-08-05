package com.ohmymeme.app

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File

object MemeImporter {

    fun importUris(context: Context, uris: List<Uri>): Int {
        val db = MemeDb.get(context)
        val cacheDir = StoragePaths.cacheDir(context)
        cacheDir.mkdirs()
        var imported = 0
        for (uri in uris) {
            try {
                val originalName = queryDisplayName(context, uri)
                    ?.substringBeforeLast('.')
                    ?: "未命名"
                val ext = detectExtFromUri(context, uri) ?: ".png"
                val fhash = context.contentResolver.openInputStream(uri)?.use { stream ->
                    FileUtils.sha256(stream)
                } ?: continue
                if (db.getByHash(fhash) != null) continue
                val dst = File(cacheDir, "${fhash.substring(0, 16)}$ext")
                context.contentResolver.openInputStream(uri)?.use { input ->
                    dst.outputStream().use { output -> input.copyTo(output) }
                } ?: continue
                val dims = CacheScanner.decodeBounds(dst)
                val mime = "image/${ext.substring(1)}"
                db.addMeme(
                    filename = dst.name,
                    fileHash = fhash,
                    width = dims.first,
                    height = dims.second,
                    fileSize = dst.length(),
                    mimeType = mime,
                    originalName = originalName
                )
                imported++
            } catch (e: Exception) {
                // 单个文件失败不影响其余文件
            }
        }
        return imported
    }

    private fun queryDisplayName(context: Context, uri: Uri): String? {
        return try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cur ->
                    if (cur.moveToFirst()) cur.getString(0) else null
                }
        } catch (e: Exception) {
            null
        }
    }

    private fun detectExtFromUri(context: Context, uri: Uri): String? {
        return try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                val head = ByteArray(16)
                val n = stream.read(head)
                if (n <= 0) return@use null
                val ext = FileUtils.detectExt(head.copyOf(n))
                if (ext.isNotEmpty()) ext else null
            }
        } catch (e: Exception) {
            null
        }
    }
}
