package ru.nastya.home

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Состояние Насти: закреплённые, избранное, удалённые (просмотрено / не буду смотреть), «начала смотреть».
 * Хранится в filesDir/state.json и уезжает в репозиторий через [GithubSync]. Владелец файла — проектор.
 */
object StateStore {
    private const val TAG = "NastyaHome"
    private const val FILE = "state.json"
    private const val PREFS = "state_meta"

    private lateinit var ctx: Context
    private var state = AppState()
    private val messages = ArrayList<String>()
    private val listeners = ArrayList<() -> Unit>()

    @Synchronized
    fun init(context: Context) {
        if (::ctx.isInitialized) return
        ctx = context.applicationContext
        val f = File(ctx.filesDir, FILE)
        if (f.exists()) {
            try {
                state = StateJson.parse(f.readText())
            } catch (e: Exception) {
                Log.w(TAG, "state.json is broken, starting empty", e)
            }
        }
        GithubSync.init(ctx)
    }

    @Synchronized fun snapshot(): AppState = state
    fun addListener(l: () -> Unit) { listeners.add(l) }

    // ----- Запросы -----

    fun isPinned(m: Movie) = snapshot().pinned.any { it.key == m.key }
    fun isFavorite(m: Movie) = snapshot().favorites.any { it.key == m.key }
    fun isStarted(m: Movie) = snapshot().started.any { it.key == m.key }
    fun removedEntry(m: Movie): Entry? = snapshot().removed.firstOrNull { it.key == m.key }

    // ----- Действия -----

    /** false, если уже закреплено [RowBuilder.MAX_PINNED] фильмов. */
    fun pin(m: Movie): Boolean {
        var ok = true
        change("закреплён «${m.title}»") { s ->
            when {
                s.pinned.any { it.key == m.key } -> s
                s.pinned.size >= RowBuilder.MAX_PINNED -> { ok = false; s }
                else -> s.copy(pinned = s.pinned + entry(s, m))
            }
        }
        return ok
    }

    fun unpin(m: Movie) = change("откреплён «${m.title}»") { s -> s.copy(pinned = s.pinned.filter { it.key != m.key }) }

    fun setFavorite(m: Movie, on: Boolean) = change(
        if (on) "в избранное «${m.title}»" else "убран из избранного «${m.title}»"
    ) { s ->
        val rest = s.favorites.filter { it.key != m.key }
        s.copy(favorites = if (on) rest + entry(s, m) else rest)
    }

    /** «Просмотрен» / «Не буду смотреть». Фильм открепляется, но остаётся в избранном и в «начала смотреть» не висит. */
    fun remove(m: Movie, reason: String, liked: Boolean? = null) = change(
        (if (reason == "watched") "просмотрен" else "не буду смотреть") + " «${m.title}»" +
            when (liked) { true -> " 👍"; false -> " 👎"; null -> "" }
    ) { s ->
        s.copy(
            pinned = s.pinned.filter { it.key != m.key },
            started = s.started.filter { it.key != m.key },
            removed = s.removed.filter { it.key != m.key } + entry(s, m).copy(reason = reason, liked = liked),
        )
    }

    /** Оценка, поставленная уже после «Просмотрен». */
    fun setLiked(m: Movie, liked: Boolean?) = change("оценка «${m.title}»") { s ->
        s.copy(removed = s.removed.map { if (it.key == m.key) it.copy(liked = liked) else it })
    }

    /** Вернуть из «Скрытых» (или отменить удаление плашкой «Вернуть»). */
    fun restore(key: String, title: String = "") = change("возвращён${if (title.isEmpty()) "" else " «$title»"}") { s ->
        s.copy(removed = s.removed.filter { it.key != key })
    }

    fun setStarted(m: Movie, on: Boolean) = change(
        if (on) "начала смотреть «${m.title}»" else "метка снята «${m.title}»"
    ) { s ->
        val rest = s.started.filter { it.key != m.key }
        s.copy(started = if (on) rest + entry(s, m) else rest)
    }

    // ----- Миграция v1 -----

    /**
     * Первый запуск v2: метки из SharedPreferences `watch` (ключ «название|год») переезжают в состояние.
     * watched → removed, started → started. Старые метки не стираем — можно откатиться на APK v1.
     */
    fun migrateV1(pool: List<Movie>) {
        val meta = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (meta.getBoolean("migrated_v1", false)) return
        meta.edit().putBoolean("migrated_v1", true).apply()
        val old = ctx.getSharedPreferences("watch", Context.MODE_PRIVATE).all
        if (old.isEmpty() || !snapshot().isEmpty) return
        val byLegacy = pool.associateBy { it.legacyKey }
        change("перенос меток из v1") { s ->
            var next = s
            for ((legacy, value) in old) {
                val m = byLegacy[legacy]
                val key = m?.key ?: legacy
                val e = Entry(key, now(), m?.let(Snap::of), seq = next.seq)
                next = when (value) {
                    "watched" -> next.copy(removed = next.removed + e.copy(reason = "watched"))
                    "started" -> next.copy(started = next.started + e)
                    else -> next
                }
            }
            next
        }
    }

    // ----- Синхронизация -----

    @Synchronized fun isDirty(): Boolean = prefs().getBoolean("dirty", false)
    @Synchronized fun markPushed(seq: Int) { if (state.seq == seq) prefs().edit().putBoolean("dirty", false).apply() }

    @Synchronized fun takeCommitMessage(): String {
        val text = if (messages.isEmpty()) "обновила состояние" else messages.joinToString(", ")
        messages.clear()
        return "📺 Настя: " + text.take(120)
    }

    @Synchronized fun restoreCommitMessage(message: String) {
        messages.add(0, message.removePrefix("📺 Настя: "))
    }

    /** Локально пусто и старых меток нет — это свежая установка, можно забирать состояние из репозитория. */
    @Synchronized fun canRestore(): Boolean =
        state.isEmpty && !isDirty() && ctx.getSharedPreferences("watch", Context.MODE_PRIVATE).all.isEmpty()

    @Synchronized fun restore(remote: AppState): Boolean {
        if (!state.isEmpty) return false
        state = remote
        write()
        notifyListeners()
        return true
    }

    // ----- Внутреннее -----

    private fun entry(s: AppState, m: Movie) = Entry(m.key, now(), Snap.of(m), seq = s.seq + 1)

    private fun change(message: String, transform: (AppState) -> AppState) {
        synchronized(this) {
            val next = transform(state)
            if (next == state) return
            state = next.copy(seq = state.seq + 1, updated = now())
            write()
            prefs().edit().putBoolean("dirty", true).apply()
            messages.add(message)
        }
        notifyListeners()
        GithubSync.schedule()
    }

    private fun write() {
        val f = File(ctx.filesDir, FILE)
        val tmp = File(ctx.filesDir, "$FILE.tmp")
        tmp.writeText(StateJson.serialize(state))
        if (!tmp.renameTo(f)) {
            f.writeText(tmp.readText())
            tmp.delete()
        }
    }

    private fun notifyListeners() = listeners.toList().forEach { it() }
    private fun prefs() = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun now(): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).format(Date())
}
