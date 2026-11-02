package ru.nastya.home

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class MovieAdapter(
    private val onClick: (Movie) -> Unit,
    private val onLongClick: (Movie) -> Unit,
) : RecyclerView.Adapter<MovieAdapter.VH>() {

    private val items = ArrayList<Movie>()

    fun submit(list: List<Movie>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_movie, parent, false))

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position])

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        private val posterFrame: FrameLayout = view.findViewById(R.id.posterFrame)
        private val poster: ImageView = view.findViewById(R.id.poster)
        private val rating: TextView = view.findViewById(R.id.rating)
        private val title: TextView = view.findViewById(R.id.title)
        private val meta: TextView = view.findViewById(R.id.meta)
        private val note: TextView = view.findViewById(R.id.note)

        init {
            posterFrame.clipToOutline = true
            view.setOnFocusChangeListener { v, hasFocus ->
                val scale = if (hasFocus) 1.08f else 1f
                v.animate().scaleX(scale).scaleY(scale)
                    .setDuration(160).setInterpolator(DecelerateInterpolator()).start()
                posterFrame.elevation = if (hasFocus) 12f * v.resources.displayMetrics.density else 4f * v.resources.displayMetrics.density
            }
            view.setOnClickListener { current()?.let(onClick) }
            view.setOnLongClickListener { current()?.let(onLongClick); true }
        }

        private fun current(): Movie? = items.getOrNull(bindingAdapterPosition)

        fun bind(m: Movie) {
            title.text = m.title
            meta.text = m.year?.toString() ?: ""
            meta.visibility = if (m.year == null) View.GONE else View.VISIBLE
            val r = m.ratingText
            rating.visibility = if (r == null) View.GONE else View.VISIBLE
            rating.text = r
            note.text = m.note
            note.visibility = if (m.note.isBlank()) View.GONE else View.VISIBLE
            Posters.load(poster, m.posterUrl, m.posterFileName)
        }
    }
}
