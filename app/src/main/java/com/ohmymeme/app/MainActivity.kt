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
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.io.File
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private data class CollectionEntry(val id: Long, val name: String, val count: Int)

    private val executor = Executors.newSingleThreadExecutor()
    private var currentKeyword = ""
    private val activeTags = mutableSetOf<String>()
    private var activeCollectionId: Long? = null

    companion object {
        private const val COLLECTION_FAVORITES = -2L
        private const val COLLECTION_RECENT = -3L
    }

    private val importLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            handlePickResult(result.resultCode, result.data)
        }
    private val pickDirLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            handlePickDirResult(result.resultCode, result.data)
        }
    private val settingsLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) reloadData()
        }

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
                pickDirLauncher.launch(intent)
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
            settingsLauncher.launch(Intent(this, SettingsActivity::class.java))
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
            rv.adapter = ChipAdapter(ChipStyle.TAG, emptyList<String>(), emptySet()) { it }
        }
        findViewById<RecyclerView>(R.id.rv_collections).let { rv ->
            rv.layoutManager = LinearLayoutManager(this, RecyclerView.HORIZONTAL, false)
            rv.adapter = ChipAdapter(ChipStyle.COLLECTION, emptyList<CollectionEntry>(), emptySet()) { it.name }
        }
    }

    private fun reloadBars() {
        val db = MemeDb.get(this)
        val tags = db.getAllTags()
        activeTags.retainAll(tags)
        findViewById<RecyclerView>(R.id.rv_tags).let { rv ->
            rv.adapter = ChipAdapter(ChipStyle.TAG, tags, activeTags) { it }.apply {
                onItemClick = { name -> toggleTag(name) }
            }
        }
        val collections = mutableListOf(
            CollectionEntry(COLLECTION_FAVORITES, getString(R.string.collection_favorites), db.count(favoriteOnly = true)),
            CollectionEntry(COLLECTION_RECENT, getString(R.string.collection_recent), db.getRecent(10000).size)
        )
        db.getCollections().forEach { c ->
            collections.add(CollectionEntry(c.id, c.name, db.count(collectionId = c.id)))
        }
        if (activeCollectionId != null && activeCollectionId != COLLECTION_FAVORITES &&
            activeCollectionId != COLLECTION_RECENT && collections.none { it.id == activeCollectionId }
        ) {
            activeCollectionId = null
        }
        val display = collections.filter { it.count > 0 }
        val active = display.firstOrNull { it.id == activeCollectionId }
        if (activeCollectionId != null && active == null) {
            activeCollectionId = null
        }
        findViewById<RecyclerView>(R.id.rv_collections).let { rv ->
            rv.adapter = ChipAdapter(
                ChipStyle.COLLECTION,
                display,
                if (active != null) setOf(active) else emptySet()
            ) { if (it.count > 0) "${it.name} (${it.count})" else it.name }.apply {
                onItemClick = { entry -> toggleCollection(entry) }
            }
        }
    }

    private fun toggleTag(name: String) {
        if (!activeTags.add(name)) activeTags.remove(name)
        reloadData()
    }

    private fun toggleCollection(entry: CollectionEntry) {
        activeCollectionId = if (activeCollectionId == entry.id) null else entry.id
        reloadData()
    }

    private fun reloadData() {
        reloadBars()
        executor.execute {
            val db = MemeDb.get(this)
            val memes = when {
                activeCollectionId == COLLECTION_FAVORITES -> db.search(
                    keyword = currentKeyword,
                    tags = if (activeTags.isEmpty()) null else activeTags.toList(),
                    favoriteOnly = true,
                    offset = 0,
                    limit = 10000
                )
                activeCollectionId == COLLECTION_RECENT -> db.getRecent(10000)
                currentKeyword.isEmpty() && activeTags.isEmpty() && activeCollectionId == null ->
                    db.getAll(limit = 10000)
                else -> db.search(
                    keyword = currentKeyword,
                    tags = if (activeTags.isEmpty()) null else activeTags.toList(),
                    collectionId = activeCollectionId,
                    offset = 0,
                    limit = 10000
                )
            }
            runOnUiThread {
                findViewById<RecyclerView>(R.id.rv_memes).let { rv ->
                    rv.layoutManager = GridLayoutManager(this, 3)
                    rv.adapter = MemeGridAdapter(this, memes).apply {
                        onLongClick = { anchor, meme -> showMemeMenu(anchor, meme) }
                    }
                }
                findViewById<View>(R.id.empty_state).visibility =
                    if (memes.isEmpty()) View.VISIBLE else View.GONE
            }
        }
    }

    private fun showMemeMenu(anchor: View, meme: Meme) {
        val popup = PopupMenu(this, anchor)
        popup.menuInflater.inflate(R.menu.menu_meme, popup.menu)
        val favorited = MemeDb.get(this).isFavorite(meme.id)
        popup.menu.findItem(R.id.act_favorite).setTitle(
            if (favorited) getString(R.string.ctx_unfavorite) else getString(R.string.ctx_favorite)
        )
        popup.menu.findItem(R.id.act_remove_collection).isVisible =
            activeCollectionId != null && activeCollectionId!! > 0
        popup.menu.findItem(R.id.act_remove_recent).isVisible =
            activeCollectionId == COLLECTION_RECENT
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.act_rename -> promptRename(meme)
                R.id.act_favorite -> toggleFavorite(meme)
                R.id.act_add_collection -> promptAddCollection(meme)
                R.id.act_remove_collection -> removeFromCollection(meme)
                R.id.act_remove_recent -> removeFromRecent(meme)
                R.id.act_delete -> confirmDelete(meme)
            }
            true
        }
        popup.show()
    }

    private fun promptRename(meme: Meme) {
        val input = EditText(this)
        input.setText(meme.originalName)
        input.hint = getString(R.string.rename_hint)
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.rename_dialog_title))
            .setView(input)
            .setPositiveButton(getString(R.string.ok)) { _, _ ->
                val name = input.text.toString().trim()
                if (name.isEmpty()) {
                    toast(getString(R.string.input_empty))
                    return@setPositiveButton
                }
                executor.execute {
                    MemeDb.get(this).updateMeme(meme.id, mapOf("original_name" to name))
                    runOnUiThread {
                        toast(getString(R.string.renamed))
                        reloadData()
                    }
                }
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun toggleFavorite(meme: Meme) {
        executor.execute {
            val db = MemeDb.get(this)
            val nowFavorite = db.toggleFavorite(meme.id)
            val favEmpty = activeCollectionId == COLLECTION_FAVORITES && db.count(favoriteOnly = true) == 0
            runOnUiThread {
                if (favEmpty) activeCollectionId = null
                toast(getString(if (nowFavorite) R.string.favorited else R.string.unfavorited))
                reloadData()
            }
        }
    }

    private fun removeFromCollection(meme: Meme) {
        val cid = activeCollectionId ?: return
        if (cid <= 0) return
        executor.execute {
            val db = MemeDb.get(this)
            db.removeFromCollection(meme.id, cid)
            val parentId = db.getCollections().firstOrNull { it.id == cid }
                ?.parentId?.takeIf { it != 0L }
            if (parentId != null) db.addToCollection(meme.id, parentId)
            val empty = db.count(collectionId = cid) == 0
            if (empty) db.deleteCollection(cid)
            val nextActive = if (empty) parentId else cid
            runOnUiThread {
                activeCollectionId = nextActive
                toast(getString(R.string.removed_from_collection))
                reloadData()
            }
        }
    }

    private fun promptAddCollection(meme: Meme) {
        val input = EditText(this)
        input.hint = getString(R.string.add_collection_hint)
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.add_collection_dialog_title))
            .setView(input)
            .setPositiveButton(getString(R.string.ok)) { _, _ ->
                val name = input.text.toString().trim()
                if (name.isEmpty()) {
                    toast(getString(R.string.input_empty))
                    return@setPositiveButton
                }
                executor.execute {
                    val cid = MemeDb.get(this).createCollection(name)
                    MemeDb.get(this).addToCollection(meme.id, cid)
                    runOnUiThread {
                        toast(getString(R.string.added_to_collection, name))
                        reloadData()
                    }
                }
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun removeFromRecent(meme: Meme) {
        executor.execute {
            val db = MemeDb.get(this)
            db.removeFromRecent(meme.id)
            val recentEmpty = activeCollectionId == COLLECTION_RECENT && db.getRecent(1).isEmpty()
            runOnUiThread {
                if (recentEmpty) activeCollectionId = null
                toast(getString(R.string.removed_from_recent))
                reloadData()
            }
        }
    }

    private fun confirmDelete(meme: Meme) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.delete_confirm_title))
            .setMessage(getString(R.string.delete_confirm_message, meme.originalName.ifEmpty { meme.filename }))
            .setPositiveButton(getString(R.string.ok)) { _, _ ->
                executor.execute {
                    try {
                        deleteMemeFiles(meme)
                        MemeDb.get(this).deleteMeme(meme.id)
                        runOnUiThread {
                            toast(getString(R.string.deleted))
                            reloadData()
                        }
                    } catch (e: Exception) {
                        runOnUiThread { toast(getString(R.string.delete_failed)) }
                    }
                }
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun deleteMemeFiles(meme: Meme) {
        Thumbnailer.findMemeFile(this, meme.filename)?.let { it.delete() }
        StoragePaths.thumbnailDir(this).listFiles()
            ?.filter { it.name.startsWith("${meme.id}_") }
            ?.forEach { it.delete() }
    }

    private fun pickImages() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "image/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        importLauncher.launch(intent)
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
}
