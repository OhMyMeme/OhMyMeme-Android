package com.ohmymeme.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.os.Build
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * 复制/分享前处理：对应桌面端 clipboard_util.py convert_image_mode_1/2/3。
 * - mode 1：静态图超限时缩放到 copy_resize_max 并存为 WebP（q90）；
 *   开启 copy_avoid_webp 时输出 JPG（不透明）/ PNG（带透明）
 * - mode 2：静态图超限时转为普通 GIF（256 色）
 * - mode 3：静态图超限时转为隐写 GIF（基座 GIF + STG3 原图数据，可无损还原）
 * - copy_avoid_webp 兜底（对齐桌面 convert_avoid_webp）：产物/原图为 WebP 时，
 *   静态转 JPG（原分辨率，透明合成白底）、动画转 GIF（最长边 ≤ copy_resize_max）
 * 动图（avoid 关闭时）/ 未超限 / 处理失败均返回 null，调用方回退原图直发。
 */
object MemeCopyProcessor {

    private const val TAG = "OhMyMeme/MemeCopy"
    private const val JPG_QUALITY = 90

    class Result(val file: File, val mimeType: String)

    fun process(context: Context, stor: StorFile): Result? {
        val cfg = ConfigStore.get(context)
        val mode = cfg.optInt("copy_resize_mode", 1)
        val avoid = cfg.optBoolean("copy_avoid_webp", false)
        val maxSide = cfg.optInt("copy_resize_max", 200)
        if (mode == 0) return if (avoid) avoidWebp(context, stor, maxSide) else null
        if (FileUtils.isAnimatedFile(stor)) {
            return if (avoid) avoidWebp(context, stor, maxSide) else null
        }
        val bounds = BitmapFactory.Options()
        bounds.inJustDecodeBounds = true
        stor.openInputStream().use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        if (maxOf(bounds.outWidth, bounds.outHeight) <= maxSide) {
            return if (avoid) avoidWebp(context, stor, maxSide) else null
        }
        return when (mode) {
            1 -> toResized(context, stor, bounds.outWidth, bounds.outHeight, maxSide, avoid)
            2 -> toGif(context, stor, bounds.outWidth, bounds.outHeight)
            3 -> toStegoGif(context, stor, bounds.outWidth, bounds.outHeight)
            else -> null
        }
    }

    /** WebP 兜底：动图转 GIF（等比缩到 maxSide），静态转 JPG（原分辨率）；非 WebP/失败返回 null */
    private fun avoidWebp(context: Context, stor: StorFile, maxSide: Int): Result? {
        if (!isWebp(stor)) return null
        return try {
            if (FileUtils.isAnimatedFile(stor)) {
                animatedWebpToGif(context, stor, maxSide)
            } else {
                staticWebpToJpg(context, stor)
            }
        } catch (e: Exception) {
            android.util.Log.w(TAG, "avoidWebp failed: $e")
            null
        }
    }

    private fun isWebp(stor: StorFile): Boolean {
        val head = ByteArray(12)
        val n = stor.openInputStream().use { it.read(head) }
        if (n < 12) return false
        return String(head, 0, 4, Charsets.ISO_8859_1) == "RIFF" &&
            String(head, 8, 4, Charsets.ISO_8859_1) == "WEBP"
    }

    /** mode 1：超限静态图缩放；avoid 开启输出 JPG（不透明）/ PNG（带透明），否则 WebP(q90) */
    private fun toResized(
        context: Context,
        stor: StorFile,
        w: Int,
        h: Int,
        maxSide: Int,
        avoid: Boolean
    ): Result? {
        return try {
            var sample = 1
            while (w / sample > maxSide * 4 || h / sample > maxSide * 4) sample *= 2
            val src = decode(stor, sample) ?: return null
            val ratio = maxSide / maxOf(src.width, src.height).toFloat()
            val nw = maxOf(1, (src.width * ratio).toInt())
            val nh = maxOf(1, (src.height * ratio).toInt())
            val scaled = Bitmap.createScaledBitmap(src, nw, nh, true)
            if (scaled !== src) src.recycle()
            val out: File
            val mime: String
            val format: Bitmap.CompressFormat
            when {
                avoid && scaled.hasAlpha() -> {
                    out = File(context.cacheDir, "copy_${System.nanoTime()}.png")
                    format = Bitmap.CompressFormat.PNG
                    mime = "image/png"
                }
                avoid -> {
                    out = File(context.cacheDir, "copy_${System.nanoTime()}.jpg")
                    format = Bitmap.CompressFormat.JPEG
                    mime = "image/jpeg"
                }
                else -> {
                    out = File(context.cacheDir, "copy_${System.nanoTime()}.webp")
                    format = Bitmap.CompressFormat.WEBP
                    mime = "image/webp"
                }
            }
            val target = if (avoid && format == Bitmap.CompressFormat.JPEG) {
                flattenToWhite(scaled).also { if (it !== scaled) scaled.recycle() }
            } else scaled
            out.outputStream().use { target.compress(format, JPG_QUALITY, it) }
            target.recycle()
            Result(out, mime)
        } catch (e: Exception) {
            android.util.Log.w(TAG, "toResized failed: $e")
            null
        }
    }

    /** 静态 WebP → JPG（原分辨率，透明合成白底）；对应桌面 _static_webp_to_jpg */
    private fun staticWebpToJpg(context: Context, stor: StorFile): Result? {
        return try {
            val bmp = decodeFull(stor) ?: return null
            val flat = flattenToWhite(bmp)
            if (flat !== bmp) bmp.recycle()
            val out = File(context.cacheDir, "copy_${System.nanoTime()}.jpg")
            out.outputStream().use { flat.compress(Bitmap.CompressFormat.JPEG, JPG_QUALITY, it) }
            flat.recycle()
            Result(out, "image/jpeg")
        } catch (e: Exception) {
            android.util.Log.w(TAG, "staticWebpToJpg failed: $e")
            null
        }
    }

    /** 动画 WebP → GIF：逐帧重封解码后按混合/处置语义合成；对应桌面 _animated_webp_to_gif */
    private fun animatedWebpToGif(context: Context, stor: StorFile, maxSide: Int): Result? {
        val anim = WebpAnim.parse(stor.readBytes()) ?: return null
        val frames = anim.frames
        if (frames.isEmpty()) return null
        val cw = anim.width
        val ch = anim.height
        if (cw <= 0 || ch <= 0 || cw.toLong() * ch > 32_000_000L) return null
        val scale = if (maxSide > 0 && maxOf(cw, ch) > maxSide) {
            maxSide.toFloat() / maxOf(cw, ch)
        } else 1f
        val tw = maxOf(1, (cw * scale).toInt())
        val th = maxOf(1, (ch * scale).toInt())
        val canvas = Bitmap.createBitmap(tw, th, Bitmap.Config.ARGB_8888)
        if (Color.alpha(anim.background) != 0) canvas.eraseColor(anim.background)
        else canvas.eraseColor(Color.TRANSPARENT)
        val canvasCanvas = Canvas(canvas)
        val clearPaint = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR) }
        val drawPaint = Paint(Paint.FILTER_BITMAP_FLAG)
        val outFrames = ArrayList<ByteArray>(frames.size)
        val durations = ArrayList<Int>(frames.size)
        var pendingClear: Rect? = null
        try {
            for (f in frames) {
                pendingClear?.let { canvasCanvas.drawRect(it, clearPaint) }
                val fbmp = BitmapFactory.decodeByteArray(WebpAnim.wrapFrame(f), 0, 0)
                    ?: return null
                val rect = Rect(
                    (f.x * scale + 0.5f).toInt(),
                    (f.y * scale + 0.5f).toInt(),
                    ((f.x + f.width) * scale + 0.5f).toInt().coerceAtMost(tw),
                    ((f.y + f.height) * scale + 0.5f).toInt().coerceAtMost(th)
                )
                if (f.noBlend) canvasCanvas.drawRect(rect, clearPaint)
                canvasCanvas.drawBitmap(fbmp, null, rect, drawPaint)
                fbmp.recycle()
                val px = IntArray(tw * th)
                canvas.getPixels(px, 0, tw, 0, 0, tw, th)
                outFrames.add(argbToRgba(px))
                durations.add(f.durationMs)
                pendingClear = if (f.disposeBg) rect else null
            }
        } finally {
            canvas.recycle()
        }
        val gif = GifEncoder.encodeAnimated(outFrames, durations, tw, th)
        val out = File(context.cacheDir, "copy_${System.nanoTime()}.gif")
        out.writeBytes(gif)
        return Result(out, "image/gif")
    }

    /** ARGB int 像素 → RGBA 字节（a<128 的像素保留 alpha 交由编码器作透明索引） */
    private fun argbToRgba(pixels: IntArray): ByteArray {
        val out = ByteArray(pixels.size * 4)
        for (i in pixels.indices) {
            val p = pixels[i]
            val b = i * 4
            out[b] = ((p ushr 16) and 0xFF).toByte()
            out[b + 1] = ((p ushr 8) and 0xFF).toByte()
            out[b + 2] = (p and 0xFF).toByte()
            out[b + 3] = (p ushr 24 and 0xFF).toByte()
        }
        return out
    }

    /** 透明像素合成白底（对应桌面 _flatten_to_rgb），非 ARGB/无 alpha 原样返回 */
    private fun flattenToWhite(bmp: Bitmap): Bitmap {
        if (!bmp.hasAlpha()) return bmp
        val flat = Bitmap.createBitmap(bmp.width, bmp.height, Bitmap.Config.ARGB_8888)
        val c = Canvas(flat)
        c.drawColor(Color.WHITE)
        c.drawBitmap(bmp, 0f, 0f, null)
        return flat
    }

    private fun toGif(context: Context, stor: StorFile, w: Int, h: Int): Result? {
        return try {
            val bmp = decodeFull(stor) ?: return null
            val rgba = bitmapToRgba(bmp)
            bmp.recycle()
            val gif = GifEncoder.encode(rgba, w, h)
            val out = File(context.cacheDir, "copy_${System.nanoTime()}.gif")
            out.writeBytes(gif)
            Result(out, "image/gif")
        } catch (e: Exception) {
            android.util.Log.w(TAG, "toGif failed: $e")
            null
        }
    }

    private fun toStegoGif(context: Context, stor: StorFile, w: Int, h: Int): Result? {
        return try {
            val bmp = decodeFull(stor) ?: return null
            val rgba = bitmapToRgba(bmp)
            val hasAlpha = bmp.hasAlpha()
            bmp.recycle()
            val n = w * h
            val kind: String
            val origPixels: ByteArray
            if (hasAlpha) {
                kind = "RGBA"
                origPixels = rgba
            } else {
                var isGray = true
                var i = 0
                while (i < n) {
                    val r = rgba[i * 4].toInt() and 0xFF
                    if (r != (rgba[i * 4 + 1].toInt() and 0xFF) || r != (rgba[i * 4 + 2].toInt() and 0xFF)) {
                        isGray = false
                        break
                    }
                    i++
                }
                if (isGray) {
                    kind = "L"
                    origPixels = ByteArray(n)
                    for (j in 0 until n) origPixels[j] = rgba[j * 4]
                } else {
                    kind = "RGB"
                    origPixels = ByteArray(n * 3)
                    for (j in 0 until n) {
                        origPixels[j * 3] = rgba[j * 4]
                        origPixels[j * 3 + 1] = rgba[j * 4 + 1]
                        origPixels[j * 3 + 2] = rgba[j * 4 + 2]
                    }
                }
            }
            val baseGif = GifEncoder.encode(rgba, w, h)
            val stego = GifStego.encode(
                baseGif, stor.readBytes(), stor.name.substringAfterLast('.', ""),
                origPixels, kind, w, h, ::encodeLosslessWebp
            )
            val out = File(context.cacheDir, "copy_${System.nanoTime()}.gif")
            out.writeBytes(stego)
            Result(out, "image/gif")
        } catch (e: Exception) {
            android.util.Log.w(TAG, "toStegoGif failed: $e")
            null
        }
    }

    /** 无损 WebP 编码（WEBP_LOSSLESS，API 30+）；低版本返回 null 时跳过 WebP 候选 */
    private fun encodeLosslessWebp(rgb: ByteArray, w: Int, h: Int): ByteArray? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return try {
            val n = w * h
            val argb = IntArray(n)
            for (i in 0 until n) {
                argb[i] = (0xFF shl 24) or
                    ((rgb[i * 3].toInt() and 0xFF) shl 16) or
                    ((rgb[i * 3 + 1].toInt() and 0xFF) shl 8) or
                    (rgb[i * 3 + 2].toInt() and 0xFF)
            }
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            bmp.setPixels(argb, 0, w, 0, 0, w, h)
            val bos = ByteArrayOutputStream()
            val ok = bmp.compress(Bitmap.CompressFormat.WEBP_LOSSLESS, 100, bos)
            bmp.recycle()
            if (ok) bos.toByteArray() else null
        } catch (e: Exception) {
            android.util.Log.w(TAG, "encodeLosslessWebp failed: $e")
            null
        }
    }

    private fun decode(stor: StorFile, sample: Int): Bitmap? {
        val opts = BitmapFactory.Options()
        opts.inSampleSize = sample
        opts.inPreferredConfig = Bitmap.Config.ARGB_8888
        return stor.openInputStream().use { BitmapFactory.decodeStream(it, null, opts) }
    }

    private fun decodeFull(stor: StorFile): Bitmap? {
        val bounds = BitmapFactory.Options()
        bounds.inJustDecodeBounds = true
        stor.openInputStream().use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        if (bounds.outWidth.toLong() * bounds.outHeight > 32_000_000L) return null
        return decode(stor, 1)
    }

    /** ARGB_8888 → 未预乘 RGBA 字节（反预乘，还原文件真实 RGB，对齐 Pillow） */
    private fun bitmapToRgba(bmp: Bitmap): ByteArray {
        val w = bmp.width
        val h = bmp.height
        val n = w * h
        val pixels = IntArray(n)
        bmp.getPixels(pixels, 0, w, 0, 0, w, h)
        val out = ByteArray(n * 4)
        var j = 0
        for (p in pixels) {
            var a = p ushr 24 and 0xFF
            var r = p ushr 16 and 0xFF
            var g = p ushr 8 and 0xFF
            var b = p and 0xFF
            if (a in 1..254) {
                r = (r * 255 / a).coerceAtMost(255)
                g = (g * 255 / a).coerceAtMost(255)
                b = (b * 255 / a).coerceAtMost(255)
            }
            out[j] = r.toByte()
            out[j + 1] = g.toByte()
            out[j + 2] = b.toByte()
            out[j + 3] = a.toByte()
            j += 4
        }
        return out
    }
}
