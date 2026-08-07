package com.ohmymeme.app

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.Spannable
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.switchmaterial.SwitchMaterial
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.File

class SettingsActivity : AppCompatActivity() {

    private val TAG = "OhMyMeme/SettingsActivity"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        setupTitle()
        setupBack()
        setupSpinners()
        setupVersion()
        loadConfig()
        setupButtons()
        setupExportLogs()
        setupStorage()
    }

    private fun setupTitle() {
        val logo = findViewById<TextView>(R.id.tv_settings_logo)
        val spannable = SpannableString(getString(R.string.settings_title))
        spannable.setSpan(
            ForegroundColorSpan(getColor(R.color.accent)),
            4, 8, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        logo.text = spannable
    }

    private fun setupBack() {
        findViewById<View>(R.id.btn_back).setOnClickListener { finishWithResult() }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                finishWithResult()
            }
        })
    }

    private fun finishWithResult() {
        setResult(RESULT_OK)
        finish()
    }

    private fun setupSpinners() {
        val copyAdapter = ArrayAdapter.createFromResource(
            this, R.array.copy_mode_options, android.R.layout.simple_spinner_dropdown_item
        )
        findViewById<Spinner>(R.id.sp_copy_mode).let {
            it.adapter = copyAdapter
            it.setSelection(1)
        }

        val syncAdapter = ArrayAdapter.createFromResource(
            this, R.array.sync_type_options, android.R.layout.simple_spinner_dropdown_item
        )
        findViewById<Spinner>(R.id.sp_sync_type).apply {
            adapter = syncAdapter
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    toggleSyncType(position)
                }

                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
        }
    }

    private fun toggleSyncType(position: Int) {
        val selected = position - 1
        val groups = listOf(
            findViewById<View>(R.id.group_ftp),
            findViewById<View>(R.id.group_s3),
            findViewById<View>(R.id.group_r2),
            findViewById<View>(R.id.group_webdav)
        )
        groups.forEach { it.visibility = View.GONE }
        findViewById<View>(R.id.sync_options).visibility =
            if (selected >= 0) View.VISIBLE else View.GONE
        findViewById<View>(R.id.sync_buttons).visibility =
            if (selected >= 0) View.VISIBLE else View.GONE
        if (selected >= 0) groups[selected].visibility = View.VISIBLE
    }

    private val syncTypes = listOf("", "ftp", "s3", "r2", "webdav")

    private fun syncTypePosition(type: String): Int {
        val idx = syncTypes.indexOf(type)
        return if (idx >= 0) idx else 0
    }

    private fun setupVersion() {
        findViewById<TextView>(R.id.tv_version).text =
            getString(R.string.current_version, currentVersionName())
    }

    private fun currentVersionName(): String {
        val versionName = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0L)).versionName
            } else {
                @Suppress("DEPRECATION")
                packageManager.getPackageInfo(packageName, 0).versionName
            }
        } catch (e: Exception) {
            null
        }
        return versionName ?: "1.0"
    }

    private fun loadConfig() {
        val cfg = ConfigStore.get(this)
        findViewById<SwitchMaterial>(R.id.sw_gif).isChecked = cfg.optBoolean("auto_play_gif", true)
        findViewById<Spinner>(R.id.sp_copy_mode).setSelection(cfg.optInt("copy_resize_mode", 1))
        findViewById<Spinner>(R.id.sp_sync_type).setSelection(syncTypePosition(cfg.optString("sync_type", "")))
        findViewById<SwitchMaterial>(R.id.sw_sync_fetch).isChecked =
            cfg.optBoolean("sync_auto_fetch_index", false)
        findViewById<SwitchMaterial>(R.id.sw_sync_auto).isChecked =
            cfg.optBoolean("sync_auto_sync", false)
        findViewById<SwitchMaterial>(R.id.sw_delete_remote).isChecked =
            cfg.optBoolean("sync_delete_remote", false)
        findViewById<SwitchMaterial>(R.id.sw_remove_local).isChecked =
            cfg.optBoolean("sync_remove_local", false)
        findViewById<SwitchMaterial>(R.id.sw_hide_upload_warn).isChecked =
            cfg.optBoolean("sync_hide_upload_warning", false)
        findViewById<SwitchMaterial>(R.id.sw_show_up_progress).isChecked =
            cfg.optBoolean("show_upload_progress", true)
        findViewById<SwitchMaterial>(R.id.sw_show_up_done).isChecked =
            cfg.optBoolean("show_upload_done", true)
        findViewById<SwitchMaterial>(R.id.sw_show_dl_progress).isChecked =
            cfg.optBoolean("show_download_progress", true)
        findViewById<SwitchMaterial>(R.id.sw_show_dl_done).isChecked =
            cfg.optBoolean("show_download_done", true)
        setText(R.id.et_ftp_host, cfg.optString("ftp_host", ""))
        setText(R.id.et_ftp_port, cfg.optInt("ftp_port", 21).toString())
        setText(R.id.et_ftp_user, cfg.optString("ftp_user", ""))
        setText(R.id.et_ftp_pass, cfg.optString("ftp_password", ""))
        setText(R.id.et_ftp_path, cfg.optString("ftp_path", "/"))
        setText(R.id.et_s3_endpoint, cfg.optString("s3_endpoint", ""))
        setText(R.id.et_s3_region, cfg.optString("s3_region", ""))
        setText(R.id.et_s3_bucket, cfg.optString("s3_bucket", ""))
        setText(R.id.et_s3_access, cfg.optString("s3_access_key", ""))
        setText(R.id.et_s3_secret, cfg.optString("s3_secret_key", ""))
        setText(R.id.et_s3_path, cfg.optString("s3_path", ""))
        setText(R.id.et_r2_account, cfg.optString("r2_account_id", ""))
        setText(R.id.et_r2_access, cfg.optString("r2_access_key_id", ""))
        setText(R.id.et_r2_secret, cfg.optString("r2_secret_access_key", ""))
        setText(R.id.et_r2_bucket, cfg.optString("r2_bucket", ""))
        setText(R.id.et_r2_path, cfg.optString("r2_path", ""))
        setText(R.id.et_wd_url, cfg.optString("webdav_url", ""))
        setText(R.id.et_wd_user, cfg.optString("webdav_user", ""))
        setText(R.id.et_wd_pass, cfg.optString("webdav_password", ""))
        setText(R.id.et_wd_path, cfg.optString("webdav_path", ""))
    }

    private fun setText(id: Int, value: String) {
        findViewById<EditText>(id).setText(value)
    }

    private fun setupButtons() {
        findViewById<View>(R.id.btn_save).setOnClickListener { saveConfig() }
        findViewById<View>(R.id.btn_reset).setOnClickListener {
            ConfigStore.reset(this)
            ConfigStore.reload(this)
            loadConfig()
            toast(getString(R.string.config_reset))
        }
        findViewById<TextView>(R.id.btn_test_connection).setOnClickListener { runSync(R.string.sync_testing) { CloudSync.syncTest(this) } }
        findViewById<TextView>(R.id.btn_check_sync_status).setOnClickListener { runSync(R.string.sync_checking) { CloudSync.checkSyncStatus(this) } }
        findViewById<TextView>(R.id.btn_sync_push).setOnClickListener { runSync(R.string.sync_pushing) { CloudSync.push(this) } }
        findViewById<TextView>(R.id.btn_sync_pull).setOnClickListener { runSync(R.string.sync_pulling) { CloudSync.pull(this) } }
        findViewById<TextView>(R.id.btn_sync_orphans).setOnClickListener { confirmCleanupOrphans() }
        findViewById<TextView>(R.id.btn_danger_local).setOnClickListener { confirmDeleteLocal() }
        findViewById<TextView>(R.id.btn_danger_cloud).setOnClickListener { confirmDeleteCloud() }
        findViewById<TextView>(R.id.btn_check_update).setOnClickListener { checkUpdate() }
    }

    private val exportLogsLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
            if (uri != null) writeLogsTo(uri)
        }

    private val storageDirLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                result.data?.data?.let { onStorageDirPicked(it) }
            }
        }

    private fun setupStorage() {
        findViewById<TextView>(R.id.tv_storage_path).text =
            getString(R.string.storage_current, StoragePaths.dataDir(this).absolutePath)
        findViewById<TextView>(R.id.btn_change_storage).setOnClickListener {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
            storageDirLauncher.launch(intent)
        }
    }

    private fun onStorageDirPicked(uri: Uri) {
        val newDir = StoragePaths.resolveTreeUriPath(this, uri)
        if (newDir == null) {
            toast(getString(R.string.storage_pick_failed))
            return
        }
        val oldDir = StoragePaths.dataDir(this)
        if (oldDir.absolutePath == newDir.absolutePath) return
        android.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.storage_transfer_title))
            .setMessage(getString(R.string.storage_transfer_message))
            .setPositiveButton(getString(R.string.storage_transfer)) { _, _ ->
                moveLocalData(oldDir, newDir)
            }
            .setNegativeButton(getString(R.string.storage_no_transfer)) { _, _ ->
                applyNewStorageDir(newDir)
            }
            .show()
    }

    private fun moveLocalData(oldDir: File, newDir: File) {
        Thread {
            try {
                MemeDb.close()
                newDir.mkdirs()
                oldDir.listFiles()?.forEach { child ->
                    val target = File(newDir, child.name)
                    if (child.isDirectory) {
                        copyRecursive(child, target)
                    } else {
                        child.copyTo(target, overwrite = true)
                    }
                }
                runOnUiThread {
                    applyNewStorageDir(newDir)
                    toast(getString(R.string.storage_moved))
                }
            } catch (e: Exception) {
                runOnUiThread { toast(getString(R.string.storage_move_failed)) }
            }
        }.start()
    }

    private fun copyRecursive(src: File, dst: File) {
        if (src.isDirectory) {
            dst.mkdirs()
            src.listFiles()?.forEach { child -> copyRecursive(child, File(dst, child.name)) }
        } else {
            dst.parentFile?.mkdirs()
            src.copyTo(dst, overwrite = true)
        }
    }

    private fun applyNewStorageDir(dir: File) {
        StoragePaths.setDataDir(this, dir)
        ConfigStore.invalidate()
    }

    private fun setupExportLogs() {
        findViewById<TextView>(R.id.btn_export_logs).setOnClickListener {
            exportLogsLauncher.launch("OhMyMeme-logs.txt")
        }
    }

    private fun writeLogsTo(uri: Uri) {
        Thread {
            try {
                val sb = StringBuilder()
                val pid = android.os.Process.myPid()
                val proc = Runtime.getRuntime().exec(arrayOf("logcat", "-d", "--pid=$pid", "*:D"))
                BufferedReader(InputStreamReader(proc.inputStream)).useLines { lines ->
                    lines.forEach { sb.append(it).append('\n') }
                }
                val err = BufferedReader(InputStreamReader(proc.errorStream)).useLines { lines ->
                    lines.joinToString("\n")
                }
                proc.waitFor()
                val header = StringBuilder()
                header.append("OhMyMeme log export ").append(currentVersionName())
                    .append(" pid=").append(pid).append('\n')
                header.append(java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(java.util.Date())).append('\n')
                header.append("--- logcat ---\n")
                header.append(sb)
                if (err.isNotBlank()) {
                    header.append("--- logcat error ---\n").append(err).append('\n')
                }
                contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(header.toString().toByteArray(Charsets.UTF_8))
                } ?: run {
                    runOnUiThread { toast(getString(R.string.log_export_failed)) }
                    return@Thread
                }
                runOnUiThread { toast(getString(R.string.log_export_done)) }
            } catch (e: Exception) {
                runOnUiThread { toast(getString(R.string.log_export_failed)) }
            }
        }.start()
    }

    private fun runSync(progressRes: Int, block: () -> Any) {
        saveConfig()
        val btn = findViewById<TextView>(R.id.btn_sync_push)
        val original = btn.text.toString()
        btn.isEnabled = false
        btn.text = getString(progressRes)
        Thread {
            val result = try {
                block()
            } catch (e: Exception) {
                e.message ?: "sync failed"
            }
            runOnUiThread {
                btn.isEnabled = true
                btn.text = original
                when (result) {
                    is String -> toast(result)
                    is CloudSync.SyncResult -> toast(syncSummary(result))
                    else -> toast(result.toString())
                }
            }
        }.start()
    }

    private fun syncSummary(r: CloudSync.SyncResult): String {
        val parts = mutableListOf<String>()
        if (r.uploaded > 0) parts.add("上传 ${r.uploaded}")
        if (r.downloaded > 0) parts.add("下载 ${r.downloaded}")
        if (r.skipped > 0) parts.add("跳过 ${r.skipped}")
        if (r.deleted > 0) parts.add("删除 ${r.deleted}")
        if (r.removedLocal > 0) parts.add("移除本地 ${r.removedLocal}")
        if (r.errors > 0) parts.add("失败 ${r.errors}")
        val base = if (parts.isEmpty()) "同步完成" else "同步完成：" + parts.joinToString("，")
        if (r.failed.isNotEmpty()) {
            return base + "\n" + r.failed.take(5).joinToString("，")
        }
        return base
    }

    private fun confirmDeleteLocal() {
        android.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.danger_title))
            .setMessage(getString(R.string.danger_delete_local))
            .setPositiveButton(getString(R.string.ctx_delete)) { _, _ ->
                runSync(R.string.sync_testing) {
                    val n = CloudSync.deleteAllLocal(this)
                    "已删除本地 $n 个表情包"
                }
            }
            .setNegativeButton(getString(R.string.update_later), null)
            .show()
    }

    private fun confirmDeleteCloud() {
        android.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.danger_title))
            .setMessage(getString(R.string.danger_delete_cloud))
            .setPositiveButton(getString(R.string.ctx_delete)) { _, _ ->
                runSync(R.string.sync_testing) {
                    val (ok, msg) = CloudSync.deleteAllRemote(this)
                    if (ok) msg else "删除失败：$msg"
                }
            }
            .setNegativeButton(getString(R.string.update_later), null)
            .show()
    }

    private fun confirmCleanupOrphans() {
        android.app.AlertDialog.Builder(this)
            .setTitle(R.string.btn_sync_orphans)
            .setMessage(R.string.orphan_cleanup_confirm)
            .setPositiveButton(R.string.ok) { _, _ ->
                runSync(R.string.sync_testing) {
                    val (ok, msg) = CloudSync.cleanupRemoteOrphans(this, delete = true)
                    if (ok) msg else "清理失败：$msg"
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun checkUpdate() {
        val btn = findViewById<TextView>(R.id.btn_check_update)
        btn.isEnabled = false
        btn.text = getString(R.string.checking_update)
        Thread {
            val info = UpdateChecker.checkLatest(currentVersionName())
            runOnUiThread {
                btn.isEnabled = true
                btn.text = getString(R.string.btn_check_update)
                if (info.error.isNotEmpty()) {
                    toast(info.error)
                } else if (info.hasUpdate) {
                    showUpdateDialog(info)
                } else {
                    toast(getString(R.string.already_latest))
                }
            }
        }.start()
    }

    private fun showUpdateDialog(info: UpdateChecker.UpdateInfo) {
        android.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.update_available))
            .setMessage(getString(R.string.update_message, info.latest, currentVersionName()))
            .setPositiveButton(getString(R.string.update_download)) { _, _ ->
                openBrowser(UpdateChecker.mirrorDownloadUrl(info.downloadUrl))
            }
            .setNegativeButton(getString(R.string.update_later), null)
            .show()
    }

    private fun openBrowser(url: String) {
        if (url.isEmpty()) {
            toast(getString(R.string.update_no_url))
            return
        }
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: Exception) {
            toast(getString(R.string.update_no_url))
        }
    }

    private fun saveConfig() {
        ConfigStore.set(this, "auto_play_gif", findViewById<SwitchMaterial>(R.id.sw_gif).isChecked)
        ConfigStore.set(this, "copy_resize_mode", findViewById<Spinner>(R.id.sp_copy_mode).selectedItemPosition)
        ConfigStore.set(this, "sync_auto_fetch_index", findViewById<SwitchMaterial>(R.id.sw_sync_fetch).isChecked)
        ConfigStore.set(this, "sync_auto_sync", findViewById<SwitchMaterial>(R.id.sw_sync_auto).isChecked)
        ConfigStore.set(this, "sync_type", syncTypes[findViewById<Spinner>(R.id.sp_sync_type).selectedItemPosition])
        ConfigStore.set(this, "sync_delete_remote", findViewById<SwitchMaterial>(R.id.sw_delete_remote).isChecked)
        ConfigStore.set(this, "sync_remove_local", findViewById<SwitchMaterial>(R.id.sw_remove_local).isChecked)
        ConfigStore.set(this, "sync_hide_upload_warning", findViewById<SwitchMaterial>(R.id.sw_hide_upload_warn).isChecked)
        ConfigStore.set(this, "show_upload_progress", findViewById<SwitchMaterial>(R.id.sw_show_up_progress).isChecked)
        ConfigStore.set(this, "show_upload_done", findViewById<SwitchMaterial>(R.id.sw_show_up_done).isChecked)
        ConfigStore.set(this, "show_download_progress", findViewById<SwitchMaterial>(R.id.sw_show_dl_progress).isChecked)
        ConfigStore.set(this, "show_download_done", findViewById<SwitchMaterial>(R.id.sw_show_dl_done).isChecked)

        ConfigStore.set(this, "ftp_host", textOf(R.id.et_ftp_host))
        ConfigStore.set(this, "ftp_port", textOf(R.id.et_ftp_port).toIntOrNull() ?: 21)
        ConfigStore.set(this, "ftp_user", textOf(R.id.et_ftp_user))
        ConfigStore.set(this, "ftp_password", textOf(R.id.et_ftp_pass))
        ConfigStore.set(this, "ftp_path", textOf(R.id.et_ftp_path))
        ConfigStore.set(this, "s3_endpoint", textOf(R.id.et_s3_endpoint))
        ConfigStore.set(this, "s3_region", textOf(R.id.et_s3_region))
        ConfigStore.set(this, "s3_bucket", textOf(R.id.et_s3_bucket))
        ConfigStore.set(this, "s3_access_key", textOf(R.id.et_s3_access))
        ConfigStore.set(this, "s3_secret_key", textOf(R.id.et_s3_secret))
        ConfigStore.set(this, "s3_path", textOf(R.id.et_s3_path))
        ConfigStore.set(this, "r2_account_id", textOf(R.id.et_r2_account))
        ConfigStore.set(this, "r2_access_key_id", textOf(R.id.et_r2_access))
        ConfigStore.set(this, "r2_secret_access_key", textOf(R.id.et_r2_secret))
        ConfigStore.set(this, "r2_bucket", textOf(R.id.et_r2_bucket))
        ConfigStore.set(this, "r2_path", textOf(R.id.et_r2_path))
        ConfigStore.set(this, "webdav_url", textOf(R.id.et_wd_url))
        ConfigStore.set(this, "webdav_user", textOf(R.id.et_wd_user))
        ConfigStore.set(this, "webdav_password", textOf(R.id.et_wd_pass))
        ConfigStore.set(this, "webdav_path", textOf(R.id.et_wd_path))

        ConfigStore.save(this)
        ConfigStore.reload(this)
        android.util.Log.d(TAG, "saveConfig done")
        toast(getString(R.string.config_saved))
    }

    private fun textOf(id: Int): String {
        return findViewById<EditText>(id).text?.toString() ?: ""
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}
