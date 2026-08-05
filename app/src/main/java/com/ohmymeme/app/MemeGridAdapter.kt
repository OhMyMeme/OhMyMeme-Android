package com.ohmymeme.app

import android.content.Context
import android.graphics.BitmapFactory
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MemeGridAdapter(
    private val context: Context,
    private val items: List<Meme>
) : RecyclerView.Adapter<MemeGridAdapter.MemeViewHolder>() {

    private val executor: ExecutorService = Executors.newSingleThreadExecutor()

    class MemeViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MemeViewHolder {
        val itemView = LayoutInflater.from(parent.context).inflate(R.layout.item_meme, parent, false)
        return MemeViewHolder(itemView)
    }

    override fun onBindViewHolder(holder: MemeViewHolder, position: Int) {
        val meme = items[position]
        val img = holder.itemView.findViewById<ImageView>(R.id.img_meme)
        val name = holder.itemView.findViewById<TextView>(R.id.tv_meme_name)
        img.setImageResource(R.drawable.ic_photo)
        img.setColorFilter(context.getColor(R.color.muted))
        img.tag = meme.id
        name.text = meme.originalName.ifEmpty { meme.filename.substringBeforeLast('.') }
        executor.execute {
            val path = Thumbnailer.getThumbPath(context, meme.id, meme.filename)
            if (path != null && img.tag == meme.id) {
                val bitmap = BitmapFactory.decodeFile(path)
                if (bitmap != null && img.tag == meme.id) {
                    img.post {
                        if (img.tag == meme.id) {
                            img.setColorFilter(null)
                            img.setImageBitmap(bitmap)
                        }
                    }
                }
            }
        }
    }

    override fun getItemCount() = items.size
}
