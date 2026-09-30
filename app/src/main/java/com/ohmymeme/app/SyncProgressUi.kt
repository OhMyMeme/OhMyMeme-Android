package com.ohmymeme.app

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog

/**
 * 云端/局域网传输共享进度对话框（样式对齐桌面端 SyncOverlay.vue）：
 * 标题 + 当前文件 + 8dp 进度条 + 百分比与速度并排 + 后台运行按钮。
 * 百分比按字节制（bytes_done/bytes_total 封顶 99），对齐桌面端 get_sync_progress。
 */
class SyncProgressDialog private constructor(
    private val dialog: AlertDialog,
    val progress: CloudSync.SyncProgress
) {
    var inBackground = false
        private set

    fun dismiss() {
        try {
            if (dialog.isShowing) dialog.dismiss()
        } catch (e: Exception) {
            // ignore
        }
    }

    companion object {

        fun show(activity: Activity, title: String): SyncProgressDialog {
            val view = activity.layoutInflater.inflate(R.layout.dialog_sync_progress, null)
            view.findViewById<TextView>(R.id.sync_progress_title).text = title
            val bar = view.findViewById<ProgressBar>(R.id.sync_progress_bar)
            val pct = view.findViewById<TextView>(R.id.sync_progress_pct)
            val speed = view.findViewById<TextView>(R.id.sync_progress_speed)
            val file = view.findViewById<TextView>(R.id.sync_progress_file)
            val dialog = AlertDialog.Builder(activity)
                .setView(view)
                .setCancelable(false)
                .create()
            dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            val controller = SyncProgressDialog(dialog, CloudSync.SyncProgress())
            view.findViewById<TextView>(R.id.btn_sync_bg).setOnClickListener {
                controller.inBackground = true
                controller.dismiss()
            }
            controller.progress.onProgress = { p ->
                activity.runOnUiThread {
                    if (controller.inBackground || !dialog.isShowing) return@runOnUiThread
                    val percent = percentOf(p)
                    bar.progress = percent
                    pct.text = "$percent%"
                    speed.text = formatSpeed(p.bytesDone(), p.startTime)
                    file.text = p.currentFile
                }
            }
            dialog.show()
            return controller
        }

        /** 桌面端同款字节制百分比：bytes_done/bytes_total 封顶 99，总量未知回退文件数 */
        fun percentOf(p: CloudSync.SyncProgress): Int {
            if (p.bytesTotal > 0) {
                return (p.bytesDone() * 100 / p.bytesTotal).coerceAtMost(99L).toInt()
            }
            if (p.filesTotal > 0) {
                return (p.done() * 100 / p.filesTotal).coerceAtMost(99)
            }
            return 0
        }

        fun formatSpeed(bytesDone: Long, startTime: Long): String {
            val elapsedSec = (System.currentTimeMillis() - startTime) / 1000.0
            if (elapsedSec <= 0.0) return "0 KB/s"
            val bytesPerSec = bytesDone / elapsedSec
            return if (bytesPerSec >= 1024.0 * 1024.0) {
                String.format("%.1f MB/s", bytesPerSec / 1024.0 / 1024.0)
            } else {
                String.format("%.0f KB/s", bytesPerSec / 1024.0)
            }
        }

        /** 完成弹窗（对齐桌面端 sync-done-overlay） */
        fun showDone(activity: Activity, title: String, detail: String) {
            val view = activity.layoutInflater.inflate(R.layout.dialog_sync_done, null)
            view.findViewById<TextView>(R.id.sync_done_title).text = title
            view.findViewById<TextView>(R.id.sync_done_detail).text = detail
            val dialog = AlertDialog.Builder(activity)
                .setView(view)
                .setCancelable(false)
                .create()
            dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            view.findViewById<TextView>(R.id.btn_sync_done_close).setOnClickListener { dialog.dismiss() }
            dialog.show()
        }
    }
}
