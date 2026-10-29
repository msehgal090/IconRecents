package com.manu.iconrecents

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView

/**
 * RecyclerView adapter showing ONLY app icons (no labels, no thumbnails).
 * Supports click (launch) and long-click (force-stop) per icon.
 */
class AppIconAdapter(
    private val onIconClick: (RecentApp) -> Unit,
    private val onIconLongClick: (RecentApp) -> Unit
) : ListAdapter<RecentApp, AppIconAdapter.IconViewHolder>(DIFF_CALLBACK) {

    // ─────────────────────────────────────────────────────────────────────────
    // ViewHolder
    // ─────────────────────────────────────────────────────────────────────────

    inner class IconViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val ivIcon: ImageView = view.findViewById(R.id.ivAppIcon)

        fun bind(app: RecentApp) {
            ivIcon.setImageDrawable(app.icon)
            ivIcon.contentDescription = app.label   // accessibility

            itemView.setOnClickListener {
                onIconClick(app)
            }
            itemView.setOnLongClickListener {
                onIconLongClick(app)
                true
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Adapter overrides
    // ─────────────────────────────────────────────────────────────────────────

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): IconViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_app_icon, parent, false)
        return IconViewHolder(view)
    }

    override fun onBindViewHolder(holder: IconViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    // ─────────────────────────────────────────────────────────────────────────
    // DiffUtil — compare by package name (stable identity)
    // ─────────────────────────────────────────────────────────────────────────

    companion object {
        val DIFF_CALLBACK = object : DiffUtil.ItemCallback<RecentApp>() {
            override fun areItemsTheSame(a: RecentApp, b: RecentApp): Boolean =
                a.packageName == b.packageName

            override fun areContentsTheSame(a: RecentApp, b: RecentApp): Boolean =
                a.packageName == b.packageName
        }
    }
}
