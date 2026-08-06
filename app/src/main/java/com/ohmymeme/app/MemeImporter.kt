package com.ohmymeme.app

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File

object MemeImporter {

    private const val TAG = "OhMyMeme/MemeImporter"

    fun importUris(context: Context, uris: List<Uri>): Int {
        val db = MemeDb.get(context)
        val cacheDir = StoragePaths.cacheDir(context)
        cacheDir.mkdirs()
        android.util.Log.d(TAG, "import ${uris.size} uri(s)")
        var imported = 0
        for (uri in uris) {
            try {
                val originalName = queryDisplayName(context, uri)
                    ?.substringBeforeLast('.')
                    ?: "未命名"
                val srcExt = detectExtFromUri(context, uri) ?: ".png"
                val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: continue

                // 隐写 GIF：解码还原原图后只导入还原结果（fromStego=1），GIF 本身不入库，与桌面端一致
                val decoded = if (srcExt == ".gif" && GifStego.hasStego(bytes)) {
                    GifStego.decode(bytes, AndroidGifDecoder::webpToRgba)
                } else null

                val payload: ByteArray
                val ext: String
                val fromStego: Int
                if (decoded != null) {
                    payload = decoded.bytes
                    ext = ".${decoded.ext}"
                    fromStego = 1
                } else {
                    payload = bytes
                    ext = srcExt
                    fromStego = 0
                }

                val fhash = FileUtils.sha256(java.io.ByteArrayInputStream(payload))
                if (db.getByHash(fhash) != null) continue
                val dst = File(cacheDir, "${fhash.substring(0, 16)}$ext")
                dst.writeBytes(payload)
                val dims = CacheScanner.decodeBounds(dst)
                val mime = "image/${ext.substring(1)}"
                db.addMeme(
                    filename = dst.name,
                    fileHash = fhash,
                    width = dims.first,
                    height = dims.second,
                    fileSize = dst.length(),
                    mimeType = mime,
                    originalName = originalName,
                    fromStego = fromStego
                )
                imported++
            } catch (e: Exception) {
                android.util.Log.w(TAG, "import failed for $uri: $e")
            }
        }
        android.util.Log.d(TAG, "import finished, imported=$imported")
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
