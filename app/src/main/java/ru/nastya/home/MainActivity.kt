package ru.nastya.home

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.leanback.widget.HorizontalGridView
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var scroll: ScrollView
    private lateinit var tvHello: TextView
    private lateinit var tvGreeting: TextView
    private lateinit var tvSection: TextView
    private lateinit var tvUpdated: TextView
    private lateinit var tvClock: TextView
    private lateinit var tvDate: TextView
    private lateinit var rvMovies: HorizontalGridView
    private lateinit var rvApps: HorizontalGridView

    private lateinit var movieAdapter: MovieAdapter
    private lateinit var appAdapter: AppAdapter

    private val handler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    private val ru = Locale("ru")

    @Volatile
    private var fetching = false
    private var current: MoviesData? = null

    private val clockTick = object : Runnable {
        override fun run() {
            updateClockAndGreeting()
            handler.postDelayed(this, 20_000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        scroll = findViewById(R.id.scroll)
        tvHello = findViewById(R.id.tvHello)
        tvGreeting = findViewById(R.id.tvGreeting)
        tvSection = findViewById(R.id.tvSection)
        tvUpdated = findViewById(R.id.tvUpdated)
        tvClock = findViewById(R.id.tvClock)
        tvDate = findViewById(R.id.tvDate)
        rvMovies = findViewById(R.id.rvMovies)
        rvApps = findViewById(R.id.rvApps)

        movieAdapter = MovieAdapter(onClick = ::openMovie, onLongClick = ::showDetails)
        appAdapter = AppAdapter(onClick = ::onAppClick)
        setupRow(rvMovies, 14)
        setupRow(rvApps, 12)
        rvMovies.adapter = movieAdapter
        rvApps.adapter = appAdapter

        updateClockAndGreeting()
        show(MoviesRepository.loadLocal(this))
        rvMovies.post { rvMovies.requestFocus() }
    }

    private fun setupRow(row: HorizontalGridView, spacingDp: Int) {
        row.setNumRows(1)
        row.setHorizontalSpacing((spacingDp * resources.displayMetrics.density).toInt())
        row.setItemViewCacheSize(12)
    }

    override fun onResume() {
        super.onResume()
        handler.removeCallbacks(clockTick)
        handler.post(clockTick)
        appAdapter.submit(AppCatalog.shortcuts(this))
        maybeFetch(force = false)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(clockTick)
    }

    override fun onDestroy() {
        super.onDestroy()
        executor.shutdownNow()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        goHome()
    }

    @Deprecated("Домашний экран не закрывается кнопкой «Назад»")
    override fun onBackPressed() {
        goHome()
    }

    private fun goHome() {
        scroll.smoothScrollTo(0, 0)
        rvMovies.selectedPosition = 0
        rvMovies.requestFocus()
    }

    private fun updateClockAndGreeting() {
        val now = Calendar.getInstance()
        val g = Greetings.forHour(now.get(Calendar.HOUR_OF_DAY))
        tvHello.text = g.hello
        tvGreeting.text = g.text
        tvClock.text = SimpleDateFormat("HH:mm", ru).format(now.time)
        tvDate.text = SimpleDateFormat("EEEE, d MMMM", ru).format(now.time)
            .replaceFirstChar { if (it.isLowerCase()) it.titlecase(ru) else it.toString() }
    }

    private fun show(data: MoviesData) {
        current = data
        tvSection.text = data.title
        tvUpdated.text = data.updated?.let { "обновлено " + formatDate(it) } ?: ""
        movieAdapter.submit(data.movies)
    }

    private fun formatDate(iso: String): String = try {
        val date = SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(iso)
        if (date != null) SimpleDateFormat("d MMMM", ru).format(date) else iso
    } catch (e: Exception) {
        iso
    }

    private fun maybeFetch(force: Boolean) {
        if (fetching) return
        val stale = System.currentTimeMillis() - MoviesRepository.lastFetchAt(this) > REFRESH_INTERVAL_MS
        if (!force && !stale) return
        fetching = true
        executor.execute {
            val data = try {
                MoviesRepository.fetchRemote(this)
            } finally {
                fetching = false
            }
            handler.post {
                if (isFinishing || isDestroyed) return@post
                if (data != null) {
                    show(data)
                    if (force) toast("Подборка обновлена")
                } else if (force) {
                    toast("Не получилось обновить. Проверь интернет")
                }
            }
        }
    }

    private fun openMovie(m: Movie) {
        if (!Launcher.searchInLampa(this, m.searchQuery)) showDetails(m)
    }

    private fun showDetails(m: Movie) {
        startActivity(DetailsActivity.intent(this, m))
    }

    private fun onAppClick(s: AppShortcut) {
        if (s.id == AppCatalog.REFRESH) {
            toast("Обновляю подборку…")
            maybeFetch(force = true)
        } else if (!Launcher.launchPackage(this, s.id)) {
            toast("Не удалось открыть ${s.label}")
        }
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

    companion object {
        private const val REFRESH_INTERVAL_MS = 60L * 60L * 1000L
    }
}
