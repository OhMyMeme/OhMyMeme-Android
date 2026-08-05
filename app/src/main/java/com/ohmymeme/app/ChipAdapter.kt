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

class ChipAdapter(
    private val style: ChipStyle,
    private val items: List<String>,
    private val activeItems: Set<String> = emptySet()
) : RecyclerView.Adapter<ChipAdapter.ChipViewHolder>() {

    class ChipViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChipViewHolder {
        val layout = if (style == ChipStyle.TAG) R.layout.item_tag else R.layout.item_collection
        val itemView = LayoutInflater.from(parent.context).inflate(layout, parent, false)
        return ChipViewHolder(itemView)
    }

    override fun onBindViewHolder(holder: ChipViewHolder, position: Int) {
        val text = items[position]
        val active = activeItems.contains(text)
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
    }

    override fun getItemCount() = items.size
}
