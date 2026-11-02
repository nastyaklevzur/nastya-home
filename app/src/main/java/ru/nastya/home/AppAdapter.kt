package ru.nastya.home

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView

class AppAdapter(private val onClick: (AppShortcut) -> Unit) : RecyclerView.Adapter<AppAdapter.VH>() {

    private val items = ArrayList<AppShortcut>()

    fun submit(list: List<AppShortcut>) {
        if (list.map { it.id } == items.map { it.id }) return
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_app, parent, false))

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position])

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        private val icon: ImageView = view.findViewById(R.id.icon)
        private val label: TextView = view.findViewById(R.id.label)

        init {
            view.setOnFocusChangeListener { v, hasFocus ->
                val scale = if (hasFocus) 1.06f else 1f
                v.animate().scaleX(scale).scaleY(scale)
                    .setDuration(140).setInterpolator(DecelerateInterpolator()).start()
            }
            view.setOnClickListener { items.getOrNull(bindingAdapterPosition)?.let(onClick) }
        }

        fun bind(s: AppShortcut) {
            label.text = s.label
            icon.setImageDrawable(s.icon)
            icon.imageTintList = if (s.id == AppCatalog.REFRESH) {
                ColorStateList.valueOf(ContextCompat.getColor(icon.context, R.color.cream))
            } else {
                null
            }
        }
    }
}
