package com.ohmymeme.app

import android.content.pm.PackageManager
import rikka.shizuku.Shizuku

/**
 * Shizuku 权限三态与远程 shell 执行通道（读取手机 QQ 缓存用，参照桌面端 adb 拉取逻辑）。
 * 进程输出：stdout 为数据通道，stderr 后台线程排空防管道写满死锁。
 */
object ShizukuBridge {

    private const val TAG = "OhMyMeme/ShizukuBridge"

    const val PERMISSION_REQUEST_CODE = 0x5351

    data class ExecResult(val code: Int, val out: String)

    /** Shizuku 服务（binder）是否可用 */
    fun available(): Boolean = try {
        Shizuku.pingBinder()
    } catch (e: Throwable) {
        false
    }

    /** 是否已授予本应用 Shizuku 权限 */
    fun hasPermission(): Boolean = try {
        available() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (e: Throwable) {
        false
    }

    /** 发起授权请求，结果经 Shizuku.OnRequestPermissionResultListener 回调 */
    fun requestPermission(requestCode: Int) {
        Shizuku.requestPermission(requestCode)
    }

    /** 执行 sh -c 命令，返回退出码与 stdout 文本；Shizuku 不可用时返回 null */
    fun exec(command: String): ExecResult? {
        val process = start(arrayOf("sh", "-c", command)) ?: return null
        return try {
            val drain = drainStderr(process)
            val out = process.inputStream.bufferedReader().use { it.readText() }
            val code = process.waitFor()
            joinDrain(drain)
            ExecResult(code, out)
        } catch (e: Throwable) {
            android.util.Log.w(TAG, "exec failed: $e")
            null
        } finally {
            destroy(process)
        }
    }

    /** 读取远程文件字节；文件不存在/无权限/进程失败返回 null */
    fun readFile(path: String): ByteArray? {
        val process = start(arrayOf("cat", path)) ?: return null
        return try {
            val drain = drainStderr(process)
            val bytes = process.inputStream.use { it.readBytes() }
            val code = process.waitFor()
            joinDrain(drain)
            if (code != 0) null else bytes
        } catch (e: Throwable) {
            android.util.Log.w(TAG, "readFile $path failed: $e")
            null
        } finally {
            destroy(process)
        }
    }

    /** newProcess 在 13.x 中为 private（规划 API 14 移除），经反射调用；不可用返回 null */
    private fun start(argv: Array<String>): Process? {
        if (!hasPermission()) return null
        return try {
            val method = Shizuku::class.java.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java
            )
            method.isAccessible = true
            method.invoke(null, argv, null, null) as? Process
        } catch (e: Throwable) {
            android.util.Log.w(TAG, "newProcess failed: $e")
            null
        }
    }

    /** 后台线程排空 stderr，避免子进程写满 stderr 管道后阻塞退出 */
    private fun drainStderr(process: Process): Thread {
        val thread = Thread {
            try {
                process.errorStream.use { stream ->
                    val buffer = ByteArray(8192)
                    while (stream.read(buffer) != -1) {
                        // discard
                    }
                }
            } catch (e: Throwable) {
                // ignore
            }
        }
        thread.isDaemon = true
        thread.start()
        return thread
    }

    private fun joinDrain(thread: Thread) {
        try {
            thread.join(2000)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private fun destroy(process: Process) {
        try {
            process.destroy()
        } catch (e: Throwable) {
            // ignore
        }
    }
}
