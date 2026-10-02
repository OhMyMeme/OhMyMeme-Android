package com.ohmymeme.app

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.ImageDecoder
import android.graphics.Rect
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import android.view.DragEvent
import android.view.MotionEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateInterpolator
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MemeGridAdapter(
    private val context: Context,
    items: List<Meme>,
    private val canOrder: Boolean,
    private val manageMode: Boolean = false,
    private val selectedIds: MutableSet<Long> = mutableSetOf()
) : RecyclerView.Adapter<MemeGridAdapter.MemeViewHolder>() {

    private val items: MutableList<Meme> = items.toMutableList()
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private var attachedRecycler: RecyclerView? = null

    var onItemClick: ((View, Meme) -> Unit)? = null
    var onSelectToggle: ((Meme, Int) -> Unit)? = null
    var onMenuClick: ((View, Meme) -> Unit)? = null
    var onDragStart: ((View, Meme) -> Unit)? = null
    var onDragFailed: ((View, Meme) -> Unit)? = null
    var onReorderStart: ((MemeViewHolder) -> Unit)? = null

    fun move(from: Int, to: Int) {
        if (from == to) return
        val item = items.removeAt(from)
        items.add(to, item)
        notifyItemMoved(from, to)
    }

    fun currentIds(): List<Long> = items.map { it.id }

    fun itemsByIds(ids: Collection<Long>): List<Meme> = items.filter { ids.contains(it.id) }

    /** 云端下载状态用伴生对象，跨 adapter 实例（reloadData 重建）保持遮罩与防连点 */
    fun setDownloading(id: Long, active: Boolean) {
        if (active) downloadingIds.add(id) else downloadingIds.remove(id)
        val pos = items.indexOfFirst { it.id == id }
        if (pos >= 0) notifyItemChanged(pos)
    }

    /** 下载完成：遮罩自上而下退场动画，结束后回调刷新（holder 不可见时直接回调） */
    fun wipeDownloadingMask(id: Long, onWiped: () -> Unit) {
        val pos = items.indexOfFirst { it.id == id }
        val holder = pos.takeIf { it >= 0 }
            ?.let { attachedRecycler?.findViewHolderForAdapterPosition(it) as? MemeViewHolder }
        val mask = holder?.itemView?.findViewById<TextView>(R.id.cloud_mask)
        if (mask == null || mask.visibility != View.VISIBLE || mask.height == 0) {
            downloadingIds.remove(id)
            onWiped()
            return
        }
        val h = mask.height
        ValueAnimator.ofInt(0, h).apply {
            duration = 400
            interpolator = AccelerateInterpolator()
            addUpdateListener {
                val top = it.animatedValue as Int
                mask.clipBounds = Rect(0, top, mask.width, h)
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    downloadingIds.remove(id)
                    mask.clipBounds = null
                    mask.visibility = View.GONE
                    onWiped()
                }
            })
            start()
        }
    }

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        super.onAttachedToRecyclerView(recyclerView)
        attachedRecycler = recyclerView
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        super.onDetachedFromRecyclerView(recyclerView)
        attachedRecycler = null
    }

    class MemeViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MemeViewHolder {
        val itemView = LayoutInflater.from(parent.context).inflate(R.layout.item_meme, parent, false)
        return MemeViewHolder(itemView)
    }

    override fun onBindViewHolder(holder: MemeViewHolder, position: Int) {
        val meme = items[position]
        val img = holder.itemView.findViewById<ImageView>(R.id.img_meme)
        val name = holder.itemView.findViewById<TextView>(R.id.tv_meme_name)
        val badge = holder.itemView.findViewById<TextView>(R.id.tv_meme_badge)
        val cloudBadge = holder.itemView.findViewById<ImageView>(R.id.tv_cloud_badge)
        val cloudMask = holder.itemView.findViewById<TextView>(R.id.cloud_mask)
        val dragHandle = holder.itemView.findViewById<ImageView>(R.id.btn_drag_handle)
        val menuButton = holder.itemView.findViewById<View>(R.id.btn_meme_menu)
        val selectCheck = holder.itemView.findViewById<TextView>(R.id.tv_select_check)
        (img.drawable as? AnimatedImageDrawable)?.stop()
        img.setImageResource(R.drawable.ic_photo)
        img.setColorFilter(context.getColor(R.color.muted))
        img.tag = meme.id
        name.text = meme.originalName.ifEmpty { meme.filename.substringBeforeLast('.') }
        badge.visibility = View.GONE
        badge.text = ""
        cloudBadge.visibility = if (meme.cloud) View.VISIBLE else View.GONE
        cloudMask.clipBounds = null
        cloudMask.visibility =
            if (meme.cloud && downloadingIds.contains(meme.id)) View.VISIBLE else View.GONE
        holder.itemView.setOnClickListener {
            if (meme.cloud && downloadingIds.contains(meme.id)) return@setOnClickListener
            if (manageMode && !meme.cloud) onSelectToggle?.invoke(meme, holder.bindingAdapterPosition)
            else onItemClick?.invoke(it, meme)
        }
        holder.itemView.setOnLongClickListener {
            when {
                meme.cloud -> true
                manageMode -> false
                else -> {
                    onDragStart?.invoke(it, meme)
                    true
                }
            }
        }
        holder.itemView.setOnDragListener { v, e ->
            if (e.action == DragEvent.ACTION_DRAG_ENDED && !e.result) {
                onDragFailed?.invoke(v, meme)
            }
            true
        }
        menuButton.visibility = if (manageMode || meme.cloud) View.GONE else View.VISIBLE
        menuButton.setOnClickListener {
            onMenuClick?.invoke(it, meme)
        }
        menuButton.setOnLongClickListener { true }
        selectCheck.visibility =
            if (manageMode && !meme.cloud && selectedIds.contains(meme.id)) View.VISIBLE else View.GONE
        dragHandle.visibility =
            if (!meme.cloud && (canOrder || manageMode)) View.VISIBLE else View.GONE
        dragHandle.setOnTouchListener(null)
        if (!meme.cloud && (canOrder || manageMode)) {
            dragHandle.setOnTouchListener { _, event ->
                if (event.action != MotionEvent.ACTION_DOWN) return@setOnTouchListener true
                if (holder.bindingAdapterPosition == RecyclerView.NO_POSITION) {
                    return@setOnTouchListener false
                }
                onReorderStart?.invoke(holder)
                true
            }
        }
        executor.execute {
            if (meme.cloud) {
                loadCloudThumb(img, meme)
                return@execute
            }
            val animated = isAnimatedFile(meme)
            val isGif = meme.mimeType.endsWith("gif") || meme.filename.lowercase().endsWith(".gif")
            img.post {
                if (img.tag == meme.id) {
                    badge.text = when {
                        meme.fromStego == 1 -> context.getString(R.string.badge_stego)
                        animated -> if (isGif) context.getString(R.string.badge_gif) else context.getString(R.string.badge_webp)
                        else -> ""
                    }
                    badge.visibility = if (meme.fromStego == 1 || animated) View.VISIBLE else View.GONE
                }
            }
            val autoPlay = ConfigStore.getBoolean(context, "auto_play_gif", true)
            if (animated && autoPlay) {
                loadAnimated(img, meme)
            } else {
                loadThumb(img, meme)
            }
        }
    }

    private fun isAnimatedFile(meme: Meme): Boolean {
        if (meme.mimeType.endsWith("gif") || meme.filename.lowercase().endsWith(".gif")) return true
        if (meme.filename.lowercase().endsWith(".webp")) {
            val f = StoragePaths.cacheDir(context).child(meme.filename)
            if (!f.exists) return false
            return FileUtils.isAnimatedFile(f)
        }
        return false
    }

    private fun loadAnimated(img: ImageView, meme: Meme) {
        val file = Thumbnailer.findMemeFile(context, meme.filename) ?: return
        val drawable = decodeAnimated(file)
        if (drawable == null) {
            loadThumb(img, meme)
            return
        }
        if (img.tag != meme.id) return
        img.post {
            if (img.tag == meme.id) {
                img.setColorFilter(null)
                img.setImageTintList(null)
                img.setImageDrawable(drawable)
                (drawable as? AnimatedImageDrawable)?.start()
            }
        }
    }

    private fun decodeAnimated(stor: StorFile): Drawable? {
        return try {
            val uri = stor.uri
            val source = if (uri != null) {
                ImageDecoder.createSource(context.contentResolver, uri)
            } else {
                ImageDecoder.createSource(stor.realFile!!)
            }
            ImageDecoder.decodeDrawable(source) { decoder, info, _ ->
                decoder.setTargetSampleSize(sampleSize(info.size.width, info.size.height, 300))
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun loadThumb(img: ImageView, meme: Meme) {
        val bitmap = Thumbnailer.getThumbBitmap(context, meme.id, meme.filename)
        if (bitmap != null && img.tag == meme.id) {
            img.post {
                if (img.tag == meme.id) {
                    img.setColorFilter(null)
                    img.setImageTintList(null)
                    img.setImageBitmap(bitmap)
                }
            }
        }
    }

    private fun loadCloudThumb(img: ImageView, meme: Meme) {
        val bitmap = Thumbnailer.cloudThumbBitmap(context, meme.fileHash)
        if (bitmap != null && img.tag == meme.id) {
            img.post {
                if (img.tag == meme.id) {
                    img.setColorFilter(null)
                    img.setImageTintList(null)
                    img.setImageBitmap(bitmap)
                }
            }
        }
    }

    private fun sampleSize(w: Int, h: Int, target: Int): Int {
        var sample = 1
        while (w / sample > target * 2 || h / sample > target * 2) {
            sample *= 2
        }
        return sample
    }

    override fun getItemCount() = items.size

    companion object {
        val downloadingIds: MutableSet<Long> = mutableSetOf()
    }
}
