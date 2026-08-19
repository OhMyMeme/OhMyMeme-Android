package com.ohmymeme.app

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns

object MemeImporter {

    private const val TAG = "OhMyMeme/MemeImporter"

    // 导入上限，对齐桌面端 src/config.py _IMPORT_MAX_BYTES / _IMPORT_MAX_PX
    const val MAX_BYTES = 20 * 1024 * 1024
    const val MAX_PX = 2560

    enum class ImportOutcome { IMPORTED, DUPLICATE, OVER_LIMIT, INVALID, FAILED }

    data class ImportResult(val imported: Int, val rejected: Int, val errors: List<String>)

    fun importUris(context: Context, uris: List<Uri>): ImportResult {
        var imported = 0
        var rejected = 0
        val errors = mutableListOf<String>()
        for (uri in uris) {
            val originalName = queryDisplayName(context, uri) ?: "未命名"
            try {
                val srcExt = detectExtFromUri(context, uri)
                val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: continue
                when (importBytes(context, bytes, originalName, srcExt)) {
                    ImportOutcome.IMPORTED -> imported++
                    ImportOutcome.DUPLICATE,
                    ImportOutcome.OVER_LIMIT,
                    ImportOutcome.INVALID -> rejected++
                    ImportOutcome.FAILED -> errors.add(originalName)
                }
            } catch (e: Exception) {
                android.util.Log.w(TAG, "import failed for $uri: $e")
                errors.add(originalName)
            }
        }
        android.util.Log.d(TAG, "import finished, imported=$imported rejected=$rejected errors=${errors.size}")
        return ImportResult(imported, rejected, errors)
    }

    /**
     * 把内存字节按哈希去重后入库（LAN 推送/接收复用）。
     * 隐写 GIF 还原、可解码校验、大小/分辨率上限均在此处执行，与桌面端一致。
     */
    fun importBytes(
        context: Context,
        bytes: ByteArray,
        originalName: String,
        srcExt: String? = null
    ): ImportOutcome {
        val db = MemeDb.get(context)
        val cacheDir = StoragePaths.cacheDir(context)
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

        if (payload.size > MAX_BYTES) {
            android.util.Log.w(TAG, "拒绝导入超限文件（大小 ${payload.size} > ${MAX_BYTES / 1024 / 1024}MB）")
            return ImportOutcome.OVER_LIMIT
        }

        // 先校验内容为合法可解码图片（宽高 > 0）与分辨率上限，通过后才落盘，杜绝孤儿缓存文件
        val dims = decodeBounds(payload)
        if (dims.first <= 0 || dims.second <= 0) {
            android.util.Log.w(TAG, "拒绝导入非图片内容（宽高 $dims, ext=$realExt）")
            return ImportOutcome.INVALID
        }
        if (maxOf(dims.first, dims.second) > MAX_PX) {
            android.util.Log.w(TAG, "拒绝导入超限文件（分辨率 ${dims.first}x${dims.second} > ${MAX_PX}）")
            return ImportOutcome.OVER_LIMIT
        }

        val fhash = FileUtils.sha256(java.io.ByteArrayInputStream(payload))
        if (db.getByHash(fhash) != null) return ImportOutcome.DUPLICATE
        val mime = "image/${realExt.substring(1)}"
        val dst = cacheDir.createFile("${fhash.substring(0, 16)}$realExt", mime)
        dst.writeBytes(payload)
        db.addMeme(
            filename = dst.name,
            fileHash = fhash,
            width = dims.first,
            height = dims.second,
            fileSize = dst.length,
            mimeType = mime,
            originalName = originalName.substringBeforeLast('.'),
            fromStego = fromStego
        )
        android.util.Log.d(TAG, "imported ${dst.name}")
        return ImportOutcome.IMPORTED
    }

    /** 用 BitmapFactory 只读宽高，不真正解码，判断是否为可解码图片 */
    private fun decodeBounds(bytes: ByteArray): Pair<Int, Int> {
        return try {
            val opts = android.graphics.BitmapFactory.Options()
            opts.inJustDecodeBounds = true
            android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
            Pair(opts.outWidth, opts.outHeight)
        } catch (e: Exception) {
            Pair(0, 0)
        }
    }

    /** LAN 拉取前的文件内容合法性检查：魔数可识别 + 可解码出有效尺寸 */
    fun isValidImageContent(bytes: ByteArray): Boolean {
        if (detectExt(bytes) == null) return false
        val dims = decodeBounds(bytes)
        return dims.first > 0 && dims.second > 0
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