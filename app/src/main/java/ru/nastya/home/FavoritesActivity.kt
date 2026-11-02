package ru.nastya.home

import android.graphics.Rect
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.Locale

/** Экран «Избранное» с вкладкой «Скрытые» (просмотренные и убранные фильмы, откуда их можно вернуть). */
class FavoritesActivity : AppCompatActivity() {

    private lateinit var tabFav: TextView
    private lateinit var tabHidden: TextView
    private lateinit var grid: RecyclerView
    private lateinit var empty: TextView
    private lateinit var adapter: MovieAdapter
    private var hiddenTab = false
    private var base = ""
    private var pool: Map<String, Movie> = emptyMap()
    private var hiddenEntries: Map<String, Entry> = emptyMap()
    private val ru = Locale("ru")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        StateStore.init(this)
        setContentView(R.layout.activity_favorites)
        tabFav = findViewById(R.id.tabFav)
        tabHidden = findViewById(R.id.tabHidden)
        grid = findViewById(R.id.grid)
        empty = findViewById(R.id.empty)

        val data = MoviesRepository.loadLocal(this)
        base = data.base
        pool = data.movies.associateBy { it.key }

        adapter = MovieAdapter(onClick = ::onCard, onMenu = ::showMenu, caption = ::hiddenCaption)
        grid.layoutManager = GridLayoutManager(this, COLUMNS)
        grid.adapter = adapter
        val gap = (14 * resources.displayMetrics.density).toInt()
        grid.addItemDecoration(object : RecyclerView.ItemDecoration() {
            override fun getItemOffsets(outRect: Rect, view: View, parent: RecyclerView, state: RecyclerView.State) {
                outRect.right = gap
                outRect.bottom = gap / 2
            }
        })
        tabFav.setOnClickListener { selectTab(false) }
        tabHidden.setOnClickListener { selectTab(true) }
        selectTab(false)
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    private fun selectTab(hidden: Boolean) {
        hiddenTab = hidden
        reload()
    }

    private fun reload() {
        val s = StateStore.snapshot()
        tabFav.text = getString(R.string.tab_favorites) + " (${s.favorites.size})"
        tabHidden.text = getString(R.string.tab_hidden) + " (${s.removed.size})"
        tabFav.alpha = if (hiddenTab) 0.65f else 1f
        tabHidden.alpha = if (hiddenTab) 1f else 0.65f
        hiddenEntries = s.removed.associateBy { it.key }
        val entries = if (hiddenTab) s.removed.sortedByDescending { it.seq } else s.favorites.sortedByDescending { it.seq }
        val movies = entries.mapNotNull { e -> pool[e.key] ?: e.snap?.toMovie(e.key, base) }
        adapter.submit(movies)
        empty.text = getString(if (hiddenTab) R.string.empty_hidden else R.string.empty_favorites)
        empty.visibility = if (movies.isEmpty()) View.VISIBLE else View.GONE
        grid.visibility = if (movies.isEmpty()) View.INVISIBLE else View.VISIBLE
    }

    private fun hiddenCaption(m: Movie): String? {
        if (!hiddenTab) return null
        val e = hiddenEntries[m.key] ?: return null
        val date = try {
            SimpleDateFormat("d MMM", ru).format(SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(e.at.take(10))!!)
        } catch (ex: Exception) {
            ""
        }
        val what = if (e.reason == "watched") "Просмотрен" else "Убран"
        val like = when (e.liked) { true -> " 👍"; false -> " 👎"; null -> "" }
        return "$what $date$like".trim()
    }

    private fun onCard(m: Movie) {
        if (hiddenTab) showMenu(m) else if (!Launcher.openMovieInLampa(this, m)) toast("LAMPA не установлена")
    }

    private fun showMenu(m: Movie) {
        val items = ArrayList<MovieDialogs.Item>()
        fun add(label: String, action: () -> Unit) { items.add(MovieDialogs.Item(label, action)) }
        if (hiddenTab) {
            add(getString(R.string.menu_restore)) { StateStore.restore(m.key, m.title); toast("Вернул «${m.title}» в подборку"); reload() }
            add(getString(R.string.menu_about)) { startActivity(DetailsActivity.intent(this, m)) }
        } else {
            add(getString(R.string.menu_about)) { startActivity(DetailsActivity.intent(this, m)) }
            if (StateStore.isPinned(m)) add(getString(R.string.menu_unpin)) { StateStore.unpin(m); reload() }
            else add(getString(R.string.menu_pin)) {
                if (!StateStore.pin(m)) toast("Можно закрепить не больше трёх. Открепи что-нибудь") else toast("Закрепил «${m.title}»")
                reload()
            }
            add(getString(R.string.menu_watched)) {
                MovieDialogs.askLiked(this, m) { StateStore.remove(m, "watched", it); toast("Отметил просмотренным: «${m.title}»"); reload() }
            }
            add(getString(R.string.menu_fav_remove)) { StateStore.setFavorite(m, false); reload() }
        }
        MovieDialogs.menu(this, MovieDialogs.title(m), items)
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

    companion object {
        private const val COLUMNS = 6
    }
}
