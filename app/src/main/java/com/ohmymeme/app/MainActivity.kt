package com.ohmymeme.app

import android.app.AlertDialog
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.result.PickVisualMediaRequest
import androidx.core.content.FileProvider
import android.text.Editable
import android.text.Spannable
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageView
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

    private val executor = Executors.newSingleThreadExecutor()
    private val syncExecutor = Executors.newSingleThreadExecutor()
    private var currentKeyword = ""
    private var activeCollectionId: Long? = null
    private val activeTags = mutableSetOf<String>()
    private val expandedSidebarIds = mutableSetOf<Long>()
    private var sortModeEnabled = false
    private var manageMode = false
    private val selectedIds = mutableSetOf<Long>()
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
        findViewById<View>(R.id.btn_import).setOnClickListener { showImportMenu(it) }
        findViewById<View>(R.id.btn_more).setOnClickListener { showMoreActionsMenu(it) }
        findViewById<View>(R.id.btn_settings).setOnClickListener {
            settingsLauncher.launch(Intent(this, SettingsActivity::class.java))
        }
        findViewById<View>(R.id.btn_sidebar).setOnClickListener { toggleSidebar() }
        findViewById<View>(R.id.btn_sort_mode).setOnClickListener { toggleManageMode() }
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
        findViewById<RecyclerView>(R.id.rv_tags).let { rv ->
            rv.layoutManager = LinearLayoutManager(this, RecyclerView.HORIZONTAL, false)
            rv.adapter = ChipAdapter(emptyList<String>(), emptySet<String>()) { it }
        }
        findViewById<RecyclerView>(R.id.rv_sidebar).let { rv ->
            rv.layoutManager = LinearLayoutManager(this)
            rv.adapter = SidebarTreeAdapter(emptyList(), emptySet())
        }
    }

    private fun reloadBars() {
        val db = MemeDb.get(this)
        val all = db.getCollections()

        val rows = mutableListOf<SidebarRow>()
        val favoritesCount = db.count(favoriteOnly = true)
        if (favoritesCount > 0) {
            rows.add(
                SidebarRow(
                    CollectionEntry(COLLECTION_FAVORITES, getString(R.string.collection_favorites), favoritesCount),
                    0, false
                )
            )
        }
        val recentCount = db.getRecent(10000).size
        if (recentCount > 0) {
            rows.add(
                SidebarRow(
                    CollectionEntry(COLLECTION_RECENT, getString(R.string.collection_recent), recentCount),
                    0, false
                )
            )
        }
        if (ConfigStore.get(this).optBoolean("show_uncategorized", true)) {
            val uncategorizedCount = db.count(uncategorizedOnly = true)
            if (uncategorizedCount > 0) {
                rows.add(
                    SidebarRow(
                        CollectionEntry(
                            COLLECTION_UNCATEGORIZED,
                            getString(R.string.collection_uncategorized),
                            uncategorizedCount
                        ),
                        0, false
                    )
                )
            }
        }

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
        val activePath = computeActivePath(all)
        fun emit(node: CollectionNode, depth: Int) {
            if (node.entry.count == 0 && node.children.isEmpty()) return
            val expanded = expandedSidebarIds.contains(node.entry.id) ||
                activeCollectionId == node.entry.id || activePath.contains(node.entry.id)
            if (expanded) expandedSidebarIds.add(node.entry.id)
            rows.add(SidebarRow(node.entry, depth, node.entry.hasChildren && expanded))
            if (node.children.isNotEmpty() && expanded) {
                node.children.forEach { emit(it, depth + 1) }
            }
        }
        buildTree(null).forEach { emit(it, 0) }

        if (activeCollectionId != null && rows.none { it.entry.id == activeCollectionId }) {
            activeCollectionId = null
        }
        val activeIds = rows.filter {
            it.entry.id == activeCollectionId || activePath.contains(it.entry.id)
        }.map { it.entry.id }.toSet()
        findViewById<RecyclerView>(R.id.rv_sidebar).adapter =
            SidebarTreeAdapter(rows, activeIds).apply {
                onItemClick = { row -> toggleSidebarRow(row) }
                onItemLongClick = { v, row -> showCollectionMenu(v, row.entry) }
                onToggleExpand = { row -> toggleSidebarExpand(row) }
            }

        val allTags = db.getAllTags()
        findViewById<RecyclerView>(R.id.rv_tags).let { rv ->
            if (allTags.isEmpty()) {
                rv.visibility = View.GONE
            } else {
                rv.visibility = View.VISIBLE
                rv.adapter = ChipAdapter(allTags, activeTags) { it }.apply {
                    onItemClick = { tag -> toggleTag(tag) }
                }
            }
        }
    }

    private fun toggleSidebar() {
        val rv = findViewById<RecyclerView>(R.id.rv_sidebar)
        val open = rv.visibility != View.VISIBLE
        rv.visibility = if (open) View.VISIBLE else View.GONE
        findViewById<ImageView>(R.id.btn_sidebar)
            .setImageResource(if (open) R.drawable.ic_close else R.drawable.ic_sidebar)
    }

    private fun toggleManageMode() {
        manageMode = !manageMode
        if (manageMode) {
            sortModeEnabled = false
            selectedIds.clear()
        } else {
            selectedIds.clear()
        }
        findViewById<ImageView>(R.id.btn_sort_mode).setColorFilter(
            getColor(if (manageMode) R.color.accent else R.color.muted)
        )
        updateManageBar()
        toast(getString(if (manageMode) R.string.sort_mode_on else R.string.sort_mode_off))
        reloadData()
    }

    private fun toggleDragSort() {
        sortModeEnabled = !sortModeEnabled
        if (sortModeEnabled && manageMode) {
            manageMode = false
            selectedIds.clear()
            findViewById<ImageView>(R.id.btn_sort_mode).setColorFilter(getColor(R.color.muted))
            updateManageBar()
        }
        toast(getString(if (sortModeEnabled) R.string.drag_sort_on else R.string.drag_sort_off))
        reloadData()
    }

    private fun updateManageBar() {
        val bar = findViewById<View>(R.id.manage_bar)
        if (!manageMode) {
            bar.visibility = View.GONE
            return
        }
        bar.visibility = View.VISIBLE
        findViewById<TextView>(R.id.tv_manage_count).text =
            getString(R.string.manage_selected_count, selectedIds.size)
        findViewById<View>(R.id.btn_manage_select_all).setOnClickListener { selectAll() }
        findViewById<View>(R.id.btn_manage_cancel).setOnClickListener {
            selectedIds.clear()
            updateManageBar()
            (findViewById<RecyclerView>(R.id.rv_memes).adapter)?.notifyDataSetChanged()
        }
        findViewById<View>(R.id.btn_manage_delete).setOnClickListener { batchDelete() }
    }

    private fun selectAll() {
        val adapter = findViewById<RecyclerView>(R.id.rv_memes).adapter as? MemeGridAdapter ?: return
        val all = adapter.currentIds()
        selectedIds.clear()
        selectedIds.addAll(all)
        updateManageBar()
        adapter.notifyDataSetChanged()
    }

    private fun toggleSelect(meme: Meme) {
        if (selectedIds.contains(meme.id)) selectedIds.remove(meme.id)
        else selectedIds.add(meme.id)
        updateManageBar()
        findViewById<RecyclerView>(R.id.rv_memes).adapter?.notifyDataSetChanged()
    }

    private fun batchDelete() {
        val ids = selectedIds.toList()
        if (ids.isEmpty()) {
            toast(getString(R.string.manage_delete_empty))
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.manage_delete)
            .setMessage(getString(R.string.manage_batch_confirm, ids.size))
            .setPositiveButton(R.string.ok) { _, _ ->
                val adapter = findViewById<RecyclerView>(R.id.rv_memes).adapter as? MemeGridAdapter
                val memes = adapter?.itemsByIds(ids) ?: emptyList()
                executor.execute {
                    try {
                        memes.forEach { deleteMemeFiles(it) }
                        MemeDb.get(this).deleteMemes(ids)
                        runOnUiThread {
                            toast(getString(R.string.manage_delete_done, ids.size))
                            selectedIds.clear()
                            manageMode = false
                            findViewById<ImageView>(R.id.btn_sort_mode).setColorFilter(getColor(R.color.muted))
                            updateManageBar()
                            reloadData()
                        }
                    } catch (e: Exception) {
                        runOnUiThread { toast(getString(R.string.delete_failed)) }
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun toggleSidebarRow(row: SidebarRow) {
        if (row.entry.hasChildren) expandedSidebarIds.add(row.entry.id)
        activeCollectionId = if (activeCollectionId == row.entry.id) null else row.entry.id
        reloadData()
    }

    private fun toggleSidebarExpand(row: SidebarRow) {
        val id = row.entry.id
        if (expandedSidebarIds.contains(id)) expandedSidebarIds.remove(id) else expandedSidebarIds.add(id)
        reloadData()
    }

    private fun toggleTag(tag: String) {
        if (activeTags.contains(tag)) activeTags.remove(tag) else activeTags.add(tag)
        reloadData()
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
        val tags = activeTags.toList()
        latestReloadId += 1
        val reloadId = latestReloadId
        executor.execute {
            val db = MemeDb.get(this)
            val memes = when {
                collectionId == COLLECTION_FAVORITES -> db.search(
                    keyword = keyword,
                    tags = tags,
                    favoriteOnly = true,
                    offset = 0,
                    limit = 10000
                )
                collectionId == COLLECTION_RECENT -> {
                    val recent = db.getRecent(10000)
                    if (tags.isEmpty()) recent
                    else recent.filter { db.memeIdsWithAllTags(tags).contains(it.id) }
                }
                collectionId == COLLECTION_UNCATEGORIZED -> db.search(
                    keyword = keyword,
                    tags = tags,
                    uncategorizedOnly = true,
                    offset = 0,
                    limit = 10000
                )
                keyword.isEmpty() && collectionId == null && tags.isEmpty() ->
                    db.getAll(limit = 10000)
                else -> db.search(
                    keyword = keyword,
                    tags = tags,
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
                    rv.setPadding(12, 12, 12, if (manageMode) 76 else 12)
                    (rv.getTag(R.id.tag_sort_helper) as? ItemTouchHelper)?.attachToRecyclerView(null)
                    rv.setTag(R.id.tag_sort_helper, null)
                    val canOrder = sortModeEnabled && canOrderCards(keyword, collectionId, memes.size)
                    val adapter = MemeGridAdapter(this, memes, canOrder, manageMode, selectedIds).apply {
                        onItemClick = { _, meme -> onMemeClick(meme) }
                        onSelectToggle = { meme -> toggleSelect(meme) }
                        onMenuClick = { anchor, meme -> showMemeMenu(anchor, meme) }
                        onDragStart = { view, meme -> startGlobalDrag(view, meme) }
                        onDragFailed = { anchor, meme -> showMemeMenu(anchor, meme) }
                    }
                    rv.adapter = adapter
                    val helper = ItemTouchHelper(SortCallback(adapter, collectionId))
                    adapter.onReorderStart = { holder -> helper.startDrag(holder) }
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
                R.id.act_tag -> promptEditTags(meme)
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

    private fun promptEditTags(meme: Meme) {
        val view = layoutInflater.inflate(R.layout.dialog_tag_editor, null)
        val input = view.findViewById<EditText>(R.id.et_tag_input)
        val list = view.findViewById<RecyclerView>(R.id.rv_tag_list)
        list.layoutManager = LinearLayoutManager(this)
        val selected = mutableSetOf<String>()
        var allTags = listOf<String>()
        fun bind() {
            val q = input.text.toString().trim()
            val filtered = allTags.filter { q.isEmpty() || it.contains(q, ignoreCase = true) }
            list.adapter = TagChoiceAdapter(filtered, selected).apply {
                onItemClick = { tag ->
                    if (selected.contains(tag)) selected.remove(tag) else selected.add(tag)
                    bind()
                }
            }
            val selText = view.findViewById<TextView>(R.id.tv_tag_selected)
            selText.visibility = if (selected.isEmpty()) View.GONE else View.VISIBLE
            selText.text = getString(R.string.tag_selected_label) + selected.sorted().joinToString("、")
        }
        input.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}

            override fun afterTextChanged(s: Editable?) {
                bind()
            }
        })
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE || actionId == EditorInfo.IME_ACTION_NEXT) {
                val v = input.text.toString().trim()
                if (v.isNotEmpty()) {
                    selected.add(v)
                    input.setText("")
                }
                true
            } else {
                false
            }
        }
        executor.execute {
            val db = MemeDb.get(this)
            val current = db.getMemeTags(meme.id)
            allTags = db.getAllTags()
            runOnUiThread {
                selected.addAll(current)
                bind()
                AlertDialog.Builder(this)
                    .setTitle(R.string.tag_editor_title)
                    .setView(view)
                    .setPositiveButton(R.string.ok) { _, _ ->
                        executor.execute {
                            MemeDb.get(this).setMemeTags(meme.id, selected.toList())
                            runOnUiThread {
                                toast(getString(R.string.tags_saved))
                                reloadData()
                            }
                        }
                    }
                    .setNegativeButton(getString(R.string.cancel), null)
                    .show()
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
        val view = layoutInflater.inflate(R.layout.dialog_add_collection, null)
        val input = view.findViewById<EditText>(R.id.et_new_collection)
        val list = view.findViewById<RecyclerView>(R.id.rv_existing_collections)
        list.layoutManager = LinearLayoutManager(this)
        executor.execute {
            val existing = MemeDb.get(this).getCollections().filter { it.id > 0 }
            runOnUiThread {
                val dialog = AlertDialog.Builder(this)
                    .setTitle(R.string.add_collection_dialog_title)
                    .setView(view)
                    .setPositiveButton(getString(R.string.create_group)) { _, _ ->
                        val name = input.text.toString().trim()
                        if (name.isEmpty()) {
                            toast(getString(R.string.input_empty))
                            return@setPositiveButton
                        }
                        executor.execute {
                            val db = MemeDb.get(this)
                            val cid = db.createCollection(name)
                            db.addToCollection(meme.id, cid)
                            runOnUiThread {
                                toast(getString(R.string.added_to_collection, name))
                                reloadData()
                            }
                        }
                    }
                    .setNegativeButton(getString(R.string.cancel), null)
                    .show()
                list.adapter = ExistingCollectionAdapter(existing).apply {
                    onItemClick = { entry ->
                        dialog.dismiss()
                        executor.execute {
                            MemeDb.get(this@MainActivity).addToCollection(meme.id, entry.id)
                            runOnUiThread {
                                toast(getString(R.string.added_to_collection, entry.name))
                                reloadData()
                            }
                        }
                    }
                }
            }
        }
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
                    File(cache, "share_${meme.id}_${file.name}").also { file.copyTo(it) }
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
        if (!ConfigStore.get(this).optBoolean("record_recent_use", true)) return
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
        Thumbnailer.findMemeFile(this, meme.filename)?.delete()
        StoragePaths.thumbnailDir(this).listFiles()
            .filter { it.name.startsWith("${meme.id}_") }
            .forEach { it.delete() }
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
                R.id.act_toggle_drag_sort -> toggleDragSort()
                R.id.act_sync_push -> quickSync(isUpload = true)
                R.id.act_sync_pull -> quickSync(isUpload = false)
                R.id.act_refresh -> rescanCache()
            }
            true
        }
        popup.show()
    }

    /** 相册式跨应用拖拽：长按卡片直接拖入聊天软件，SAF 模式先物化到 cacheDir 再经 FileProvider 暴露 */
    private fun startGlobalDrag(itemView: View, meme: Meme) {
        executor.execute {
            val file = materializeDragFile(meme)
            runOnUiThread {
                if (file == null) {
                    toast(getString(R.string.drag_file_missing))
                    return@runOnUiThread
                }
                val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
                val label = meme.originalName.ifEmpty { meme.filename.substringBeforeLast('.') }
                val data = ClipData.newUri(contentResolver, label, uri)
                val shadow = itemView.findViewById<View>(R.id.img_meme) ?: itemView
                itemView.startDragAndDrop(
                    data,
                    View.DragShadowBuilder(shadow),
                    meme,
                    View.DRAG_FLAG_GLOBAL or View.DRAG_FLAG_GLOBAL_URI_READ
                )
                recordUse(meme)
            }
        }
    }

    /** 拖拽源文件物化：真实路径/SAF 统一复制到内部 cacheDir，保证 FileProvider 路径一致 */
    private fun materializeDragFile(meme: Meme): File? {
        return try {
            val stor = Thumbnailer.findMemeFile(this, meme.filename) ?: return null
            val ext = meme.filename.substringAfterLast('.', "img")
            val tmp = File(cacheDir, "drag_${meme.id}_${System.nanoTime()}.$ext")
            stor.copyTo(tmp)
            tmp
        } catch (e: Exception) {
            android.util.Log.w(TAG, "materializeDragFile failed: $e")
            null
        }
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
            val result = MemeImporter.importUris(this, uris)
            runOnUiThread {
                var msg = getString(R.string.import_done, result.imported)
                if (result.rejected > 0) msg += getString(R.string.import_rejected, result.rejected)
                if (result.errors.isNotEmpty()) msg += getString(R.string.import_errors, result.errors.size)
                toast(msg)
                reloadData()
            }
        }
    }

    private fun handlePickDirResult(resultCode: Int, data: Intent?) {
        val uri = data?.data
        if (resultCode == RESULT_OK && uri != null) {
            if (StoragePaths.persistDataTree(this, uri)) {
                StoragePaths.setDataTree(this, uri)
            } else {
                toast(getString(R.string.storage_pick_not_writable))
            }
        }
        StoragePaths.markSetupDone(this)
        ConfigStore.invalidate()
        reloadData()
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    private inner class ExistingCollectionAdapter(
        private val items: List<CollectionEntry>
    ) : RecyclerView.Adapter<ExistingCollectionAdapter.Holder>() {

        var onItemClick: ((CollectionEntry) -> Unit)? = null

        class Holder(itemView: View) : RecyclerView.ViewHolder(itemView)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            return Holder(
                LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_add_collection_row, parent, false)
            )
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val entry = items[position]
            holder.itemView.findViewById<TextView>(R.id.tv_existing_collection_name).text = entry.name
            holder.itemView.setOnClickListener { onItemClick?.invoke(entry) }
        }

        override fun getItemCount() = items.size
    }

    private inner class TagChoiceAdapter(
        private val tags: List<String>,
        private val selected: Set<String>
    ) : RecyclerView.Adapter<TagChoiceAdapter.Holder>() {

        var onItemClick: ((String) -> Unit)? = null

        class Holder(itemView: View) : RecyclerView.ViewHolder(itemView)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            return Holder(
                LayoutInflater.from(parent.context).inflate(R.layout.item_tag_check, parent, false)
            )
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val tag = tags[position]
            val tv = holder.itemView.findViewById<TextView>(R.id.tv_tag_check)
            val active = selected.contains(tag)
            tv.text = tag
            tv.setBackgroundResource(
                if (active) R.drawable.bg_collection_chip_active else R.drawable.bg_collection_chip
            )
            tv.setTextColor(
                holder.itemView.context.getColor(if (active) R.color.accent else R.color.fg_secondary)
            )
            holder.itemView.setOnClickListener { onItemClick?.invoke(tag) }
        }

        override fun getItemCount() = tags.size
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
