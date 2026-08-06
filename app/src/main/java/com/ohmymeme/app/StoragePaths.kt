package com.ohmymeme.app

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import java.io.File

object StoragePaths {

    private const val PREFS = "ohmymeme_prefs"
    private const val KEY_DATA_DIR = "data_dir"
    private const val KEY_SETUP_DONE = "setup_done"

    fun isFirstRun(context: Context): Boolean {
        return !context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_SETUP_DONE, false)
    }

    fun markSetupDone(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_SETUP_DONE, true).apply()
    }

    /** config 所在根目录：Android/data/com.ohmymeme.app/（应用专属数据根） */
    fun configRoot(context: Context): File {
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        return base.parentFile ?: base
    }

    /** localdata 目录：默认 Android/data/com.ohmymeme.app/files/，可被用户自定义覆盖 */
    fun dataDir(context: Context): File {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val saved = prefs.getString(KEY_DATA_DIR, null)
        val dir = if (saved != null && saved.isNotEmpty()) {
            File(saved)
        } else {
            context.getExternalFilesDir(null) ?: context.filesDir
        }
        dir.mkdirs()
        return dir
    }

    fun setDataDir(context: Context, dir: File) {
        dir.mkdirs()
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_DATA_DIR, dir.absolutePath).apply()
    }

    /**
     * 将 SAF 树 URI 解析为真实文件路径；无法解析时返回 null（回退默认位置）。
     * 支持 internal storage（primary:xxx）与 Downloads（home:xxx）。
     */
    fun resolveTreeUriPath(context: Context, uri: Uri): File? {
        return try {
            val docId = DocumentsContract.getTreeDocumentId(uri)
            val idx = docId.indexOf(':')
            val root = if (idx >= 0) docId.substring(0, idx) else docId
            val rel = if (idx >= 0) docId.substring(idx + 1) else ""
            val base = when (root) {
                "primary" -> Environment.getExternalStorageDirectory()
                "home" -> Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                else -> return null
            }
            if (rel.isEmpty()) base else File(base, rel)
        } catch (e: Exception) {
            null
        }
    }

    fun configFile(context: Context): File {
        val f = File(configRoot(context), "config.json")
        f.parentFile?.mkdirs()
        return f
    }

    fun cacheDir(context: Context): File {
        val d = File(dataDir(context), "cache")
        d.mkdirs()
        return d
    }

    fun thumbnailDir(context: Context): File {
        val d = File(dataDir(context), "thumbnails")
        d.mkdirs()
        return d
    }

    fun dbPath(context: Context): File {
        return File(dataDir(context), "memes.db")
    }
}
