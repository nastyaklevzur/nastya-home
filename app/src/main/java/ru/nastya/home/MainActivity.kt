package ru.nastya.home

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.animation.AnimationUtils
import android.widget.FrameLayout
import android.widget.ScrollView
import android.widget.TextSwitcher
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.leanback.widget.HorizontalGridView
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var rootFrame: FrameLayout
    private lateinit var scroll: ScrollView
    private lateinit var tvHello: TextView
    private lateinit var tvGreeting: TextView
    private lateinit var tvSection: TextView
    private lateinit var tvUpdated: TextView
    private lateinit var tvClock: TextView
    private lateinit var tvDate: TextView
    private lateinit var tvWeatherIcon: TextView
    private lateinit var tvWeatherTemp: TextView
    private lateinit var tvWeatherDesc: TextView
    private lateinit var tvNewsLabel: TextView
    private lateinit var newsSwitcher: TextSwitcher
    private lateinit var tvCams: TextView
    private lateinit var rvMovies: HorizontalGridView
    private lateinit var rvCams: HorizontalGridView
    private lateinit var rvApps: HorizontalGridView

    private lateinit var movieAdapter: MovieAdapter
    private lateinit var camAdapter: CamAdapter
    private lateinit var appAdapter: AppAdapter

    private val handler = Handler(Looper.getMainLooper())
    private val executor = Executors.newFixedThreadPool(2)
    private val ru = Locale("ru")

    private val fetching = HashSet<String>()
    private var current: MoviesData? = null
    private var news: List<String> = emptyList()
    private var newsIndex = 0
    private var splash: Splash? = null

    private val clockTick = object : Runnable {
        override fun run() {
            updateClockAndGreeting()
            handler.postDelayed(this, 20_000L)
        }
    }

    private val newsTick = object : Runnable {
        override fun run() {
            showNextNews()
            handler.postDelayed(this, NEWS_INTERVAL_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        rootFrame = findViewById(R.id.rootFrame)
        scroll = findViewById(R.id.scroll)
        tvHello = findViewById(R.id.tvHello)
        tvGreeting = findViewById(R.id.tvGreeting)
        tvSection = findViewById(R.id.tvSection)
        tvUpdated = findViewById(R.id.tvUpdated)
        tvClock = findViewById(R.id.tvClock)
        tvDate = findViewById(R.id.tvDate)
        tvWeatherIcon = findViewById(R.id.tvWeatherIcon)
        tvWeatherTemp = findViewById(R.id.tvWeatherTemp)
        tvWeatherDesc = findViewById(R.id.tvWeatherDesc)
        tvNewsLabel = findViewById(R.id.tvNewsLabel)
        newsSwitcher = findViewById(R.id.newsSwitcher)
        tvCams = findViewById(R.id.tvCams)
        rvMovies = findViewById(R.id.rvMovies)
        rvCams = findViewById(R.id.rvCams)
        rvApps = findViewById(R.id.rvApps)

        movieAdapter = MovieAdapter(onClick = ::openMovie, onMenu = ::showMovieMenu)
        camAdapter = CamAdapter(onClick = ::openCam)
        appAdapter = AppAdapter(onClick = ::onAppClick)
        setupRow(rvMovies, 12)
        setupRow(rvCams, 14)
        setupRow(rvApps, 10)
        rvMovies.adapter = movieAdapter
        rvCams.adapter = camAdapter
        rvApps.adapter = appAdapter

        setupNewsSwitcher()
        updateClockAndGreeting()
        show(MoviesRepository.loadLocal(this))
        CamsRepository.loadLocal(this)?.let(::showCams)
        showWeather(WeatherRepository.cached(this))
        showNews(NewsRepository.cached(this))
        rvMovies.post { rvMovies.requestFocus() }

        if (shouldShowSplash()) startSplash()
    }

    // ----- Заставка -----

    private fun shouldShowSplash(): Boolean =
        System.currentTimeMillis() - lastShownAt > SPLASH_AFTER_MS

    private fun startSplash() {
        val view = LayoutInflater.from(this).inflate(R.layout.view_splash, rootFrame, false)
        rootFrame.addView(view)
        splash = Splash(view, scroll) { splash = null }.also { it.start() }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val s = splash
        if (s != null && s.isShowing) {
            if (event.action == KeyEvent.ACTION_DOWN) s.dismiss()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    // ----- Жизненный цикл -----

    private fun setupRow(row: HorizontalGridView, spacingDp: Int) {
        row.setNumRows(1)
        row.setHorizontalSpacing((spacingDp * resources.displayMetrics.density).toInt())
        row.setItemViewCacheSize(12)
    }

    override fun onResume() {
        super.onResume()
        handler.removeCallbacks(clockTick)
        handler.post(clockTick)
        handler.removeCallbacks(newsTick)
        handler.postDelayed(newsTick, NEWS_INTERVAL_MS)
        appAdapter.submit(AppCatalog.shortcuts(this))
        movieAdapter.refreshStatuses()
        refreshAll(force = false)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(clockTick)
        handler.removeCallbacks(newsTick)
    }

    override fun onStop() {
        super.onStop()
        lastShownAt = System.currentTimeMillis()
    }

    override fun onDestroy() {
        super.onDestroy()
        executor.shutdownNow()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    @Suppress("DEPRECATION")
    private fun hideSystemBars() {
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            or View.SYSTEM_UI_FLAG_FULLSCREEN
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        goHome()
        if (splash == null && shouldShowSplash()) startSplash()
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

    // ----- Шапка -----

    private fun updateClockAndGreeting() {
        val now = Calendar.getInstance()
        val g = Greetings.forHour(now.get(Calendar.HOUR_OF_DAY))
        tvHello.text = g.hello
        tvGreeting.text = g.text
        tvClock.text = SimpleDateFormat("HH:mm", ru).format(now.time)
        tvDate.text = SimpleDateFormat("EEEE, d MMMM", ru).format(now.time)
            .replaceFirstChar { if (it.isLowerCase()) it.titlecase(ru) else it.toString() }
    }

    private fun showWeather(w: Weather?) {
        if (w == null) {
            tvWeatherIcon.text = "🌡️"
            tvWeatherTemp.text = "—"
            tvWeatherDesc.text = getString(R.string.weather_loading)
            return
        }
        tvWeatherIcon.text = w.emoji
        tvWeatherTemp.text = w.tempText
        tvWeatherDesc.text = w.detailsText
    }

    // ----- Новости -----

    private fun setupNewsSwitcher() {
        newsSwitcher.setFactory {
            TextView(this).apply {
                setTextColor(getColor(R.color.cream))
                textSize = 14f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT,
                )
            }
        }
        newsSwitcher.inAnimation = AnimationUtils.loadAnimation(this, R.anim.news_in)
        newsSwitcher.outAnimation = AnimationUtils.loadAnimation(this, R.anim.news_out)
        tvNewsLabel.text = "📰 " + NewsRepository.FEEDS.first().name
    }

    private fun showNews(list: List<String>) {
        if (list.isEmpty()) {
            if (news.isEmpty()) newsSwitcher.setCurrentText(getString(R.string.news_loading))
            return
        }
        val changed = list != news
        news = list
        if (changed) {
            newsIndex = 0
            newsSwitcher.setCurrentText(news[0])
        }
    }

    private fun showNextNews() {
        if (news.size < 2) return
        newsIndex = (newsIndex + 1) % news.size
        newsSwitcher.setText(news[newsIndex])
    }

    // ----- Данные -----

    private fun show(data: MoviesData) {
        current = data
        tvSection.text = data.title
        tvUpdated.text = data.updated?.let { "обновлено " + formatDate(it) } ?: ""
        movieAdapter.submit(data.movies)
    }

    private fun showCams(data: CamsData) {
        tvCams.text = data.title
        camAdapter.submit(data.cams)
        val visible = if (camAdapter.itemCount == 0) View.GONE else View.VISIBLE
        tvCams.visibility = visible
        rvCams.visibility = visible
    }

    private fun formatDate(iso: String): String = try {
        val date = SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(iso)
        if (date != null) SimpleDateFormat("d MMMM", ru).format(date) else iso
    } catch (e: Exception) {
        iso
    }

    private fun refreshAll(force: Boolean) {
        val now = System.currentTimeMillis()
        if (force || now - MoviesRepository.lastFetchAt(this) > HOUR_MS) {
            fetch("movies", { MoviesRepository.fetchRemote(this) }) { data ->
                if (data != null) {
                    show(data)
                    if (force) toast("Подборка обновлена")
                } else if (force) {
                    toast("Не получилось обновить. Проверь интернет")
                }
            }
        }
        if (force || now - CamsRepository.lastFetchAt(this) > HOUR_MS) {
            fetch("cams", { CamsRepository.fetchRemote(this) }) { data -> if (data != null) showCams(data) }
        }
        val w = WeatherRepository.cached(this)
        if (force || w == null || now - w.fetchedAt > WEATHER_INTERVAL_MS) {
            fetch("weather", { WeatherRepository.fetch(this) }) { data -> if (data != null) showWeather(data) }
        }
        if (force || now - NewsRepository.lastFetchAt(this) > NEWS_REFRESH_MS) {
            fetch("news", { NewsRepository.fetch(this) }) { data -> if (data != null) showNews(data) }
        }
    }

    private fun <T> fetch(key: String, work: () -> T?, onResult: (T?) -> Unit) {
        synchronized(fetching) { if (!fetching.add(key)) return }
        executor.execute {
            val result = try {
                work()
            } catch (e: Exception) {
                null
            } finally {
                synchronized(fetching) { fetching.remove(key) }
            }
            handler.post {
                if (isFinishing || isDestroyed) return@post
                onResult(result)
            }
        }
    }

    // ----- Действия -----

    private fun openMovie(m: Movie) {
        if (Launcher.searchInLampa(this, m.searchQuery)) {
            if (WatchStatus.markOpened(this, m)) movieAdapter.refreshStatuses()
        } else {
            showDetails(m)
        }
    }

    private fun showDetails(m: Movie) {
        startActivity(DetailsActivity.intent(this, m))
    }

    private fun showMovieMenu(m: Movie) {
        val status = WatchStatus.get(this, m)
        val items = arrayOf(
            getString(R.string.menu_about),
            getString(R.string.menu_started) + if (status == WatchStatus.Status.STARTED) "  ✓" else "",
            getString(R.string.menu_watched) + if (status == WatchStatus.Status.WATCHED) "  ✓" else "",
            getString(R.string.menu_reset),
        )
        AlertDialog.Builder(this)
            .setTitle(m.title + (m.year?.let { " ($it)" } ?: ""))
            .setItems(items) { _, which ->
                when (which) {
                    0 -> showDetails(m)
                    1 -> setStatus(m, WatchStatus.Status.STARTED)
                    2 -> setStatus(m, WatchStatus.Status.WATCHED)
                    3 -> setStatus(m, WatchStatus.Status.NONE)
                }
            }
            .show()
    }

    private fun setStatus(m: Movie, s: WatchStatus.Status) {
        WatchStatus.set(this, m, s)
        movieAdapter.refreshStatuses()
        toast(
            when (s) {
                WatchStatus.Status.STARTED -> "Отметил: начала смотреть «${m.title}»"
                WatchStatus.Status.WATCHED -> "Отметил: «${m.title}» просмотрен"
                WatchStatus.Status.NONE -> "Метка снята"
            }
        )
    }

    private fun openCam(c: Cam) {
        startActivity(PlayerActivity.intent(this, c))
    }

    private fun onAppClick(s: AppShortcut) {
        if (s.id == AppCatalog.REFRESH) {
            toast("Обновляю…")
            refreshAll(force = true)
        } else if (!Launcher.launchPackage(this, s.id)) {
            toast("Не удалось открыть ${s.label}")
        }
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

    companion object {
        private const val HOUR_MS = 60L * 60L * 1000L
        private const val WEATHER_INTERVAL_MS = 30L * 60L * 1000L
        private const val NEWS_REFRESH_MS = 20L * 60L * 1000L
        private const val NEWS_INTERVAL_MS = 7_000L

        /** Заставку показываем при холодном старте и если лаунчер не видели дольше этого времени. */
        private const val SPLASH_AFTER_MS = 10L * 60L * 1000L
        private var lastShownAt = 0L
    }
}
