package com.ohmymeme.app

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

data class CollectionEntry(
    val id: Long,
    val name: String,
    val count: Int,
    val hasChildren: Boolean = false
)

data class CollectionNode(
    val entry: CollectionEntry,
    val children: List<CollectionNode>
)

data class SidebarRow(
    val entry: CollectionEntry,
    val depth: Int,
    val expanded: Boolean
)

class SidebarTreeAdapter(
    private val items: List<SidebarRow>,
    private val activeIds: Set<Long>
) : RecyclerView.Adapter<SidebarTreeAdapter.SidebarViewHolder>() {

    var onItemClick: ((SidebarRow) -> Unit)? = null
    var onItemLongClick: ((View, SidebarRow) -> Unit)? = null
    var onToggleExpand: ((SidebarRow) -> Unit)? = null

    class SidebarViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SidebarViewHolder {
        val itemView = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_sidebar_collection, parent, false)
        return SidebarViewHolder(itemView)
    }

    override fun onBindViewHolder(holder: SidebarViewHolder, position: Int) {
        val row = items[position]
        val name = holder.itemView.findViewById<TextView>(R.id.tv_sidebar_name)
        val arrow = holder.itemView.findViewById<TextView>(R.id.tv_sidebar_arrow)
        val active = activeIds.contains(row.entry.id)

        var label = row.entry.name
        if (row.entry.count > 0) label += " (${row.entry.count})"
        name.text = label
        name.setTextColor(
            holder.itemView.context.getColor(if (active) R.color.accent else R.color.fg_secondary)
        )
        arrow.text = if (row.entry.hasChildren) {
            if (row.expanded) "▾" else "▸"
        } else {
            ""
        }
        holder.itemView.setPadding(
            (12 + row.depth * 16).dp(holder.itemView),
            holder.itemView.paddingTop,
            10,
            holder.itemView.paddingBottom
        )
        holder.itemView.setBackgroundResource(
            if (active) R.drawable.bg_sidebar_item_active else 0
        )
        holder.itemView.setOnClickListener { onItemClick?.invoke(row) }
        holder.itemView.setOnLongClickListener {
            onItemLongClick?.invoke(holder.itemView, row)
            true
        }
        arrow.setOnClickListener { onToggleExpand?.invoke(row) }
    }

    override fun getItemCount() = items.size
}

private fun Int.dp(view: View): Int =
    (this * view.resources.displayMetrics.density).toInt()