package com.korsarts.tvlauncher

import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView

class AppAdapter(
    private val onClick: (AppEntry) -> Unit,
    private val onMenu: (AppEntry) -> Unit,
) : ListAdapter<AppEntry, AppAdapter.Holder>(Diff) {

    /** Pacotes ocultos (exibidos esmaecidos quando "mostrar ocultos" está ativo). */
    var hidden: Set<String> = emptySet()

    init {
        setHasStableIds(true)
    }

    override fun getItemId(position: Int): Long = getItem(position).packageName.hashCode().toLong()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_app, parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(getItem(position))

    inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
        private val banner: ImageView = view.findViewById(R.id.banner)
        private val iconGroup: View = view.findViewById(R.id.icon_group)
        private val icon: ImageView = view.findViewById(R.id.icon)
        private val iconLabel: TextView = view.findViewById(R.id.icon_label)
        private val label: TextView = view.findViewById(R.id.label)

        fun bind(app: AppEntry) {
            if (app.banner != null) {
                banner.setImageDrawable(app.banner)
                banner.visibility = View.VISIBLE
                iconGroup.visibility = View.GONE
            } else {
                banner.setImageDrawable(null)
                banner.visibility = View.GONE
                iconGroup.visibility = View.VISIBLE
                icon.setImageDrawable(app.icon)
                iconLabel.text = app.label
            }
            label.text = app.label
            itemView.contentDescription = app.label
            itemView.alpha = if (app.packageName in hidden) 0.4f else 1f

            itemView.setOnClickListener { onClick(app) }
            itemView.setOnLongClickListener { onMenu(app); true }
            itemView.setOnKeyListener { _, keyCode, event ->
                if (keyCode == KeyEvent.KEYCODE_MENU && event.action == KeyEvent.ACTION_UP) {
                    onMenu(app); true
                } else false
            }
            itemView.setOnFocusChangeListener { v, hasFocus ->
                val scale = if (hasFocus) 1.1f else 1f
                v.animate().scaleX(scale).scaleY(scale).setDuration(120).start()
                v.translationZ = if (hasFocus) 8f else 0f
                label.visibility = if (hasFocus) View.VISIBLE else View.INVISIBLE
            }
        }
    }

    private object Diff : DiffUtil.ItemCallback<AppEntry>() {
        override fun areItemsTheSame(a: AppEntry, b: AppEntry) = a.packageName == b.packageName
        override fun areContentsTheSame(a: AppEntry, b: AppEntry) =
            a.label == b.label && a.lastUpdate == b.lastUpdate && a.component == b.component
    }
}
