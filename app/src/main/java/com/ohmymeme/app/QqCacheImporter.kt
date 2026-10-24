package com.ohmymeme.app

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.util.Locale

/**
 * 手机 QQ 缓存扫描与导入/转存：经 Shizuku（手机获取自身缓存，对应桌面端 adb 拉取 QQ_Favorite）
 * 扫描候选根 → 多选后「导入」走 MemeImporter 全链路，「转存到…」经 SAF 写入用户所选目录。
 */
object QqCacheImporter {

    private const val TAG = "OhMyMeme/QqCacheImporter"

    private const val QQ_PKG = "com.tencent.mobileqq"

    /** 候选根后缀：收藏夹（桌面端同款）/ 聊天图片缓存（含 chatraw/chatimg/chatthumb）/ 表情缓存 */
    val ROOT_SUFFIXES = listOf(
        "/Android/data/$QQ_PKG/Tencent/QQ_Favorite",
        "/Android/data/$QQ_PKG/Tencent/chatpic",
        "/Android/data/$QQ_PKG/files/tencent/MicroMsg/.emotionsm"
    )

    /** 存储根候选：主存储 + /sdcard（回退）+ 外置卡（枚举 /storage） */
    @android.annotation.SuppressLint("SdCardPath")
    val STORAGE_BASES = listOf("/storage/emulated/0", "/sdcard")

    /** 扫描时跳过的非图片扩展名（无扩展名或未知扩展名保留，交魔数判定） */
    val SKIP_EXT = setOf(
        ".mp4", ".3gp", ".mov", ".avi", ".mkv", ".amr", ".mp3", ".m4a", ".aac",
        ".apk", ".zip", ".rar", ".7z", ".db", ".sqlite", ".json", ".xml", ".log",
        ".txt", ".pdf", ".doc", ".docx"
    )

    /** 转存单文件大小上限（整文件读入内存，防止超大文件 OOM） */
    const val EXPORT_MAX_BYTES = 64L * 1024 * 1024

    data class Entry(val path: String, val size: Long, val root: String)

    /** 扫描全部候选根，返回去重排序后的文件列表 */
    fun scan(): List<Entry> {
        val entries = mutableListOf<Entry>()
        for (root in existingRoots(storageBases())) {
            val result = ShizukuBridge.exec(listCommand(root)) ?: continue
            if (result.code != 0) {
                Log.w(TAG, "list $root rc=${result.code}")
                continue
            }
            entries += parseListOutput(result.out, root)
        }
        return entries.distinctBy { it.path }.sortedWith(compareBy({ it.root }, { it.path }))
    }

    /** 存储根：主存储 + /sdcard + /storage 下外置卷（在本进程内枚举，无需 Shizuku） */
    internal fun storageBases(): List<String> {
        val bases = linkedSetOf<String>()
        bases.addAll(STORAGE_BASES)
        try {
            File("/storage").listFiles()?.forEach { f ->
                if (f.isDirectory && !f.name.startsWith(".") &&
                    f.name != "emulated" && f.name != "self"
                ) {
                    bases.add("/storage/${f.name}")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "list /storage failed: $e")
        }
        return bases.toList()
    }

    /** 列出实际存在的候选根（readlink 归一化后去重，/sdcard 与主存储同源不重复） */
    internal fun rootsCommand(bases: List<String>): String {
        val candidates = bases.flatMap { b -> ROOT_SUFFIXES.map { b + it } }
        val list = candidates.joinToString(" ") { shellQuote(it) }
        return "for d in $list; do if [ -d \"\$d\" ]; then " +
            "readlink -f \"\$d\" 2>/dev/null || echo \"\$d\"; fi; done | sort -u"
    }

    internal fun existingRoots(bases: List<String>): List<String> {
        val result = ShizukuBridge.exec(rootsCommand(bases)) ?: return emptyList()
        if (result.code != 0) return emptyList()
        return result.out.lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("/") }
            .distinct()
            .toList()
    }

    /** find 全递归列文件，逐个 stat 输出「size<TAB>path」行 */
    internal fun listCommand(root: String): String =
        "find ${shellQuote(root)} -type f 2>/dev/null | " +
            "while IFS= read -r f; do " +
            "printf '%s\\t%s\\n' \"\$(stat -c %s \"\$f\" 2>/dev/null)\" \"\$f\"; done"

    /** 解析 listCommand 输出，跳过 stat 失败、相对路径与非图片扩展名 */
    internal fun parseListOutput(out: String, root: String): List<Entry> {
        val entries = mutableListOf<Entry>()
        for (line in out.lineSequence()) {
            if (line.isEmpty()) continue
            val tab = line.indexOf('\t')
            if (tab <= 0 || tab == line.length - 1) continue
            val size = line.substring(0, tab).toLongOrNull() ?: continue
            val path = line.substring(tab + 1)
            if (!path.startsWith("/") || isSkippedFile(path)) continue
            entries.add(Entry(path, size, root))
        }
        return entries
    }

    internal fun isSkippedFile(path: String): Boolean {
        val name = path.substringAfterLast('/')
        if (name == ".nomedia") return true
        val dot = name.lastIndexOf('.')
        if (dot <= 0) return false
        return name.substring(dot).lowercase() in SKIP_EXT
    }

    /** 目录标签（根相对父目录；文件直接位于根目录时显示根名），供树形选择界面左栏分组 */
    fun dirLabel(entry: Entry): String {
        val parent = entry.path.substringBeforeLast('/', "")
        val rel = when {
            parent.startsWith(entry.root + "/") -> parent.substring(entry.root.length + 1)
            parent == entry.root || parent.isEmpty() -> entry.root.substringAfterLast('/')
            else -> parent
        }
        return rel.ifEmpty { entry.root.substringAfterLast('/') }
    }

    /** 文件行标签：文件名 + 体积 */
    fun fileNameLabel(entry: Entry): String =
        "${entry.path.substringAfterLast('/')}（${formatSize(entry.size)}）"

    /** 弹窗条目：根相对路径 + 体积 */
    fun displayLabel(entry: Entry): String {
        val rel = if (entry.path.startsWith(entry.root + "/")) {
            entry.path.substring(entry.root.length + 1)
        } else {
            entry.path.substringAfterLast('/')
        }
        return "$rel（${formatSize(entry.size)}）"
    }

    internal fun formatSize(bytes: Long): String = when {
        bytes >= 1024L * 1024 -> String.format(Locale.ROOT, "%.1f MB", bytes / 1024.0 / 1024.0)
        bytes >= 1024 -> "${bytes / 1024} KB"
        else -> "$bytes B"
    }

    /** 导入所选文件到表情库（经 MemeImporter：去重/上限/魔数/隐写全链路），report 供进度条累计 */
    fun importSelected(
        context: Context,
        entries: List<Entry>,
        report: (bytes: Long, name: String) -> Unit
    ): MemeImporter.ImportResult {
        var imported = 0
        var rejected = 0
        val errors = mutableListOf<String>()
        for (entry in entries) {
            val name = entry.path.substringAfterLast('/')
            try {
                if (entry.size > MemeImporter.MAX_BYTES) {
                    rejected++
                    report(0, name)
                    continue
                }
                val bytes = ShizukuBridge.readFile(entry.path)
                if (bytes == null) {
                    errors.add(name)
                    report(entry.size, name)
                    continue
                }
                when (MemeImporter.importBytes(context, bytes, name)) {
                    MemeImporter.ImportOutcome.IMPORTED -> imported++
                    MemeImporter.ImportOutcome.DUPLICATE,
                    MemeImporter.ImportOutcome.OVER_LIMIT,
                    MemeImporter.ImportOutcome.INVALID -> rejected++
                    MemeImporter.ImportOutcome.FAILED -> errors.add(name)
                }
                report(bytes.size.toLong(), name)
            } catch (e: Exception) {
                Log.w(TAG, "import $name failed: $e")
                errors.add(name)
            }
        }
        Log.d(TAG, "import finished, imported=$imported rejected=$rejected errors=${errors.size}")
        return MemeImporter.ImportResult(imported, rejected, errors)
    }

    /** 转存所选文件到 SAF 目录（按魔数补正扩展名），返回 Pair<成功, 失败> */
    fun exportSelected(
        context: Context,
        treeUri: Uri,
        entries: List<Entry>,
        report: (bytes: Long, name: String) -> Unit
    ): Pair<Int, Int> {
        val tree = DocumentFile.fromTreeUri(context, treeUri)
        if (tree == null || !tree.isDirectory) return Pair(0, entries.size)
        var ok = 0
        var failed = 0
        for (entry in entries) {
            val name = entry.path.substringAfterLast('/')
            try {
                if (entry.size <= 0 || entry.size > EXPORT_MAX_BYTES) {
                    failed++
                    report(0, name)
                    continue
                }
                val bytes = ShizukuBridge.readFile(entry.path)
                if (bytes == null || bytes.isEmpty()) {
                    failed++
                    report(0, name)
                    continue
                }
                val outName = exportName(name, bytes)
                val outFile = tree.createFile(exportMime(outName), outName)
                val stream = outFile?.let { context.contentResolver.openOutputStream(it.uri) }
                if (stream == null) {
                    failed++
                    report(0, name)
                    continue
                }
                stream.use { it.write(bytes) }
                ok++
                report(bytes.size.toLong(), outName)
            } catch (e: Exception) {
                Log.w(TAG, "export $name failed: $e")
                failed++
            }
        }
        return Pair(ok, failed)
    }

    /** 转存文件名：魔数识别真实扩展名（QQ 存 .jpg 实为 png/webp），识别不出保留原扩展名 */
    internal fun exportName(originalName: String, bytes: ByteArray): String {
        val stem = originalName.substringBeforeLast('.', originalName)
        val magic = FileUtils.detectExt(bytes.take(16).toByteArray())
        val fallback = originalName.substringAfterLast('.', "")
        val ext = magic.trimStart('.').ifEmpty {
            if (fallback.isEmpty()) "" else fallback
        }
        return if (ext.isEmpty()) stem else "$stem.$ext"
    }

    internal fun exportMime(name: String): String =
        when (name.substringAfterLast('.', "").lowercase()) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "bmp" -> "image/bmp"
            else -> "application/octet-stream"
        }

    /** sh 单引号包裹（内部单引号转义），供候选根/文件名拼入命令 */
    internal fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\\''") + "'"
}
