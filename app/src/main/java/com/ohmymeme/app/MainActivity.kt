package com.ohmymeme.app

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.Spannable
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.view.View
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private val executor = Executors.newSingleThreadExecutor()
    private var currentKeyword = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        setupLogo()
        setupTitleButtons()
        setupBars()
        setupSearch()
        ensureFirstRunSetup()
    }

    private fun ensureFirstRunSetup() {
        if (!StoragePaths.isFirstRun(this)) {
            reloadData()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.storage_title))
            .setMessage(getString(R.string.storage_message))
            .setCancelable(false)
            .setPositiveButton(getString(R.string.storage_use_default)) { _, _ ->
                StoragePaths.markSetupDone(this)
                reloadData()
            }
            .setNegativeButton(getString(R.string.storage_pick_custom)) { _, _ ->
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                startActivityForResult(intent, REQ_PICK_DIR)
            }
            .show()
    }

    private fun setupLogo() {
        val logo = findViewById<TextView>(R.id.tv_logo)
        val spannable = SpannableString("OhMyMeme")
        spannable.setSpan(
            ForegroundColorSpan(getColor(R.color.accent)),
            4, 8, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        logo.text = spannable
    }

    private fun setupTitleButtons() {
        findViewById<TextView>(R.id.btn_import).setOnClickListener { pickImages() }
        findViewById<TextView>(R.id.btn_refresh).setOnClickListener { rescanCache() }
        findViewById<TextView>(R.id.btn_settings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
    }

    private fun setupSearch() {
        findViewById<EditText>(R.id.et_search).addTextChangedListener(
            object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}

                override fun afterTextChanged(s: android.text.Editable?) {
                    currentKeyword = s?.toString() ?: ""
                    reloadData()
                }
            }
        )
    }

    private fun setupBars() {
        findViewById<RecyclerView>(R.id.rv_tags).let { rv ->
            rv.layoutManager = LinearLayoutManager(this, RecyclerView.HORIZONTAL, false)
            rv.adapter = ChipAdapter(ChipStyle.TAG, listOf(), setOf())
        }
        findViewById<RecyclerView>(R.id.rv_collections).let { rv ->
            rv.layoutManager = LinearLayoutManager(this, RecyclerView.HORIZONTAL, false)
            rv.adapter = ChipAdapter(ChipStyle.COLLECTION, listOf(), setOf())
        }
    }

    private fun reloadBars() {
        val db = MemeDb.get(this)
        val tags = db.getAllTags()
        findViewById<RecyclerView>(R.id.rv_tags).let { rv ->
            rv.adapter = ChipAdapter(ChipStyle.TAG, tags, setOf())
        }
        val collections = db.getCollections().map { it.name }
        findViewById<RecyclerView>(R.id.rv_collections).let { rv ->
            rv.adapter = ChipAdapter(ChipStyle.COLLECTION, collections, setOf())
        }
    }

    private fun reloadData() {
        reloadBars()
        executor.execute {
            val memes = if (currentKeyword.isEmpty()) {
                MemeDb.get(this).getAll(limit = 10000)
            } else {
                MemeDb.get(this).search(keyword = currentKeyword, limit = 10000)
            }
            runOnUiThread {
                findViewById<RecyclerView>(R.id.rv_memes).let { rv ->
                    rv.layoutManager = GridLayoutManager(this, 3)
                    rv.adapter = MemeGridAdapter(this, memes)
                }
                findViewById<View>(R.id.empty_state).visibility =
                    if (memes.isEmpty()) View.VISIBLE else View.GONE
            }
        }
    }

    private fun pickImages() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "image/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        startActivityForResult(intent, REQ_PICK)
    }

    private fun rescanCache() {
        executor.execute {
            val added = CacheScanner.scan(this)
            runOnUiThread {
                toast(getString(R.string.scan_done, added))
                reloadData()
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQ_PICK -> handlePickResult(resultCode, data)
            REQ_PICK_DIR -> handlePickDirResult(resultCode, data)
        }
    }

    private fun handlePickResult(resultCode: Int, data: Intent?) {
        if (resultCode != RESULT_OK || data == null) return
        val uris = mutableListOf<Uri>()
        if (data.clipData != null) {
            for (i in 0 until data.clipData!!.itemCount) {
                data.clipData!!.getItemAt(i).uri?.let { uris.add(it) }
            }
        } else {
            data.data?.let { uris.add(it) }
        }
        if (uris.isEmpty()) return
        executor.execute {
            val imported = MemeImporter.importUris(this, uris)
            runOnUiThread {
                toast(getString(R.string.import_done, imported))
                reloadData()
            }
        }
    }

    private fun handlePickDirResult(resultCode: Int, data: Intent?) {
        val uri = data?.data
        if (resultCode == RESULT_OK && uri != null) {
            val path = StoragePaths.resolveTreeUriPath(this, uri)
            if (path != null) {
                StoragePaths.setDataDir(this, path)
            } else {
                toast(getString(R.string.storage_pick_failed))
            }
        }
        StoragePaths.markSetupDone(this)
        ConfigStore.invalidate()
        reloadData()
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val REQ_PICK = 1001
        private const val REQ_PICK_DIR = 1002
    }
}
