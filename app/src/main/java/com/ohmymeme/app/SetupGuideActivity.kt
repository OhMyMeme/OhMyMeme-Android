package com.ohmymeme.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.switchmaterial.SwitchMaterial

/**
 * 首启设置向导（裁剪版 5 步）：欢迎 → 存储位置 → 复制模式 → 云同步（可跳过）→ 完成。
 * 全部选项保存在 ConfigStore / StoragePaths，与设置页共用同一配置。
 */
class SetupGuideActivity : AppCompatActivity() {

    private var step = 0
    private val modeRadioIds = mutableMapOf<Int, Int>()

    private val stepIds = intArrayOf(
        R.id.step_welcome,
        R.id.step_storage,
        R.id.step_copy,
        R.id.step_sync,
        R.id.step_done
    )

    private val pickTreeLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val uri: Uri? = result.data?.data
            val rg = findViewById<RadioGroup>(R.id.rg_guide_storage)
            if (result.resultCode == RESULT_OK && uri != null && StoragePaths.persistDataTree(this, uri)) {
                StoragePaths.setDataTree(this, uri)
                val path = StoragePaths.resolveTreeUriPath(this, uri)?.absolutePath ?: uri.toString()
                findViewById<TextView>(R.id.tv_guide_storage_path).text =
                    getString(R.string.guide_storage_picked, path)
            } else {
                if (result.resultCode == RESULT_OK && uri != null) {
                    toast(getString(R.string.storage_pick_not_writable))
                }
                rg.check(R.id.rb_guide_default)
                findViewById<TextView>(R.id.tv_guide_storage_path).text = ""
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_setup_guide)

        findViewById<RadioGroup>(R.id.rg_guide_storage).setOnCheckedChangeListener { _, checkedId ->
            if (checkedId == R.id.rb_guide_custom) {
                pickTreeLauncher.launch(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE))
            } else {
                findViewById<TextView>(R.id.tv_guide_storage_path).text = ""
            }
        }

        setupCopyRadios()

        findViewById<TextView>(R.id.btn_guide_back).setOnClickListener {
            if (step > 0) showStep(step - 1)
        }
        findViewById<TextView>(R.id.btn_guide_next).setOnClickListener { onNext() }

        showStep(0)
    }

    private fun setupCopyRadios() {
        val rg = findViewById<RadioGroup>(R.id.rg_guide_copy)
        val current = ConfigStore.getInt(this, "copy_resize_mode", 1)
        resources.getStringArray(R.array.copy_mode_options).forEachIndexed { i, label ->
            val rb = RadioButton(this)
            rb.id = View.generateViewId()
            rb.text = label
            rb.setTextColor(getColor(R.color.fg))
            rb.textSize = 14f
            rg.addView(rb)
            modeRadioIds[rb.id] = i
            if (i == current) rg.check(rb.id)
        }
        findViewById<SwitchMaterial>(R.id.sw_guide_avoid).isChecked =
            ConfigStore.getBoolean(this, "copy_avoid_webp", false)
    }

    private fun onNext() {
        if (step == stepIds.size - 1) {
            finishGuide()
            return
        }
        if (step == 2) saveCopyConfig()
        showStep(step + 1)
    }

    private fun showStep(index: Int) {
        step = index
        stepIds.forEachIndexed { i, id ->
            findViewById<View>(id).visibility = if (i == index) View.VISIBLE else View.GONE
        }
        findViewById<TextView>(R.id.tv_guide_step).text =
            getString(R.string.guide_step, index + 1, stepIds.size)
        findViewById<TextView>(R.id.btn_guide_back).visibility =
            if (index == 0) View.INVISIBLE else View.VISIBLE
        findViewById<TextView>(R.id.btn_guide_next).text =
            if (index == stepIds.size - 1) getString(R.string.guide_finish)
            else getString(R.string.guide_next)
    }

    private fun saveCopyConfig() {
        val rg = findViewById<RadioGroup>(R.id.rg_guide_copy)
        val mode = modeRadioIds[rg.checkedRadioButtonId] ?: 1
        ConfigStore.set(this, "copy_resize_mode", mode)
        ConfigStore.set(
            this,
            "copy_avoid_webp",
            findViewById<SwitchMaterial>(R.id.sw_guide_avoid).isChecked
        )
        ConfigStore.save(this)
    }

    private fun finishGuide() {
        StoragePaths.markSetupDone(this)
        ConfigStore.save(this)
        setResult(RESULT_OK)
        finish()
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}
