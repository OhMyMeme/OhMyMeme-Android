package com.ohmymeme.app

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.result.PickVisualMediaRequest
import androidx.core.content.FileProvider
import android.text.Spannable
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.view.View
import android.widget.EditText
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.io.File
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private val TAG = "OhMyMeme/MainActivity"

    private data class CollectionEntry(
        val id: Long,
        val name: String,
        val count: Int,
        val hasChildren: Boolean = false
    )

    private data class CollectionNode(
        val entry: CollectionEntry,
        val children: List<CollectionNode>
    )

    private val executor = Executors.newSingleThreadExecutor()
    private val syncExecutor = Executors.newSingleThreadExecutor()
    private var currentKeyword = ""
    private var activeCollectionId: Long? = null
    private var latestReloadId = 0L

    companion object {
        private const val COLLECTION_FAVORITES = -2L
        private const val COLLECTION_RECENT = -3L
        private const val COLLECTION_UNCATEGORIZED = -4L
    }

    private val importLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            handlePickResult(result.resultCode, result.data)
        }
    private val galleryLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            handlePickResult(result.resultCode, result.data)
        }
    private val photoPickerLauncher =
        registerForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { uris ->
            if (uris.isNotEmpty()) doImport(uris)
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
        autoSyncIfConfigured()
        handleIncomingIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    @Suppress("DEPRECATION")
    private fun handleIncomingIntent(intent: Intent) {
        if (intent.action != Intent.ACTION_SEND && intent.action != Intent.ACTION_SEND_MULTIPLE) return
        val uris = mutableListOf<Uri>()
        when (intent.action) {
            Intent.ACTION_SEND -> intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)?.let { uris.add(it) }
            Intent.ACTION_SEND_MULTIPLE -> {
                intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.let { uris.addAll(it) }
            }
        }
        if (uris.isEmpty()) return
        doImport(uris)
    }

    private fun autoSyncIfConfigured() {
        if (StoragePaths.isFirstRun(this)) return
        val cfg = ConfigStore.get(this)
        val syncType = cfg.optString("sync_type", "")
        val autoFetch = cfg.optBoolean("sync_auto_fetch_index", false)
        val autoSync = cfg.optBoolean("sync_auto_sync", false)
        if (syncType.isEmpty() || (!autoFetch && !autoSync)) return
        executor.execute {
            try {
                if (autoSync) {
                    CloudSync.pull(this)
                } else if (autoFetch) {
                    CloudSync.checkSyncStatus(this)
                }
                runOnUiThread { reloadData() }
            } catch (e: Exception) {
                runOnUiThread { toast(e.message ?: getString(R.string.sync_failed)) }
            }
        }
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
        findViewById<TextView>(R.id.btn_import).setOnClickListener { showImportMenu(it) }
        findViewById<View>(R.id.btn_more).setOnClickListener { showMoreActionsMenu(it) }
        findViewById<View>(R.id.btn_settings).setOnClickListener {
            settingsLauncher.launch(Intent(this, SettingsActivity::class.java))
        }
    }

    private fun quickSync(isUpload: Boolean) {
        val cfg = ConfigStore.get(this)
        if (cfg.optString("sync_type", "").isEmpty()) {
            toast(getString(R.string.sync_not_configured))
            return
        }
        val showProgress = cfg.optBoolean(
            if (isUpload) "show_upload_progress" else "show_download_progress", true
        )
        val showDone = cfg.optBoolean(
            if (isUpload) "show_upload_done" else "show_download_done", true
        )
        val titleRes = if (isUpload) R.string.sync_pushing else R.string.sync_pulling
        val doneTitleRes = if (isUpload) R.string.sync_upload_done_title else R.string.sync_download_done_title

        val syncProgress = CloudSync.SyncProgress()
        var dialog: AlertDialog? = null
        var inBackground = false
        if (showProgress) {
            val view = layoutInflater.inflate(R.layout.dialog_sync_progress, null)
            view.findViewById<TextView>(R.id.sync_progress_title).text = getString(titleRes)
            val bar = view.findViewById<ProgressBar>(R.id.sync_progress_bar)
            val pct = view.findViewById<TextView>(R.id.sync_progress_pct)
            val file = view.findViewById<TextView>(R.id.sync_progress_file)
            view.findViewById<TextView>(R.id.btn_sync_bg).setOnClickListener {
                inBackground = true
                dialog?.dismiss()
                dialog = null
            }
            syncProgress.onProgress = { p ->
                runOnUiThread {
                    if (inBackground || dialog == null) return@runOnUiThread
                    val percent = if (p.filesTotal > 0) p.done() * 100 / p.filesTotal else 0
                    bar.progress = percent
                    pct.text = "$percent% · ${formatSpeed(p.bytesDone(), p.startTime)}"
                    file.text = p.currentFile
                }
            }
            dialog = AlertDialog.Builder(this)
                .setView(view)
                .setCancelable(false)
                .create()
            dialog?.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
            dialog?.show()
        }
        syncExecutor.execute {
            try {
                val result = if (isUpload) CloudSync.push(this, syncProgress)
                else CloudSync.pull(this, syncProgress)
                android.util.Log.d(TAG, "quickSync ${if (isUpload) "push" else "pull"} result=$result")
                runOnUiThread {
                    if (!isUpload) reloadData()
                    if (!inBackground && showDone) {
                        dialog?.dismiss()
                        dialog = null
                        showSyncDoneDialog(doneTitleRes, syncSummary(result))
                        return@runOnUiThread
                    }
                    dialog?.dismiss()
                    dialog = null
                    toast(syncSummary(result))
                }
            } catch (e: Exception) {
                android.util.Log.e(TAG, "quickSync ${if (isUpload) "push" else "pull"} failed: $e")
                runOnUiThread {
                    dialog?.dismiss()
                    dialog = null
                    toast(e.message ?: getString(R.string.sync_failed))
                }
            }
        }
    }

    private fun formatSpeed(bytesDone: Long, startTime: Long): String {
        val elapsedSec = (System.currentTimeMillis() - startTime) / 1000.0
        if (elapsedSec <= 0.0) return "0 KB/s"
        val bytesPerSec = bytesDone / elapsedSec
        return if (bytesPerSec >= 1024.0 * 1024.0) {
            String.format("%.1f MB/s", bytesPerSec / 1024.0 / 1024.0)
        } else {
            String.format("%.0f KB/s", bytesPerSec / 1024.0)
        }
    }

    private fun showSyncDoneDialog(titleRes: Int, detail: String) {
        val view = layoutInflater.inflate(R.layout.dialog_sync_done, null)
        view.findViewById<TextView>(R.id.sync_done_title).text = getString(titleRes)
        view.findViewById<TextView>(R.id.sync_done_detail).text = detail
        val dialog = AlertDialog.Builder(this)
            .setView(view)
            .setCancelable(false)
            .create()
        dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
        view.findViewById<TextView>(R.id.btn_sync_done_close).setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    private fun syncSummary(r: CloudSync.SyncResult): String {
        val parts = mutableListOf<String>()
        if (r.uploaded > 0) parts.add(getString(R.string.sync_uploaded, r.uploaded))
        if (r.downloaded > 0) parts.add(getString(R.string.sync_downloaded, r.downloaded))
        if (r.skipped > 0) parts.add(getString(R.string.sync_skipped, r.skipped))
        if (r.deleted > 0) parts.add(getString(R.string.sync_deleted, r.deleted))
        if (r.removedLocal > 0) parts.add(getString(R.string.sync_removed_local, r.removedLocal))
        if (r.errors > 0) parts.add(getString(R.string.sync_errors, r.errors))
        return if (parts.isEmpty()) getString(R.string.sync_done) else parts.joinToString("，")
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
        findViewById<RecyclerView>(R.id.rv_collections).let { rv ->
            rv.layoutManager = LinearLayoutManager(this, RecyclerView.HORIZONTAL, false)
            rv.adapter = ChipAdapter(emptyList<CollectionEntry>(), emptySet()) { it.name }
        }
    }

    private fun reloadBars() {
        val db = MemeDb.get(this)
        val all = db.getCollections()
        fun buildTree(parentId: Long?): List<CollectionNode> {
            return all
                .filter { c -> if (parentId == null) c.parentId == null || c.parentId == 0L else c.parentId == parentId }
                .map { c ->
                    val kids = buildTree(c.id)
                    CollectionNode(
                        CollectionEntry(c.id, c.name, db.count(collectionId = c.id), kids.isNotEmpty()),
                        kids
                    )
                }
        }
        val top = buildTree(null)
        val activePath = computeActivePath(all)
        val display = mutableListOf<CollectionEntry>()
        val favoritesCount = db.count(favoriteOnly = true)
        if (favoritesCount > 0) {
            display.add(CollectionEntry(COLLECTION_FAVORITES, getString(R.string.collection_favorites), favoritesCount))
        }
        val recentCount = db.getRecent(10000).size
        if (recentCount > 0) {
            display.add(CollectionEntry(COLLECTION_RECENT, getString(R.string.collection_recent), recentCount))
        }
        if (ConfigStore.get(this).optBoolean("show_uncategorized", true)) {
            val uncategorizedCount = db.count(uncategorizedOnly = true)
            if (uncategorizedCount > 0) {
                display.add(
                    CollectionEntry(
                        COLLECTION_UNCATEGORIZED,
                        getString(R.string.collection_uncategorized),
                        uncategorizedCount
                    )
                )
            }
        }
        fun flatten(items: List<CollectionNode>, parentActive: Boolean) {
            items.forEach { node ->
                val entry = node.entry
                if (entry.count == 0 && node.children.isEmpty()) return@forEach
                display.add(entry)
                if (parentActive || activeCollectionId == entry.id || activePath.contains(entry.id)) {
                    if (node.children.isNotEmpty()) flatten(node.children, activeCollectionId == entry.id)
                }
            }
        }
        flatten(top, false)
        val active = display.firstOrNull { it.id == activeCollectionId }
        if (activeCollectionId != null && active == null) {
            activeCollectionId = null
        }
        val activeSet = display.filter {
            it.id == activeCollectionId || activePath.contains(it.id)
        }.toSet()
        findViewById<RecyclerView>(R.id.rv_collections).let { rv ->
            rv.adapter = ChipAdapter(
                display,
                activeSet
            ) { entry ->
                var label = entry.name
                if (entry.count > 0) label += " (${entry.count})"
                if (entry.hasChildren) label += " \u25BC"
                label
            }.apply {
                onItemClick = { e -> toggleCollection(e) }
                onItemLongClick = { v, e -> showCollectionMenu(v, e) }
            }
        }
    }

    private fun computeActivePath(all: List<MemeDb.Collection>): Set<Long> {
        val path = mutableSetOf<Long>()
        var cur = activeCollectionId ?: return path
        var guard = 0
        while (cur > 0 && guard++ < 16) {
            val parent = all.firstOrNull { it.id == cur }?.parentId
            if (parent == null || parent == 0L) break
            path.add(parent)
            cur = parent
        }
        return path
    }

    private fun showCollectionMenu(anchor: View, entry: CollectionEntry) {
        if (entry.id == COLLECTION_RECENT) {
            showClearRecentMenu(anchor)
            return
        }
        if (entry.id <= 0) return
        val popup = PopupMenu(this, anchor)
        popup.menuInflater.inflate(R.menu.menu_collection, popup.menu)
        popup.menu.findItem(R.id.act_clear_recent).isVisible = false
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.act_new_subcollection -> promptCreateSubcollection(entry)
                R.id.act_rename_collection -> promptRenameCollection(entry)
                R.id.act_delete_collection -> promptDeleteCollection(entry)
            }
            true
        }
        popup.show()
    }

    private fun showClearRecentMenu(anchor: View) {
        val popup = PopupMenu(this, anchor)
        popup.menuInflater.inflate(R.menu.menu_collection, popup.menu)
        popup.menu.findItem(R.id.act_new_subcollection).isVisible = false
        popup.menu.findItem(R.id.act_rename_collection).isVisible = false
        popup.menu.findItem(R.id.act_delete_collection).isVisible = false
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.act_clear_recent -> promptClearRecent()
            }
            true
        }
        popup.show()
    }

    private fun promptRenameCollection(entry: CollectionEntry) {
        val input = EditText(this)
        input.setText(entry.name)
        input.hint = getString(R.string.rename_hint)
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.rename_collection_dialog_title))
            .setView(input)
            .setPositiveButton(getString(R.string.ok)) { _, _ ->
                val name = input.text.toString().trim()
                if (name.isEmpty()) {
                    toast(getString(R.string.input_empty))
                    return@setPositiveButton
                }
                executor.execute {
                    MemeDb.get(this).renameCollection(entry.id, name)
                    runOnUiThread {
                        toast(getString(R.string.collection_renamed))
                        reloadData()
                    }
                }
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun promptDeleteCollection(entry: CollectionEntry) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.ctx_delete_collection))
            .setMessage(getString(R.string.delete_collection_confirm_message, entry.name))
            .setPositiveButton(getString(R.string.ok)) { _, _ ->
                executor.execute {
                    val db = MemeDb.get(this)
                    val parentId = db.getCollections().firstOrNull { it.id == entry.id }
                        ?.parentId?.takeIf { it != 0L }
                    val members = db.search(collectionId = entry.id, offset = 0, limit = 10000)
                    for (m in members) {
                        if (parentId != null) db.addToCollection(m.id, parentId)
                    }
                    db.deleteCollection(entry.id)
                    val nextActive = if (activeCollectionId == entry.id) parentId else activeCollectionId
                    runOnUiThread {
                        activeCollectionId = nextActive
                        toast(getString(R.string.collection_deleted))
                        reloadData()
                    }
                }
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun promptClearRecent() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.clear_recent_confirm_title))
            .setMessage(getString(R.string.clear_recent_confirm_message))
            .setPositiveButton(getString(R.string.ok)) { _, _ ->
                executor.execute {
                    MemeDb.get(this).clearRecent()
                    runOnUiThread {
                        if (activeCollectionId == COLLECTION_RECENT) activeCollectionId = null
                        toast(getString(R.string.recent_cleared))
                        reloadData()
                    }
                }
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun promptCreateSubcollection(entry: CollectionEntry) {
        val db = MemeDb.get(this)
        if (db.getCollectionDepth(entry.id) >= 1) {
            toast(getString(R.string.subcollection_depth_limit))
            return
        }
        val input = EditText(this)
        input.hint = getString(R.string.add_collection_hint)
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.new_subcollection_title))
            .setView(input)
            .setPositiveButton(getString(R.string.ok)) { _, _ ->
                val name = input.text.toString().trim()
                if (name.isEmpty()) {
                    toast(getString(R.string.input_empty))
                    return@setPositiveButton
                }
                executor.execute {
                    MemeDb.get(this).createCollection(name, entry.id)
                    runOnUiThread {
                        toast(getString(R.string.subcollection_created))
                        reloadData()
                    }
                }
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun toggleCollection(entry: CollectionEntry) {
        activeCollectionId = if (activeCollectionId == entry.id) null else entry.id
        reloadData()
    }

    private fun promptAddToSubgroup(meme: Meme) {
        val targetCol = activeCollectionId ?: return
        if (targetCol <= 0) return
        val db = MemeDb.get(this)
        val children = db.getChildCollections(targetCol)
        val labels = mutableListOf(getString(R.string.new_subcollection_title))
        val ids = mutableListOf<Long?>(-1L)
        children.forEach { child ->
            labels.add(child.name)
            ids.add(child.id)
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.ctx_add_subgroup))
            .setItems(labels.toTypedArray()) { _, which ->
                val pickedId = ids[which]
                if (pickedId == -1L) {
                    promptCreateSubcollectionFor(meme, targetCol)
                } else if (pickedId != null) {
                    executor.execute {
                        MemeDb.get(this).addToCollection(meme.id, pickedId)
                        runOnUiThread {
                            toast(getString(R.string.added_to_collection, labels[which]))
                            reloadData()
                        }
                    }
                }
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun promptCreateSubcollectionFor(meme: Meme, parentId: Long) {
        val input = EditText(this)
        input.hint = getString(R.string.add_collection_hint)
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.new_subcollection_title))
            .setView(input)
            .setPositiveButton(getString(R.string.ok)) { _, _ ->
                val name = input.text.toString().trim()
                if (name.isEmpty()) {
                    toast(getString(R.string.input_empty))
                    return@setPositiveButton
                }
                executor.execute {
                    val db = MemeDb.get(this)
                    if (db.getCollectionDepth(parentId) >= 1) {
                        runOnUiThread { toast(getString(R.string.subcollection_depth_limit)) }
                        return@execute
                    }
                    val cid = db.createCollection(name, parentId)
                    db.addToCollection(meme.id, cid)
                    runOnUiThread {
                        toast(getString(R.string.added_to_collection, name))
                        reloadData()
                    }
                }
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun reloadData() {
        reloadBars()
        val keyword = currentKeyword
        val collectionId = activeCollectionId
        latestReloadId += 1
        val reloadId = latestReloadId
        executor.execute {
            val db = MemeDb.get(this)
            val memes = when {
                collectionId == COLLECTION_FAVORITES -> db.search(
                    keyword = keyword,
                    favoriteOnly = true,
                    offset = 0,
                    limit = 10000
                )
                collectionId == COLLECTION_RECENT -> db.getRecent(10000)
                collectionId == COLLECTION_UNCATEGORIZED -> db.search(
                    keyword = keyword,
                    uncategorizedOnly = true,
                    offset = 0,
                    limit = 10000
                )
                keyword.isEmpty() && collectionId == null ->
                    db.getAll(limit = 10000)
                else -> db.search(
                    keyword = keyword,
                    collectionId = collectionId,
                    offset = 0,
                    limit = 10000
                )
            }
            android.util.Log.d(TAG, "reloadData got ${memes.size} memes keyword='$keyword'")
            runOnUiThread {
                if (reloadId != latestReloadId) return@runOnUiThread
                findViewById<RecyclerView>(R.id.rv_memes).let { rv ->
                    rv.layoutManager = GridLayoutManager(this, 3)
                    (rv.getTag(R.id.tag_sort_helper) as? ItemTouchHelper)?.attachToRecyclerView(null)
                    rv.setTag(R.id.tag_sort_helper, null)
                    val canOrder = canOrderCards(keyword, collectionId, memes.size)
                    val adapter = MemeGridAdapter(this, memes, canOrder).apply {
                        onItemClick = { _, meme -> onMemeClick(meme) }
                        onLongClick = { anchor, meme -> showMemeMenu(anchor, meme) }
                    }
                    rv.adapter = adapter
                    val helper = ItemTouchHelper(SortCallback(adapter, collectionId))
                    adapter.onDragStart = { holder -> helper.startDrag(holder) }
                    helper.attachToRecyclerView(rv)
                    rv.setTag(R.id.tag_sort_helper, helper)
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
        popup.menu.findItem(R.id.act_add_subgroup).isVisible =
            activeCollectionId != null && activeCollectionId!! > 0
        popup.menu.findItem(R.id.act_remove_collection).isVisible =
            activeCollectionId != null && activeCollectionId!! > 0
        popup.menu.findItem(R.id.act_remove_recent).isVisible =
            activeCollectionId == COLLECTION_RECENT
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.act_rename -> promptRename(meme)
                R.id.act_favorite -> toggleFavorite(meme)
                R.id.act_add_collection -> promptAddCollection(meme)
                R.id.act_add_subgroup -> promptAddToSubgroup(meme)
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

    private fun onMemeClick(meme: Meme) {
        recordUse(meme)
        shareMeme(meme)
    }

    private fun shareMeme(meme: Meme) {
        executor.execute {
            val file = Thumbnailer.findMemeFile(this, meme.filename)
            if (file == null) {
                runOnUiThread { toast(getString(R.string.share_file_missing)) }
                return@execute
            }
            try {
                val processed = MemeCopyProcessor.process(this, file)
                val cache = cacheDir
                val shareFile = if (processed != null) {
                    processed.file
                } else {
                    File(cache, "share_${meme.id}_${file.name}").also { file.copyTo(it, overwrite = true) }
                }
                val mime = processed?.mimeType ?: meme.mimeType.ifEmpty { "image/*" }
                val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", shareFile)
                runOnUiThread {
                    val share = Intent(Intent.ACTION_SEND).apply {
                        type = mime
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    startActivity(Intent.createChooser(share, getString(R.string.share_title)))
                }
            } catch (e: Exception) {
                android.util.Log.w(TAG, "share failed for ${meme.filename}: $e")
                runOnUiThread { toast(getString(R.string.share_failed)) }
            }
        }
    }

    private fun recordUse(meme: Meme) {
        executor.execute {
            MemeDb.get(this).recordUse(meme.id)
            val recentEmpty = activeCollectionId == COLLECTION_RECENT && MemeDb.get(this).getRecent(1).isEmpty()
            runOnUiThread {
                if (recentEmpty) activeCollectionId = null
                reloadData()
            }
        }
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

    private fun showImportMenu(anchor: View) {
        val popup = PopupMenu(this, anchor)
        popup.menuInflater.inflate(R.menu.menu_import, popup.menu)
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.act_import_files -> pickImages()
                R.id.act_import_album -> pickAlbumImages()
                R.id.act_import_qq -> toast(getString(R.string.import_qq_pending))
            }
            true
        }
        popup.show()
    }

    private fun showMoreActionsMenu(anchor: View) {
        val popup = PopupMenu(this, anchor)
        popup.menuInflater.inflate(R.menu.menu_more_actions, popup.menu)
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.act_sync_push -> quickSync(isUpload = true)
                R.id.act_sync_pull -> quickSync(isUpload = false)
                R.id.act_refresh -> rescanCache()
            }
            true
        }
        popup.show()
    }

    private fun pickAlbumImages() {
        if (ActivityResultContracts.PickVisualMedia.isPhotoPickerAvailable(this)) {
            photoPickerLauncher.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
            )
        } else {
            val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                type = "image/*"
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            }
            galleryLauncher.launch(intent)
        }
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
        doImport(uris)
    }

    private fun doImport(uris: List<Uri>) {
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

    private inner class SortCallback(
        private val adapter: MemeGridAdapter,
        private val collectionId: Long?
    ) : ItemTouchHelper.Callback() {

        override fun getMovementFlags(
            recyclerView: RecyclerView,
            viewHolder: RecyclerView.ViewHolder
        ): Int = makeMovementFlags(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN or
                ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT,
            0
        )

        override fun onMove(
            recyclerView: RecyclerView,
            viewHolder: RecyclerView.ViewHolder,
            target: RecyclerView.ViewHolder
        ): Boolean {
            adapter.move(viewHolder.bindingAdapterPosition, target.bindingAdapterPosition)
            return true
        }

        override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}

        override fun isLongPressDragEnabled() = false

        override fun clearView(
            recyclerView: RecyclerView,
            viewHolder: RecyclerView.ViewHolder
        ) {
            super.clearView(recyclerView, viewHolder)
            val ids = adapter.currentIds()
            executor.execute {
                if (collectionId != null && collectionId > 0) {
                    MemeDb.get(this@MainActivity).reorderCollectionMembers(collectionId, ids)
                } else {
                    MemeDb.get(this@MainActivity).reorderMemes(ids)
                }
            }
        }
    }
}
