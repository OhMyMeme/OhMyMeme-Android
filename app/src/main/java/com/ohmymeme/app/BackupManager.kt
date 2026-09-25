package com.ohmymeme.app

import android.content.Context
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * ZIP 备份/恢复：memes.db + config.json + cache/ + thumbnails/ 单文件打包。
 * 恢复先完整解包校验再落盘（DB 先 close 后替换），失败不动现有数据。
 */
object BackupManager {

    private const val TAG = "OhMyMeme/Backup"

    private const val ENTRY_META = "ohmymeme-backup.json"
    private const val ENTRY_DB = "memes.db"
    private const val ENTRY_CONFIG = "config.json"
    private const val ENTRY_CACHE = "cache/"
    private const val ENTRY_THUMB = "thumbnails/"

    class Stats(var files: Int = 0)

    fun backup(context: Context, out: OutputStream): Stats {
        MemeDb.get(context).checkpoint()
        val stats = Stats()
        ZipOutputStream(out.buffered()).use { zip ->
            zip.putNextEntry(ZipEntry(ENTRY_META))
            zip.write("""{"app":"ohmymeme","format":1}""".toByteArray(Charsets.UTF_8))
            zip.closeEntry()

            zip.putNextEntry(ZipEntry(ENTRY_DB))
            StoragePaths.dbPath(context).inputStream().use { it.copyTo(zip) }
            zip.closeEntry()
            stats.files++

            val cfg = StoragePaths.configFile(context)
            if (cfg.isFile) {
                zip.putNextEntry(ZipEntry(ENTRY_CONFIG))
                cfg.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
                stats.files++
            }

            StoragePaths.cacheDir(context).listFilesRecursive().forEach { f ->
                zip.putNextEntry(ZipEntry(ENTRY_CACHE + f.name))
                f.openInputStream().use { it.copyTo(zip) }
                zip.closeEntry()
                stats.files++
            }
            StoragePaths.thumbnailDir(context).listFiles().forEach { f ->
                if (f.isDirectory) return@forEach
                zip.putNextEntry(ZipEntry(ENTRY_THUMB + f.name))
                f.openInputStream().use { it.copyTo(zip) }
                zip.closeEntry()
                stats.files++
            }
        }
        return stats
    }

    fun restore(context: Context, src: File): Stats {
        val stats = Stats()
        val staging = File(context.cacheDir, "restore_staging_${System.nanoTime()}")
        try {
            staging.mkdirs()
            val dbStaged = File(staging, ENTRY_DB)
            val cfgStaged = File(staging, ENTRY_CONFIG)
            val cacheStaged = File(staging, "cache")
            val thumbStaged = File(staging, "thumbnails")
            var hasDb = false
            ZipInputStream(src.inputStream().buffered()).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    val name = entry.name
                    if (name.contains("..") || name.startsWith("/")) {
                        throw IOException("unsafe zip entry: $name")
                    }
                    when {
                        entry.isDirectory -> {}
                        name == ENTRY_DB -> {
                            dbStaged.outputStream().use { zip.copyTo(it) }
                            hasDb = true
                            stats.files++
                        }
                        name == ENTRY_CONFIG -> {
                            cfgStaged.outputStream().use { zip.copyTo(it) }
                            stats.files++
                        }
                        name.startsWith(ENTRY_CACHE) && name.length > ENTRY_CACHE.length -> {
                            extractTo(zip, File(cacheStaged, name.substring(ENTRY_CACHE.length)))
                            stats.files++
                        }
                        name.startsWith(ENTRY_THUMB) && name.length > ENTRY_THUMB.length -> {
                            extractTo(zip, File(thumbStaged, name.substring(ENTRY_THUMB.length)))
                            stats.files++
                        }
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
            if (!hasDb) throw IOException("zip missing memes.db")

            // 校验通过后才动现有数据：关库 -> 换 DB -> 换配置 -> 换媒体文件
            MemeDb.close()
            val dbFile = StoragePaths.dbPath(context)
            File(dbFile.path + "-wal").delete()
            File(dbFile.path + "-shm").delete()
            dbStaged.copyTo(dbFile, overwrite = true)

            if (cfgStaged.isFile) {
                cfgStaged.copyTo(StoragePaths.configFile(context), overwrite = true)
                ConfigStore.invalidate()
            }
            replaceStorDir(StoragePaths.cacheDir(context), cacheStaged)
            replaceStorDir(StoragePaths.thumbnailDir(context), thumbStaged)
            return stats
        } finally {
            staging.deleteRecursively()
        }
    }

    private fun extractTo(zip: ZipInputStream, dest: File) {
        dest.parentFile?.mkdirs()
        dest.outputStream().use { zip.copyTo(it) }
    }

    /** 清空目标目录后逐文件写入（真实路径与 SAF 双模式） */
    private fun replaceStorDir(target: StorFile, src: File) {
        target.listFiles().forEach { deleteStor(it) }
        src.listFiles()?.forEach { f ->
            if (f.isFile) {
                target.createFile(f.name, mimeOf(f.name)).writeFrom(f)
            }
        }
    }

    private fun mimeOf(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        else -> "application/octet-stream"
    }

    private fun deleteStor(f: StorFile) {
        if (f.isDirectory) f.listFiles().forEach { deleteStor(it) }
        f.delete()
    }
}
