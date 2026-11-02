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
import androidx.activity.result.contract.ActivityResultContracts
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
    private lateinit var tvFav: TextView
    private lateinit var undoBar: TextView
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
    /** Ряд «топ-10», собранный из пула и состояния (см. RowBuilder). */
    private var row: List<Movie> = emptyList()
    /** Новый пул применяем к ряду только при старте, по кнопке «Обновить» и после долгого отсутствия (SC-8). */
    private var applyNextRemote = true
    /** Версия состояния, по которой собран ряд; если она изменилась (закрепили на другом экране), ряд пересобираем. */
    private var rowSeq = -1
    private var undo: Undo? = null
    private val undoExpire = Runnable { clearUndo() }

    private class Undo(val movie: Movie, val index: Int, val rowBefore: List<Movie>, val wasPinned: Boolean, val wasStarted: Boolean)

    private val detailsLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val data = r.data ?: return@registerForActivityResult
        val key = data.getStringExtra(DetailsActivity.RESULT_KEY) ?: return@registerForActivityResult
        val m = row.firstOrNull { it.key == key } ?: current?.movies?.firstOrNull { it.key == key } ?: return@registerForActivityResult
        when (data.getStringExtra(DetailsActivity.RESULT_ACTION)) {
            "watched" -> MovieDialogs.askLiked(this, m) { removeMovie(m, "watched", it) }
            "skip" -> removeMovie(m, "skip", null)
        }
    }
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
        StateStore.init(this)

        rootFrame = findViewById(R.id.rootFrame)
        scroll = findViewById(R.id.scroll)
        tvHello = findViewById(R.id.tvHello)
        tvGreeting = findViewById(R.id.tvGreeting)
        tvSection = findViewById(R.id.tvSection)
        tvUpdated = findViewById(R.id.tvUpdated)
        tvFav = findViewById(R.id.tvFav)
        undoBar = findViewById(R.id.undoBar)
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
        tvFav.setOnClickListener { startActivity(Intent(this, FavoritesActivity::class.java)) }
        updateFav()
        updateClockAndGreeting()
        val local = MoviesRepository.loadLocal(this)
        StateStore.migrateV1(local.movies)
        show(local, apply = true)
        CamsRepository.loadLocal(this)?.let(::showCams)
        showWeather(WeatherRepository.cached(this))
        showNews(NewsRepository.cached(this))
        rvMovies.post { rvMovies.requestFocus() }

        if (shouldShowSplash()) startSplash() else SplashBackdrops.prefetchNext(this)
    }

    // ----- Заставка -----

    private fun shouldShowSplash(): Boolean =
        System.currentTimeMillis() - lastShownAt > SPLASH_AFTER_MS

    private fun startSplash() {
        val view = LayoutInflater.from(this).inflate(R.layout.view_splash, rootFrame, false)
        rootFrame.addView(view)
        val backdrop = SplashBackdrops.peek(this)
        SplashBackdrops.advance(this)
        splash = Splash(view, scroll) {
            splash = null
            SplashBackdrops.prefetchNext(this)
        }.also {
            it.start()
            it.setBackdrop(backdrop)
        }
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
        if (lastShownAt != 0L && System.currentTimeMillis() - lastShownAt > SPLASH_AFTER_MS) applyNextRemote = true
        handler.removeCallbacks(clockTick)
        handler.post(clockTick)
        handler.removeCallbacks(newsTick)
        handler.postDelayed(newsTick, NEWS_INTERVAL_MS)
        appAdapter.submit(AppCatalog.shortcuts(this))
        // Состояние могли изменить на экране «Избранное» или в «О фильме».
        val data = current
        if (data != null && StateStore.snapshot().seq != rowSeq) {
            applyRow(RowBuilder.build(data, StateStore.snapshot(), row.map { it.key }), null)
        }
        movieAdapter.refreshStatuses()
        updateFav()
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
        GithubSync.flush()
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(undoExpire)
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
        if (undo != null) performUndo() else goHome()
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

    private fun show(data: MoviesData, apply: Boolean) {
        current = data
        tvSection.text = data.title
        tvUpdated.text = data.updated?.let { "обновлено " + formatDate(it) } ?: ""
        if (apply || row.isEmpty()) rebuildRow(data)
    }

    /** Полный пересчёт ряда: закреплённые, затем прежние карточки, затем лучшие из пула (RowBuilder). */
    private fun rebuildRow(data: MoviesData) {
        // Прежний порядок сохраняем только для того же пула; новый movies.json (другая дата) раскладывается по рангу.
        val prefs = getSharedPreferences("row", MODE_PRIVATE)
        val signature = data.movies.joinToString(",") { "${it.key}:${it.rank}" }.hashCode().toString()
        val samePool = prefs.getString("pool", null) == signature
        val keys = if (!samePool) emptyList() else if (row.isEmpty()) loadRowKeys() else row.map { it.key }
        applyRow(RowBuilder.build(data, StateStore.snapshot(), keys), null)
        prefs.edit().putString("pool", signature).apply()
    }

    /** Показать новый ряд; фокус остаётся на [focusKey] или на той же позиции. */
    private fun applyRow(newRow: List<Movie>, focusKey: String?) {
        val selected = rvMovies.selectedPosition
        val hadFocus = rvMovies.hasFocus()
        row = newRow
        rowSeq = StateStore.snapshot().seq
        saveRowKeys()
        movieAdapter.submit(row)
        val target = focusKey?.let { k -> row.indexOfFirst { it.key == k } }?.takeIf { it >= 0 }
            ?: selected.coerceIn(0, (row.size - 1).coerceAtLeast(0))
        rvMovies.selectedPosition = target
        if (hadFocus) rvMovies.requestFocus()
        updateFav()
    }

    private fun updateFav() {
        val n = StateStore.snapshot().favorites.size
        tvFav.text = if (n > 0) "♥ $n" else "♥"
    }

    private fun loadRowKeys(): List<String> =
        getSharedPreferences("row", MODE_PRIVATE).getString("keys", "")!!.split('\n').filter { it.isNotEmpty() }

    private fun saveRowKeys() {
        getSharedPreferences("row", MODE_PRIVATE).edit().putString("keys", row.joinToString("\n") { it.key }).apply()
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
                    show(data, apply = force || applyNextRemote)
                    applyNextRemote = false
                    if (force) toast("Подборка обновлена")
                } else if (force) {
                    toast("Не получилось обновить. Проверь интернет")
                }
            }
        }
        // Состояние: забрать из репозитория на свежей установке и отправить то, что не ушло (офлайн, ошибка).
        executor.execute {
            val restored = GithubSync.restoreIfEmpty()
            GithubSync.flush()
            if (restored) handler.post { if (!isFinishing && !isDestroyed) current?.let(::rebuildRow) }
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

    /** OK на карточке открывает «О фильме»; оттуда кнопка «Смотреть» запускает LAMPA. */
    private fun openMovie(m: Movie) = showDetails(m)

    private fun showDetails(m: Movie) {
        detailsLauncher.launch(DetailsActivity.intent(this, m, fromMain = true))
    }

    private fun showMovieMenu(m: Movie) {
        val items = ArrayList<MovieDialogs.Item>()
        fun add(label: String, action: () -> Unit) { items.add(MovieDialogs.Item(label, action)) }
        add(getString(R.string.menu_about)) { showDetails(m) }
        if (StateStore.isPinned(m)) add(getString(R.string.menu_unpin)) { unpinMovie(m) }
        else add(getString(R.string.menu_pin)) { pinMovie(m) }
        if (StateStore.isFavorite(m)) add(getString(R.string.menu_fav_remove)) { toggleFavorite(m) }
        else add(getString(R.string.menu_fav_add)) { toggleFavorite(m) }
        if (StateStore.isLiked(m)) add(getString(R.string.menu_unlike)) { toggleLike(m) }
        else add(getString(R.string.menu_like)) { toggleLike(m) }
        add(getString(R.string.menu_dislike)) { removeMovie(m, "skip", false) }
        add(getString(R.string.menu_watched)) {
            if (StateStore.isLiked(m)) removeMovie(m, "watched", true) else MovieDialogs.askLiked(this, m) { removeMovie(m, "watched", it) }
        }
        add(getString(R.string.menu_skip)) { removeMovie(m, "skip", null) }
        if (StateStore.isStarted(m)) add(getString(R.string.menu_reset)) { resetMark(m) }
        MovieDialogs.menu(this, MovieDialogs.title(m), items)
    }

    private fun resetMark(m: Movie) {
        WatchStatus.set(this, m, WatchStatus.Status.NONE)
        rowSeq = StateStore.snapshot().seq
        movieAdapter.refreshStatuses()
        toast("Метка снята")
    }

    private fun pinMovie(m: Movie) {
        val data = current ?: return
        if (!StateStore.pin(m)) {
            toast("Можно закрепить не больше трёх. Открепи что-нибудь")
            return
        }
        toast("Закрепил «${m.title}»")
        applyRow(RowBuilder.build(data, StateStore.snapshot(), row.map { it.key }), m.key)
    }

    private fun unpinMovie(m: Movie) {
        val data = current ?: return
        StateStore.unpin(m)
        toast("Открепил «${m.title}»")
        applyRow(RowBuilder.unpin(data, StateStore.snapshot(), row, m.key), m.key)
    }

    private fun toggleLike(m: Movie) {
        val on = !StateStore.isLiked(m)
        StateStore.setLike(m, on)
        rowSeq = StateStore.snapshot().seq
        movieAdapter.refreshStatuses()
        toast(if (on) "Запомнил: «${m.title}» нравится 👍" else "Лайк снят")
    }

    private fun toggleFavorite(m: Movie) {
        val on = !StateStore.isFavorite(m)
        StateStore.setFavorite(m, on)
        rowSeq = StateStore.snapshot().seq
        movieAdapter.refreshStatuses()
        updateFav()
        toast(if (on) "Добавил в избранное ♥" else "Убрал из избранного")
    }

    /** «Просмотрен» / «Не буду смотреть»: карточка исчезает, на её место встаёт следующая из пула; 6 секунд можно вернуть. */
    private fun removeMovie(m: Movie, reason: String, liked: Boolean?) {
        val data = current ?: return
        val before = row
        val index = row.indexOfFirst { it.key == m.key }
        val wasPinned = StateStore.isPinned(m)
        val wasStarted = StateStore.isStarted(m)
        StateStore.remove(m, reason, liked)
        val after = if (index >= 0) RowBuilder.replaceAt(data, StateStore.snapshot(), row, index) else row
        applyRow(after, null)
        showUndo(
            Undo(m, index, before, wasPinned, wasStarted),
            (if (reason == "watched") "Просмотрен" else if (liked == false) "Не понравился" else "Убрал") + ": «${m.title}». Назад — вернуть",
        )
    }

    private fun showUndo(u: Undo, text: String) {
        handler.removeCallbacks(undoExpire)
        undo = u
        undoBar.text = text
        undoBar.visibility = View.VISIBLE
        handler.postDelayed(undoExpire, UNDO_MS)
    }

    private fun clearUndo() {
        handler.removeCallbacks(undoExpire)
        undo = null
        undoBar.visibility = View.GONE
    }

    private fun performUndo() {
        val u = undo ?: return
        clearUndo()
        StateStore.restore(u.movie.key, u.movie.title)
        if (u.wasPinned) StateStore.pin(u.movie)
        if (u.wasStarted) StateStore.setStarted(u.movie, true)
        applyRow(u.rowBefore, u.movie.key)
        movieAdapter.refreshStatuses()
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
        private const val UNDO_MS = 6_000L

        /** Заставку показываем при холодном старте и если лаунчер не видели дольше этого времени. */
        private const val SPLASH_AFTER_MS = 10L * 60L * 1000L
        private var lastShownAt = 0L
    }
}
