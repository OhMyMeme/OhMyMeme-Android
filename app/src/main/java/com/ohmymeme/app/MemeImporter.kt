package com.ohmymeme.app

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File

object MemeImporter {

    private const val TAG = "OhMyMeme/MemeImporter"

    fun importUris(context: Context, uris: List<Uri>): Int {
        var imported = 0
        for (uri in uris) {
            try {
                val originalName = queryDisplayName(context, uri)
                    ?: "未命名"
                val srcExt = detectExtFromUri(context, uri) ?: ".png"
                val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: continue
                if (importBytes(context, bytes, originalName, srcExt)) imported++
            } catch (e: Exception) {
                android.util.Log.w(TAG, "import failed for $uri: $e")
            }
        }
        android.util.Log.d(TAG, "import finished, imported=$imported")
        return imported
    }

    /**
     * 把内存字节按哈希去重后入库（LAN 推送/接收复用）。
     * @return true 表示真正入库（非重复）
     */
    fun importBytes(context: Context, bytes: ByteArray, originalName: String, srcExt: String? = null): Boolean {
        val db = MemeDb.get(context)
        val cacheDir = StoragePaths.cacheDir(context)
        cacheDir.mkdirs()
        val ext = srcExt ?: detectExt(bytes) ?: ".png"

        // 隐写 GIF：解码还原原图后只导入还原结果（fromStego=1），GIF 本身不入库，与桌面端一致
        val decoded = if (ext == ".gif" && GifStego.hasStego(bytes)) {
            GifStego.decode(bytes, AndroidGifDecoder::webpToRgba)
        } else null

        val payload: ByteArray
        val realExt: String
        val fromStego: Int
        if (decoded != null) {
            payload = decoded.bytes
            realExt = ".${decoded.ext}"
            fromStego = 1
        } else {
            payload = bytes
            realExt = ext
            fromStego = 0
        }

        val fhash = FileUtils.sha256(java.io.ByteArrayInputStream(payload))
        if (db.getByHash(fhash) != null) return false
        val dst = File(cacheDir, "${fhash.substring(0, 16)}$realExt")
        dst.writeBytes(payload)
        val dims = CacheScanner.decodeBounds(dst)
        val mime = "image/${realExt.substring(1)}"
        db.addMeme(
            filename = dst.name,
            fileHash = fhash,
            width = dims.first,
            height = dims.second,
            fileSize = dst.length(),
            mimeType = mime,
            originalName = originalName.substringBeforeLast('.'),
            fromStego = fromStego
        )
        android.util.Log.d(TAG, "imported ${dst.name}")
        return true
    }

    private fun detectExt(bytes: ByteArray): String? {
        val head = bytes.take(16).toByteArray()
        val ext = FileUtils.detectExt(head)
        return if (ext.isNotEmpty()) ext else null
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
