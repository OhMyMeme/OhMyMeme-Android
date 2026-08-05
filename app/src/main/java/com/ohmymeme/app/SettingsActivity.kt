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
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.switchmaterial.SwitchMaterial

class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        setupTitle()
        setupBack()
        setupSpinners()
        setupVersion()
        loadConfig()
        setupButtons()
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
        findViewById<View>(R.id.btn_back).setOnClickListener { finish() }
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
        val developing = getString(R.string.developing)
        val buttons = listOf(
            findViewById<TextView>(R.id.btn_test_connection),
            findViewById<TextView>(R.id.btn_check_sync_status),
            findViewById<TextView>(R.id.btn_sync_push),
            findViewById<TextView>(R.id.btn_sync_pull),
            findViewById<TextView>(R.id.btn_danger_local),
            findViewById<TextView>(R.id.btn_danger_cloud),
            findViewById<TextView>(R.id.btn_export_logs)
        )
        buttons.forEach { it.setOnClickListener { toast(developing) } }
        findViewById<TextView>(R.id.btn_check_update).setOnClickListener { checkUpdate() }
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
                openBrowser(info.downloadUrl)
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
        toast(getString(R.string.config_saved))
    }

    private fun textOf(id: Int): String {
        return findViewById<EditText>(id).text?.toString() ?: ""
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}
