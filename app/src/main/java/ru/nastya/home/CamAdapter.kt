package ru.nastya.home

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class CamAdapter(private val onClick: (Cam) -> Unit) : RecyclerView.Adapter<CamAdapter.VH>() {

    private val items = ArrayList<Cam>()

    fun submit(list: List<Cam>) {
        items.clear()
        items.addAll(list.filter { !it.hidden })
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_cam, parent, false))

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position])

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        private val frame: FrameLayout = view.findViewById(R.id.camFrame)
        private val preview: ImageView = view.findViewById(R.id.camPreview)
        private val title: TextView = view.findViewById(R.id.camTitle)

        init {
            frame.clipToOutline = true
            view.setOnFocusChangeListener { v, hasFocus ->
                val scale = if (hasFocus) 1.08f else 1f
                v.animate().scaleX(scale).scaleY(scale)
                    .setDuration(160).setInterpolator(DecelerateInterpolator()).start()
                val d = v.resources.displayMetrics.density
                frame.elevation = if (hasFocus) 12f * d else 4f * d
            }
            view.setOnClickListener { items.getOrNull(bindingAdapterPosition)?.let(onClick) }
        }

        fun bind(c: Cam) {
            title.text = c.label
            Posters.loadCam(preview, c)
        }
    }
}
