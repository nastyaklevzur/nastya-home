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
import androidx.leanback.widget.VerticalGridView
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var rootFrame: FrameLayout
    private lateinit var scroll: ScrollView
    private lateinit var scrollMovies: ScrollView
    private lateinit var mainColumn: View
    private lateinit var tabs: Tabs
    private lateinit var tvFooter: TextView
    private lateinit var btnProfile: TextView
    private lateinit var btnToMovies: TextView
    private lateinit var btnToCams: TextView
    private lateinit var camsHeader: View
    private lateinit var tvSectionMovies: TextView
    private lateinit var tvUpdatedMovies: TextView
    private lateinit var tvFavorites: TextView
    private lateinit var tvAppsMovies: TextView
    private lateinit var tvHello: TextView
    private lateinit var tvGreeting: TextView
    private lateinit var tvSection: TextView
    private lateinit var tvUpdated: TextView
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
    private lateinit var rvMoviesAll: HorizontalGridView
    private lateinit var rvFavorites: HorizontalGridView
    private lateinit var tvContinueHome: TextView
    private lateinit var rvContinueHome: HorizontalGridView
    private lateinit var tvContinueMovies: TextView
    private lateinit var rvContinueMovies: HorizontalGridView
    private lateinit var continueHomeAdapter: MovieAdapter
    private lateinit var continueMoviesAdapter: MovieAdapter
    private var continueCaptions: Map<String, String> = emptyMap()
    private var continueVia: Map<String, String?> = emptyMap()
    private lateinit var rvAppsMovies: HorizontalGridView
    private lateinit var rvAppsSystem: HorizontalGridView
    private lateinit var gridCams: VerticalGridView

    /** Ряд топ-10 на «Главной» и тот же ряд на вкладке «Кино» — два адаптера над одним списком [row]. */
    private lateinit var movieAdapter: MovieAdapter
    private lateinit var movieAllAdapter: MovieAdapter
    private lateinit var favAdapter: MovieAdapter
    private lateinit var camAdapter: CamAdapter
    private lateinit var camGridAdapter: CamAdapter
    private lateinit var movieAppsAdapter: AppAdapter
    private lateinit var systemAppsAdapter: AppAdapter

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

    private class Undo(val movie: Movie, val index: Int, val rowBefore: List<Movie>, val wasPinned: Boolean, val startedVia: String?)

    private val detailsLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val data = r.data ?: return@registerForActivityResult
        val key = data.getStringExtra(DetailsActivity.RESULT_KEY) ?: return@registerForActivityResult
        val m = row.firstOrNull { it.key == key } ?: current?.movies?.firstOrNull { it.key == key } ?: return@registerForActivityResult
        val liked = if (data.getBooleanExtra("hasLiked", false)) data.getBooleanExtra("liked", false) else null
        when (data.getStringExtra(DetailsActivity.RESULT_ACTION)) {
            "watched" -> removeMovie(m, "watched", liked)
            "skip" -> removeMovie(m, "skip", liked)
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
        scrollMovies = findViewById(R.id.scrollMovies)
        mainColumn = findViewById(R.id.mainColumn)
        tvFooter = findViewById(R.id.tvFooter)
        btnProfile = findViewById(R.id.btnProfile)
        btnToMovies = findViewById(R.id.btnToMovies)
        btnToCams = findViewById(R.id.btnToCams)
        camsHeader = findViewById(R.id.camsHeader)
        tvSectionMovies = findViewById(R.id.tvSectionMovies)
        tvUpdatedMovies = findViewById(R.id.tvUpdatedMovies)
        tvFavorites = findViewById(R.id.tvFavorites)
        tvAppsMovies = findViewById(R.id.tvAppsMovies)
        tvHello = findViewById(R.id.tvHello)
        tvGreeting = findViewById(R.id.tvGreeting)
        tvSection = findViewById(R.id.tvSection)
        tvUpdated = findViewById(R.id.tvUpdated)
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
        rvMoviesAll = findViewById(R.id.rvMoviesAll)
        rvFavorites = findViewById(R.id.rvFavorites)
        tvContinueHome = findViewById(R.id.tvContinueHome)
        rvContinueHome = findViewById(R.id.rvContinueHome)
        tvContinueMovies = findViewById(R.id.tvContinueMovies)
        rvContinueMovies = findViewById(R.id.rvContinueMovies)
        rvAppsMovies = findViewById(R.id.rvAppsMovies)
        rvAppsSystem = findViewById(R.id.rvAppsSystem)
        gridCams = findViewById(R.id.gridCams)

        movieAdapter = MovieAdapter(onClick = ::openMovie, onMenu = ::showMovieMenu)
        movieAllAdapter = MovieAdapter(
            onClick = ::openMovie, onMenu = ::showMovieMenu,
            trailing = MovieAdapter.Trailing(getString(R.string.more_all)) { startActivity(Intent(this, RecommendationsActivity::class.java)) },
        )
        favAdapter = MovieAdapter(
            onClick = ::openMovie, onMenu = ::showMovieMenu,
            trailing = MovieAdapter.Trailing(getString(R.string.more_fav)) { startActivity(Intent(this, FavoritesActivity::class.java)) },
        )
        continueHomeAdapter = MovieAdapter(onClick = ::continueMovie, onMenu = ::continueMenu, caption = { m -> continueCaptions[m.key] })
        continueMoviesAdapter = MovieAdapter(onClick = ::continueMovie, onMenu = ::continueMenu, caption = { m -> continueCaptions[m.key] })
        camAdapter = CamAdapter(onClick = ::openCam)
        camGridAdapter = CamAdapter(onClick = ::openCam)
        movieAppsAdapter = AppAdapter(onClick = ::onAppClick)
        systemAppsAdapter = AppAdapter(onClick = ::onAppClick, layoutRes = R.layout.item_app_large)
        setupRow(rvMovies, 12)
        setupRow(rvMoviesAll, 12)
        setupRow(rvFavorites, 12)
        setupRow(rvContinueHome, 12)
        setupRow(rvContinueMovies, 12)
        setupRow(rvCams, 14)
        setupRow(rvAppsMovies, 10)
        setupRow(rvAppsSystem, 14)
        gridCams.setNumColumns(CAM_COLUMNS)
        gridCams.setHorizontalSpacing((8 * resources.displayMetrics.density).toInt())
        gridCams.setVerticalSpacing((12 * resources.displayMetrics.density).toInt())
        rvMovies.adapter = movieAdapter
        rvMoviesAll.adapter = movieAllAdapter
        rvFavorites.adapter = favAdapter
        rvContinueHome.adapter = continueHomeAdapter
        rvContinueMovies.adapter = continueMoviesAdapter
        rvCams.adapter = camAdapter
        gridCams.adapter = camGridAdapter
        rvAppsMovies.adapter = movieAppsAdapter
        rvAppsSystem.adapter = systemAppsAdapter

        setupTabs()
        tvFooter.text = getString(
            R.string.footer, getString(R.string.app_name), BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE,
            Calendar.getInstance().get(Calendar.YEAR), getString(R.string.credits_author),
        )
        btnProfile.setOnClickListener { Profile.showNameDialog(this) { updateClockAndGreeting() } }
        btnToMovies.setOnClickListener { tabs.selectAndFocusContent(TAB_MOVIES) }
        btnToCams.setOnClickListener { tabs.selectAndFocusContent(TAB_CAMS) }

        setupNewsSwitcher()
        updateClockAndGreeting()
        val local = MoviesRepository.loadLocal(this)
        StateStore.migrateV1(local.movies)
        show(local, apply = true)
        CamsRepository.loadLocal(this)?.let(::showCams)
        showWeather(WeatherRepository.cached(this))
        showNews(NewsRepository.cached(this))
        rvMovies.post { rvMovies.requestFocus() }

        if (shouldShowSplash()) startSplash() else {
            SplashBackdrops.prefetchNext(this)
            askNameOnce()
        }
    }

    private var nameAsked = false

    /** Первый запуск: спросить имя (один раз за запуск, не поверх заставки). */
    private fun askNameOnce() {
        if (nameAsked || splash != null || !Profile.shouldAsk(this)) return
        nameAsked = true
        Profile.showNameDialog(this) { updateClockAndGreeting() }
    }

    // ----- Заставка -----

    private fun shouldShowSplash(): Boolean =
        System.currentTimeMillis() - lastShownAt > SPLASH_AFTER_MS

    private fun startSplash() {
        val view = LayoutInflater.from(this).inflate(R.layout.view_splash, rootFrame, false)
        rootFrame.addView(view)
        val backdrop = SplashBackdrops.peek(this)
        SplashBackdrops.advance(this)
        splash = Splash(view, mainColumn) {
            splash = null
            SplashBackdrops.prefetchNext(this)
            askNameOnce()
        }.also {
            it.setName(Profile.name(this))
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
        // «Вверх» из верхнего ряда страницы ведёт на чип текущей вкладки, а не на соседнюю.
        if (event.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_DPAD_UP) {
            val f = currentFocus
            if (f != null && tabs.handleUp(f)) {
                pageScroll()?.smoothScrollTo(0, 0)
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    // ----- Вкладки -----

    private fun setupTabs() {
        tabs = Tabs(
            chips = listOf(R.id.tabHome, R.id.tabMovies, R.id.tabCams, R.id.tabApps).map { findViewById<View>(it) },
            pages = listOf<View>(scroll, scrollMovies, findViewById(R.id.pageCams), findViewById(R.id.pageApps)),
            // Куда уходит фокус со стрелкой вниз: топ-10, рекомендации, сетка камер, плитки приложений.
            entries = listOf<View>(rvMovies, rvMoviesAll, gridCams, rvAppsSystem),
        )
    }

    /** ScrollView текущей вкладки (у камер и приложений прокрутки нет). */
    private fun pageScroll(): ScrollView? = when (tabs.selected) {
        TAB_HOME -> scroll
        TAB_MOVIES -> scrollMovies
        else -> null
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
        showApps()
        // Состояние могли изменить на экране «Избранное» или в «О фильме».
        val data = current
        if (data != null && StateStore.snapshot().seq != rowSeq) {
            applyRow(RowBuilder.build(data, StateStore.snapshot(), row.map { it.key }), null)
        }
        refreshStatuses()
        refreshFavorites()
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
        when {
            undo != null -> performUndo()
            tabs.selected != TAB_HOME -> tabs.selectAndFocusChip(TAB_HOME)
            else -> goHome()
        }
    }

    /** Кнопка Home: вкладка «Главная», наверх, фокус на первую карточку топ-10. */
    private fun goHome() {
        tabs.select(TAB_HOME)
        scroll.smoothScrollTo(0, 0)
        rvMovies.selectedPosition = 0
        rvMovies.requestFocus()
    }

    private fun showApps() {
        movieAppsAdapter.submit(AppCatalog.movieApps(this))
        systemAppsAdapter.submit(AppCatalog.systemApps(this))
        val v = if (movieAppsAdapter.itemCount == 0) View.GONE else View.VISIBLE
        tvAppsMovies.visibility = v
        rvAppsMovies.visibility = v
    }

    private fun refreshStatuses() {
        movieAdapter.refreshStatuses()
        movieAllAdapter.refreshStatuses()
        favAdapter.refreshStatuses()
    }

    // ----- Шапка -----

    private fun updateClockAndGreeting() {
        val now = Calendar.getInstance()
        val g = Greetings.forHour(now.get(Calendar.HOUR_OF_DAY), Profile.name(this))
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
        val updatedText = data.updated?.let { "обновлено " + formatDate(it) } ?: ""
        tvUpdated.text = updatedText
        tvUpdatedMovies.text = updatedText
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
        row = newRow
        rowSeq = StateStore.snapshot().seq
        saveRowKeys()
        // Один и тот же ряд показывают «Главная» и «Кино»: у каждого свой выбор и свой фокус.
        for ((rv, adapter) in listOf(rvMovies to movieAdapter, rvMoviesAll to movieAllAdapter)) {
            val selected = rv.selectedPosition
            val hadFocus = rv.hasFocus()
            adapter.submit(row)
            val target = focusKey?.let { k -> row.indexOfFirst { it.key == k } }?.takeIf { it >= 0 }
                ?: selected.coerceIn(0, (row.size - 1).coerceAtLeast(0))
            rv.selectedPosition = target
            if (hadFocus) rv.requestFocus()
        }
        refreshFavorites()
    }

    /** Избранное (свежие сверху): фильм из пула по ключу или из снимка в state.json, в конце карточка «Все · Скрытые». */
    private fun refreshFavorites() {
        val s = StateStore.snapshot()
        val pool = current?.movies?.associateBy { it.key } ?: emptyMap()
        val base = current?.base ?: ""
        val movies = s.favorites.sortedByDescending { it.seq }.mapNotNull { e -> pool[e.key] ?: e.snap?.toMovie(e.key, base) }
        tvFavorites.text = if (s.favorites.isEmpty()) getString(R.string.section_favorites) else getString(R.string.section_favorites_n, s.favorites.size)
        val selected = rvFavorites.selectedPosition
        val hadFocus = rvFavorites.hasFocus()
        favAdapter.submit(movies)
        rvFavorites.selectedPosition = selected.coerceIn(0, movies.size)
        if (hadFocus) rvFavorites.requestFocus()
        refreshContinue()
    }

    /** «Продолжить просмотр»: начатые через лаунчер, один источник данных — два адаптера (Главная и Кино). */
    private fun refreshContinue() {
        val items = ContinueWatching.build(StateStore.snapshot(), current?.movies ?: emptyList(), current?.base ?: "")
        val locale = Locale("ru")
        continueCaptions = items.associate { it.movie.key to ContinueWatching.caption(it, locale) }
        continueVia = items.associate { it.movie.key to it.via }
        val movies = items.map { it.movie }
        val vis = if (movies.isEmpty()) View.GONE else View.VISIBLE
        for ((tv, rv, adapter) in listOf(
            Triple(tvContinueHome, rvContinueHome, continueHomeAdapter),
            Triple(tvContinueMovies, rvContinueMovies, continueMoviesAdapter),
        )) {
            val selected = rv.selectedPosition
            val hadFocus = rv.hasFocus()
            adapter.submit(movies)
            tv.visibility = vis
            rv.visibility = vis
            rv.selectedPosition = selected.coerceIn(0, (movies.size - 1).coerceAtLeast(0))
            if (hadFocus && movies.isNotEmpty()) rv.requestFocus()
        }
    }

    private fun continueMovie(m: Movie) {
        val ok = if (continueVia[m.key] == "kinopoisk") Launcher.openMovieInKinopoisk(this, m)
        else Launcher.openMovieInLampa(this, m) || Launcher.launchPackage(this, Launcher.LAMPA)
        if (!ok) toast(if (continueVia[m.key] == "kinopoisk") "Кинопоиск не открылся" else "LAMPA не установлена")
    }

    private fun continueMenu(m: Movie) {
        val items = ArrayList<MovieDialogs.Item>()
        fun add(label: String, action: () -> Unit) { items.add(MovieDialogs.Item(label, action)) }
        add(getString(R.string.menu_continue)) { continueMovie(m) }
        add(getString(R.string.menu_about)) { showDetails(m) }
        add(getString(R.string.menu_watched)) {
            if (StateStore.isLiked(m)) removeMovie(m, "watched", true) else MovieDialogs.askLiked(this, m) { removeMovie(m, "watched", it) }
        }
        add(getString(R.string.menu_reset)) { resetMark(m) }
        MovieDialogs.menu(this, MovieDialogs.title(m), items)
    }

    private fun loadRowKeys(): List<String> =
        getSharedPreferences("row", MODE_PRIVATE).getString("keys", "")!!.split('\n').filter { it.isNotEmpty() }

    private fun saveRowKeys() {
        getSharedPreferences("row", MODE_PRIVATE).edit().putString("keys", row.joinToString("\n") { it.key }).apply()
    }

    private fun showCams(data: CamsData) {
        tvCams.text = data.title
        camAdapter.submit(data.homeCams)
        camGridAdapter.submit(data.cams)
        val visible = if (camAdapter.itemCount == 0) View.GONE else View.VISIBLE
        camsHeader.visibility = visible
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
        refreshStatuses()
        refreshContinue()
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
        refreshStatuses()
        toast(if (on) "Запомнил: «${m.title}» нравится 👍" else "Лайк снят")
    }

    private fun toggleFavorite(m: Movie) {
        val on = !StateStore.isFavorite(m)
        StateStore.setFavorite(m, on)
        rowSeq = StateStore.snapshot().seq
        refreshStatuses()
        refreshFavorites()
        toast(if (on) "Добавил в избранное ♥" else "Убрал из избранного")
    }

    /** «Просмотрен» / «Не буду смотреть»: карточка исчезает, на её место встаёт следующая из пула; 6 секунд можно вернуть. */
    private fun removeMovie(m: Movie, reason: String, liked: Boolean?) {
        val data = current ?: return
        val before = row
        val index = row.indexOfFirst { it.key == m.key }
        val wasPinned = StateStore.isPinned(m)
        val startedVia = StateStore.snapshot().started.firstOrNull { it.key == m.key }?.let { it.via ?: "lampa" }
        StateStore.remove(m, reason, liked)
        val after = if (index >= 0) RowBuilder.replaceAt(data, StateStore.snapshot(), row, index) else row
        applyRow(after, null)
        showUndo(
            Undo(m, index, before, wasPinned, startedVia),
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
        if (u.startedVia != null) StateStore.setStarted(u.movie, true, u.startedVia)
        applyRow(u.rowBefore, u.movie.key)
        refreshStatuses()
    }

    private fun openCam(c: Cam) {
        startActivity(PlayerActivity.intent(this, c))
    }

    private fun onAppClick(s: AppShortcut) {
        when (s.id) {
            AppCatalog.REFRESH -> {
                toast("Обновляю…")
                refreshAll(force = true)
            }
            AppCatalog.LAMPA_UC -> if (!Launcher.launchLampaUncensored(this)) {
                toast("Не удалось открыть ${s.label}")
            }
            else -> if (!Launcher.launchPackage(this, s.id)) {
                toast("Не удалось открыть ${s.label}")
            }
        }
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

    companion object {
        private const val TAB_HOME = 0
        private const val TAB_MOVIES = 1
        private const val TAB_CAMS = 2
        private const val CAM_COLUMNS = 6
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
