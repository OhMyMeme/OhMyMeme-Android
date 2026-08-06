package com.ohmymeme.app

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

enum class ChipStyle {
    TAG,
    COLLECTION
}

class ChipAdapter<T>(
    private val style: ChipStyle,
    private val items: List<T>,
    private val activeItems: Set<T>,
    private val label: (T) -> String
) : RecyclerView.Adapter<ChipAdapter.ChipViewHolder>() {

    var onItemClick: ((T) -> Unit)? = null

    class ChipViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChipViewHolder {
        val layout = if (style == ChipStyle.TAG) R.layout.item_tag else R.layout.item_collection
        val itemView = LayoutInflater.from(parent.context).inflate(layout, parent, false)
        return ChipViewHolder(itemView)
    }

    override fun onBindViewHolder(holder: ChipViewHolder, position: Int) {
        val item = items[position]
        val text = label(item)
        val active = activeItems.contains(item)
        val chip = holder.itemView.findViewById<TextView>(
            if (style == ChipStyle.TAG) R.id.tv_chip else R.id.tv_collection_chip
        )
        chip.text = text
        chip.setTextColor(holder.itemView.context.getColor(if (active) R.color.accent else R.color.muted))
        chip.setBackgroundResource(
            when {
                active && style == ChipStyle.TAG -> R.drawable.bg_chip_active
                active && style == ChipStyle.COLLECTION -> R.drawable.bg_collection_chip_active
                style == ChipStyle.TAG -> R.drawable.bg_chip
                else -> R.drawable.bg_collection_chip
            }
        )
        holder.itemView.setOnClickListener { onItemClick?.invoke(item) }
    }

    override fun getItemCount() = items.size
}
