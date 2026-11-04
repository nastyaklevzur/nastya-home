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

/** Экран «Рекомендации»: весь пул подборки (без убранных фильмов) сеткой, по рангу. Открывается карточкой «Показать всё». */
class RecommendationsActivity : AppCompatActivity() {

    private lateinit var grid: RecyclerView
    private lateinit var tvUpdated: TextView
    private lateinit var adapter: MovieAdapter
    private var pool: List<Movie> = emptyList()
    private val ru = Locale("ru")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        StateStore.init(this)
        setContentView(R.layout.activity_recommendations)
        grid = findViewById(R.id.grid)
        tvUpdated = findViewById(R.id.tvUpdated)

        val data = MoviesRepository.loadLocal(this)
        pool = data.movies.sortedBy { it.rank }
        tvUpdated.text = data.updated?.let { "обновлено " + formatDate(it) } ?: ""

        adapter = MovieAdapter(onClick = { startActivity(DetailsActivity.intent(this, it)) }, onMenu = ::showMenu)
        grid.layoutManager = GridLayoutManager(this, COLUMNS)
        grid.adapter = adapter
        val gap = (14 * resources.displayMetrics.density).toInt()
        grid.addItemDecoration(object : RecyclerView.ItemDecoration() {
            override fun getItemOffsets(outRect: Rect, view: View, parent: RecyclerView, state: RecyclerView.State) {
                outRect.right = gap
                outRect.bottom = gap / 2
            }
        })
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    /** Состояние могли изменить в «О фильме» или в меню: перечитываем и убираем скрытые. */
    private fun reload() {
        val removed = StateStore.snapshot().removedKeys
        adapter.submit(pool.filter { it.key !in removed })
    }

    private fun formatDate(iso: String): String = try {
        val date = SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(iso)
        if (date != null) SimpleDateFormat("d MMMM", ru).format(date) else iso
    } catch (e: Exception) {
        iso
    }

    private fun showMenu(m: Movie) {
        val items = ArrayList<MovieDialogs.Item>()
        fun add(label: String, action: () -> Unit) { items.add(MovieDialogs.Item(label, action)) }
        add(getString(R.string.menu_about)) { startActivity(DetailsActivity.intent(this, m)) }
        if (StateStore.isPinned(m)) add(getString(R.string.menu_unpin)) { StateStore.unpin(m); reload() }
        else add(getString(R.string.menu_pin)) {
            if (!StateStore.pin(m)) toast("Можно закрепить не больше трёх. Открепи что-нибудь") else toast("Закрепил «${m.title}»")
            reload()
        }
        if (StateStore.isLiked(m)) add(getString(R.string.menu_unlike)) { StateStore.setLike(m, false); reload() }
        else add(getString(R.string.menu_like)) { StateStore.setLike(m, true); reload() }
        add(getString(R.string.menu_watched)) {
            MovieDialogs.askLiked(this, m) { StateStore.remove(m, "watched", it); toast("Отметил просмотренным: «${m.title}»"); reload() }
        }
        if (StateStore.isFavorite(m)) add(getString(R.string.menu_fav_remove)) { StateStore.setFavorite(m, false); reload() }
        else add(getString(R.string.menu_fav_add)) { StateStore.setFavorite(m, true); toast("Добавил в избранное ♥"); reload() }
        MovieDialogs.menu(this, MovieDialogs.title(m), items)
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

    companion object {
        private const val COLUMNS = 6
    }
}
