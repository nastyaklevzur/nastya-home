package ru.nastya.home

import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView

class MovieAdapter(
    private val onClick: (Movie) -> Unit,
    private val onMenu: (Movie) -> Unit,
    /** Подпись под названием вместо года (экран «Скрытые»: «Просмотрен 3 ноя 👍»). */
    private val caption: (Movie) -> String? = { null },
    /** Последняя карточка ряда («Показать всё», «Все · Скрытые»); null — без неё. */
    private val trailing: Trailing? = null,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    class Trailing(val label: String, val onClick: () -> Unit)

    private val items = ArrayList<Movie>()

    /** Фильм по позиции; у карточки [Trailing] фильма нет. */
    fun itemAt(position: Int): Movie? = items.getOrNull(position)

    fun submit(list: List<Movie>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    /** Перерисовать метки статуса, не трогая картинки. */
    fun refreshStatuses() = notifyItemRangeChanged(0, items.size, PAYLOAD_STATUS)

    override fun getItemViewType(position: Int): Int = if (position < items.size) TYPE_MOVIE else TYPE_MORE

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_MORE) MoreVH(inflater.inflate(R.layout.item_more, parent, false))
        else VH(inflater.inflate(R.layout.item_movie, parent, false))
    }

    override fun getItemCount(): Int = items.size + (if (trailing != null) 1 else 0)

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (holder) {
            is VH -> holder.bind(items[position])
            is MoreVH -> holder.bind()
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int, payloads: MutableList<Any>) {
        if (holder is VH && payloads.contains(PAYLOAD_STATUS)) holder.bindStatus(items[position]) else onBindViewHolder(holder, position)
    }

    /** Карточка «Показать всё» в конце ряда. */
    inner class MoreVH(view: View) : RecyclerView.ViewHolder(view) {
        private val frame: View = view.findViewById(R.id.moreFrame)
        private val label: TextView = view.findViewById(R.id.moreLabel)

        init {
            frame.clipToOutline = true
            view.setOnFocusChangeListener { v, hasFocus ->
                val scale = if (hasFocus) 1.08f else 1f
                v.animate().scaleX(scale).scaleY(scale)
                    .setDuration(160).setInterpolator(DecelerateInterpolator()).start()
                val d = v.resources.displayMetrics.density
                frame.elevation = if (hasFocus) 12f * d else 4f * d
            }
            view.setOnClickListener { trailing?.onClick?.invoke() }
        }

        fun bind() {
            label.text = trailing?.label ?: ""
        }
    }

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        private val posterFrame: FrameLayout = view.findViewById(R.id.posterFrame)
        private val poster: ImageView = view.findViewById(R.id.poster)
        private val rating: TextView = view.findViewById(R.id.rating)
        private val status: TextView = view.findViewById(R.id.status)
        private val pin: TextView = view.findViewById(R.id.pin)
        private val fav: TextView = view.findViewById(R.id.fav)
        private val award: TextView = view.findViewById(R.id.award)
        private val like: TextView = view.findViewById(R.id.like)
        private val title: TextView = view.findViewById(R.id.title)
        private val meta: TextView = view.findViewById(R.id.meta)

        init {
            posterFrame.clipToOutline = true
            view.setOnFocusChangeListener { v, hasFocus ->
                val scale = if (hasFocus) 1.08f else 1f
                v.animate().scaleX(scale).scaleY(scale)
                    .setDuration(160).setInterpolator(DecelerateInterpolator()).start()
                val d = v.resources.displayMetrics.density
                posterFrame.elevation = if (hasFocus) 12f * d else 4f * d
            }
            view.setOnClickListener { current()?.let(onClick) }
            view.setOnLongClickListener { current()?.let(onMenu); true }
            // Кнопка Menu на пульте открывает меню карточки (долгое нажатие OK на проекторе перехвачено).
            view.setOnKeyListener { _, keyCode, event ->
                if ((keyCode == KeyEvent.KEYCODE_MENU || keyCode == KeyEvent.KEYCODE_INFO) && event.action == KeyEvent.ACTION_UP) {
                    current()?.let(onMenu)
                    true
                } else {
                    false
                }
            }
        }

        private fun current(): Movie? = items.getOrNull(bindingAdapterPosition)

        fun bind(m: Movie) {
            title.text = m.title
            meta.text = caption(m) ?: m.year?.toString() ?: ""
            val r = m.ratingText
            rating.visibility = if (r == null) View.GONE else View.VISIBLE
            rating.text = r
            bindStatus(m)
            Posters.load(poster, m.posterUrl, m.posterFileName)
        }

        fun bindStatus(m: Movie) {
            pin.visibility = if (StateStore.isPinned(m)) View.VISIBLE else View.GONE
            fav.visibility = if (StateStore.isFavorite(m)) View.VISIBLE else View.GONE
            like.visibility = if (StateStore.isLiked(m)) View.VISIBLE else View.GONE
            // Кубок — только за победы (Mac ставит award_badge лишь лауреатам); текст не показываем, один значок.
            award.visibility = if (m.awardBadge == null) View.GONE else View.VISIBLE
            val s = WatchStatus.get(status.context, m)
            if (s == WatchStatus.Status.NONE) {
                status.visibility = View.GONE
                poster.alpha = 1f
            } else {
                status.visibility = View.VISIBLE
                status.text = s.label
                status.setBackgroundResource(if (s == WatchStatus.Status.WATCHED) R.drawable.badge_watched else R.drawable.badge_started)
                status.setTextColor(ContextCompat.getColor(status.context, if (s == WatchStatus.Status.WATCHED) R.color.cream else R.color.plum_deep))
                poster.alpha = if (s == WatchStatus.Status.WATCHED) 0.55f else 1f
            }
        }
    }

    companion object {
        private const val PAYLOAD_STATUS = "status"
        private const val TYPE_MOVIE = 0
        private const val TYPE_MORE = 1
    }
}
