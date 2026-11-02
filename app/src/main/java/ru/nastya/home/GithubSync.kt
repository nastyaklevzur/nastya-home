package ru.nastya.home

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONObject
import java.util.Base64
import java.util.concurrent.Executors

/**
 * Отправка state.json в репозиторий через GitHub Contents API (PRD SC-10).
 *
 * Токен зашивается в APK при сборке из local.properties (`github.token`), права — только Contents на этот репозиторий.
 * Нет токена — синхронизация молча выключена, всё остальное работает локально.
 * Настя никогда не видит ошибок отсюда: всё уходит в logcat (тег NastyaHome).
 */
object GithubSync {
    private const val TAG = "NastyaHome"
    private const val REPO = "nastyaklevzur/nastya-home"
    private const val BRANCH = "main"
    private const val PATH = "state.json"
    private const val API = "https://api.github.com/repos/$REPO/contents/$PATH"
    private const val DEBOUNCE_MS = 5_000L
    private const val ATTEMPTS = 3

    private val handler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    private val pushTask = Runnable { executor.execute { pushNow() } }
    private lateinit var appCtx: Context

    val enabled: Boolean get() = BuildConfig.GITHUB_TOKEN.isNotEmpty()

    fun init(ctx: Context) {
        appCtx = ctx.applicationContext
    }

    /** Действие совершено: отправим через 5 секунд тишины, чтобы несколько нажатий слились в один коммит. */
    fun schedule() {
        if (!enabled) return
        handler.removeCallbacks(pushTask)
        handler.postDelayed(pushTask, DEBOUNCE_MS)
    }

    /** Отправить сейчас, если есть неотправленное (при выходе с главного экрана, раз в час, после загрузки подборки). */
    fun flush() {
        if (!enabled || !StateStore.isDirty()) return
        handler.removeCallbacks(pushTask)
        executor.execute { pushNow() }
    }

    /**
     * Свежая установка: локального состояния нет — забираем его из репозитория (публичное чтение, токен не нужен).
     * Вызывать не на главном потоке. Возвращает true, если состояние восстановлено.
     */
    fun restoreIfEmpty(): Boolean {
        if (!StateStore.canRestore()) return false
        for (base in RemoteStore.REMOTE_BASES) {
            try {
                val restored = StateJson.parse(Http.get(base + PATH))
                if (StateStore.restore(restored)) {
                    Log.i(TAG, "state restored from $base (seq=${restored.seq})")
                    return true
                }
                return false
            } catch (e: Exception) {
                Log.w(TAG, "state restore from $base failed: $e")
            }
        }
        return false
    }

    @Synchronized
    private fun pushNow() {
        if (!enabled || !StateStore.isDirty()) return
        val snapshot = StateStore.snapshot()
        val text = StateJson.serialize(snapshot)
        val message = StateStore.takeCommitMessage()
        try {
            for (attempt in 1..ATTEMPTS) {
                val sha = currentSha()
                val body = JSONObject().apply {
                    put("message", message)
                    put("content", Base64.getEncoder().encodeToString(text.toByteArray(Charsets.UTF_8)))
                    put("branch", BRANCH)
                    if (sha != null) put("sha", sha)
                }.toString()
                val r = Http.send("PUT", API, body, authHeaders())
                when (r.code) {
                    200, 201 -> {
                        StateStore.markPushed(snapshot.seq)
                        Log.i(TAG, "state pushed (seq=${snapshot.seq})")
                        // Пока шли запросы, Настя могла нажать ещё что-то: тогда отправим следом.
                        if (StateStore.isDirty()) schedule()
                        return
                    }
                    // sha устарел или файл появился между запросами — берём свежий sha и повторяем.
                    409, 422 -> Log.w(TAG, "state push conflict (attempt $attempt): ${r.code}")
                    else -> {
                        Log.w(TAG, "state push failed: HTTP ${r.code} ${r.body.take(200)}")
                        StateStore.restoreCommitMessage(message)
                        return
                    }
                }
            }
            StateStore.restoreCommitMessage(message)
        } catch (e: Exception) {
            // Нет сети и т. п.: файл остаётся «не отправлен», повтор при следующем flush().
            Log.w(TAG, "state push failed: $e")
            StateStore.restoreCommitMessage(message)
        }
    }

    private fun currentSha(): String? {
        val r = Http.send("GET", "$API?ref=$BRANCH", null, authHeaders())
        return when (r.code) {
            200 -> JSONObject(r.body).optString("sha").takeIf { it.isNotEmpty() }
            404 -> null
            else -> throw java.io.IOException("GET state.json: HTTP ${r.code}")
        }
    }

    private fun authHeaders() = mapOf(
        "Authorization" to "Bearer ${BuildConfig.GITHUB_TOKEN}",
        "Accept" to "application/vnd.github+json",
        "X-GitHub-Api-Version" to "2022-11-28",
    )
}
