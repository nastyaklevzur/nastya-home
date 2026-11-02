package ru.nastya.home

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.util.Locale

/**
 * Экран «О фильме» — открывается по OK на карточке. «Смотреть» запускает LAMPA и ставит «Начат просмотр».
 * Закрепить, избранное и «Нравится» применяются сразу (состояние общее),
 * «Просмотрен» и «Не буду смотреть» возвращают действие главному экрану, чтобы там сработали вопрос про оценку и плашка «Вернуть».
 */
class DetailsActivity : AppCompatActivity() {

    companion object {
        const val RESULT_KEY = "key"
        const val RESULT_ACTION = "action"
        private const val X_FROM_MAIN = "fromMain"

        fun intent(ctx: Context, m: Movie, fromMain: Boolean = false): Intent = Intent(ctx, DetailsActivity::class.java)
            .putExtra("title", m.title)
            .putExtra("original", m.originalTitle)
            .putExtra("year", m.year ?: 0)
            .putExtra("rating", m.rating ?: -1.0)
            .putExtra("note", m.note)
            .putExtra("poster", m.posterUrl)
            .putExtra("posterPath", m.posterPath)
            .putExtra("posterFile", m.posterFileName)
            .putExtra("query", m.searchQuery)
            .putExtra("search", m.search)
            .putExtra("tmdb", m.tmdbId ?: 0L)
            .putExtra("imdb", m.imdbRating ?: -1.0)
            .putExtra("kp", m.kpRating ?: -1.0)
            .putExtra("details", detailsLine(m))
            .putStringArrayListExtra("awards", ArrayList(m.awards.mapNotNull { it.label }))
            .putExtra("whyYou", m.whyYou)
            .putExtra("critics", m.critics)
            .putExtra("overview", m.overview)
            .putStringArrayListExtra("reviews", ArrayList(m.reviews.map { r ->
                val mark = when (r.type) { "positive" -> "👍 "; "negative" -> "👎 "; else -> "" }
                "$mark${r.author}: «${r.text}»"
            }))
            .putExtra(X_FROM_MAIN, fromMain)

        private fun detailsLine(m: Movie): String = listOfNotNull(
            m.year?.toString(),
            m.runtime?.let { "$it мин" },
            m.country,
            m.genres.takeIf { it.isNotEmpty() }?.joinToString(", "),
        ).joinToString(" · ")
    }

    private lateinit var movie: Movie

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        StateStore.init(this)
        setContentView(R.layout.activity_details)
        val i = intent
        val tmdb = i.getLongExtra("tmdb", 0L).takeIf { it > 0 }
        val year = i.getIntExtra("year", 0).takeIf { it > 0 }
        val rating = i.getDoubleExtra("rating", -1.0).takeIf { it >= 0 }
        movie = Movie(
            title = i.getStringExtra("title") ?: "",
            originalTitle = i.getStringExtra("original"),
            year = year, rating = rating,
            note = i.getStringExtra("note") ?: "",
            posterUrl = i.getStringExtra("poster"),
            kinopoiskId = null, tmdbId = tmdb,
            search = i.getStringExtra("search"),
            posterPath = i.getStringExtra("posterPath"),
        )

        text(R.id.title, movie.title)
        text(R.id.original, movie.originalTitle?.takeIf { it != movie.title })
        val details = i.getStringExtra("details").orEmpty()
        text(R.id.meta, details.ifEmpty { movie.metaLine })
        val imdb = i.getDoubleExtra("imdb", -1.0).takeIf { it >= 0 }
        val kp = i.getDoubleExtra("kp", -1.0).takeIf { it >= 0 }
        text(
            R.id.ratings,
            listOfNotNull(
                imdb?.let { "IMDb " + String.format(Locale.US, "%.1f", it) },
                kp?.let { "Кинопоиск " + String.format(Locale.US, "%.1f", it) },
            ).joinToString("   ·   ").takeIf { it.isNotEmpty() },
        )
        text(R.id.awards, i.getStringArrayListExtra("awards")?.joinToString("\n") { "🏆 $it" }?.takeIf { it.isNotEmpty() })
        text(R.id.note, movie.note)
        text(R.id.overview, i.getStringExtra("overview"))
        text(R.id.reviews, i.getStringArrayListExtra("reviews")?.takeIf { it.isNotEmpty() }?.joinToString("\n\n", prefix = "Отзывы на Кинопоиске:\n"))
        text(R.id.whyYou, i.getStringExtra("whyYou")?.let { "Почему тебе: $it" })
        text(R.id.critics, i.getStringExtra("critics")?.let { "Критики: $it" })
        Posters.load(findViewById(R.id.poster), i.getStringExtra("poster"), i.getStringExtra("posterFile"))

        val btnLampa = findViewById<Button>(R.id.btnLampa)
        btnLampa.setOnClickListener {
            if (Launcher.openMovieInLampa(this, movie) || Launcher.launchPackage(this, Launcher.LAMPA)) {
                WatchStatus.markOpened(this, movie)
            } else {
                Toast.makeText(this, "LAMPA не установлена", Toast.LENGTH_SHORT).show()
            }
        }
        fromMain = i.getBooleanExtra(X_FROM_MAIN, false)
        btnLampa.requestFocus()
    }

    private var fromMain = false

    /** Menu на экране фильма — те же действия, что в меню карточки. */
    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_MENU || keyCode == KeyEvent.KEYCODE_INFO) {
            showMenu()
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    private fun showMenu() {
        val m = movie
        val items = ArrayList<MovieDialogs.Item>()
        fun add(label: String, action: () -> Unit) { items.add(MovieDialogs.Item(label, action)) }
        if (StateStore.isPinned(m)) add(getString(R.string.menu_unpin)) { StateStore.unpin(m) }
        else add(getString(R.string.menu_pin)) {
            if (!StateStore.pin(m)) Toast.makeText(this, "Можно закрепить не больше трёх. Открепи что-нибудь", Toast.LENGTH_SHORT).show()
        }
        if (StateStore.isFavorite(m)) add(getString(R.string.menu_fav_remove)) { StateStore.setFavorite(m, false) }
        else add(getString(R.string.menu_fav_add)) { StateStore.setFavorite(m, true) }
        if (StateStore.isLiked(m)) add(getString(R.string.menu_unlike)) { StateStore.setLike(m, false) }
        else add(getString(R.string.menu_like)) { StateStore.setLike(m, true) }
        add(getString(R.string.menu_dislike)) { removeAndClose("skip", false) }
        add(getString(R.string.menu_watched)) {
            if (StateStore.isLiked(m)) removeAndClose("watched", true) else MovieDialogs.askLiked(this, m) { removeAndClose("watched", it) }
        }
        add(getString(R.string.menu_skip)) { removeAndClose("skip", null) }
        if (StateStore.isStarted(m)) add(getString(R.string.menu_reset)) { StateStore.setStarted(m, false) }
        MovieDialogs.menu(this, MovieDialogs.title(m), items)
    }

    /** «Просмотрен» / «Не буду смотреть»: с главного экрана — отдаём туда (там замена карточки и «Вернуть»), иначе применяем сами. */
    private fun removeAndClose(reason: String, liked: Boolean?) {
        if (fromMain) {
            setResult(RESULT_OK, Intent().putExtra(RESULT_KEY, movie.key).putExtra(RESULT_ACTION, reason).putExtra("liked", liked ?: false).putExtra("hasLiked", liked != null))
        } else {
            StateStore.remove(movie, reason, liked)
        }
        finish()
    }

    private fun text(id: Int, value: String?) {
        val v = findViewById<TextView>(id)
        if (value.isNullOrBlank()) {
            v.visibility = View.GONE
        } else {
            v.text = value
            v.visibility = View.VISIBLE
        }
    }
}
