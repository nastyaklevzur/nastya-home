package ru.nastya.home

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import java.util.concurrent.Executors

/** Простой плеер HLS-потока камеры на ExoPlayer. Назад — закрыть, любая другая кнопка — показать название. */
@OptIn(UnstableApi::class)
class PlayerActivity : AppCompatActivity() {

    private lateinit var playerView: PlayerView
    private lateinit var overlay: View
    private lateinit var tvTitle: TextView
    private lateinit var tvStatus: TextView
    private lateinit var errorBox: View
    private lateinit var tvError: TextView
    private lateinit var btnVlc: Button
    private lateinit var btnRetry: Button
    private lateinit var btnClose: Button

    private var player: ExoPlayer? = null
    private var stream: CamsRepository.Stream? = null
    private var retries = 0
    private var restarts = 0
    private val handler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    private val hideOverlay = Runnable { overlay.animate().alpha(0f).setDuration(400).start() }

    private lateinit var cam: Cam

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        playerView = findViewById(R.id.playerView)
        overlay = findViewById(R.id.overlay)
        tvTitle = findViewById(R.id.tvTitle)
        tvStatus = findViewById(R.id.tvStatus)
        errorBox = findViewById(R.id.errorBox)
        tvError = findViewById(R.id.tvError)
        btnVlc = findViewById(R.id.btnVlc)
        btnRetry = findViewById(R.id.btnRetry)
        btnClose = findViewById(R.id.btnClose)

        cam = Cam(
            id = intent.getStringExtra("id") ?: "cam",
            title = intent.getStringExtra("title") ?: "Камера",
            subtitle = intent.getStringExtra("subtitle"),
            type = intent.getStringExtra("type") ?: "hls",
            url = intent.getStringExtra("url"),
            referer = intent.getStringExtra("referer"),
            dev = intent.getStringExtra("dev"),
            server = intent.getStringExtra("server"),
            cam = intent.getStringExtra("cam"),
            page = intent.getStringExtra("page"),
            previewUrl = null,
            previewFallbackUrl = null,
            hidden = false,
        )
        tvTitle.text = cam.label
        tvStatus.text = getString(R.string.cam_connecting)

        btnRetry.setOnClickListener { retries = 0; errorBox.visibility = View.GONE; startPlayback() }
        btnClose.setOnClickListener { finish() }
        btnVlc.setOnClickListener {
            val s = stream
            if (s != null && Launcher.openInVlc(this, s.url, cam.label)) finish()
        }
    }

    override fun onStart() {
        super.onStart()
        startPlayback()
    }

    override fun onStop() {
        super.onStop()
        releasePlayer()
    }

    override fun onDestroy() {
        super.onDestroy()
        executor.shutdownNow()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION)
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (errorBox.visibility == View.VISIBLE) return super.dispatchKeyEvent(event)
        if (event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> { finish(); return true }
                else -> { showOverlay(); return true }
            }
        }
        return true
    }

    private fun showOverlay() {
        overlay.animate().cancel()
        overlay.alpha = 1f
        handler.removeCallbacks(hideOverlay)
        handler.postDelayed(hideOverlay, 4_000)
    }

    private fun startPlayback() {
        showOverlay()
        tvStatus.visibility = View.VISIBLE
        tvStatus.text = getString(R.string.cam_connecting)
        val cached = stream
        if (cached != null) {
            play(cached)
            return
        }
        executor.execute {
            val s = try {
                CamsRepository.resolve(cam)
            } catch (e: Exception) {
                Log.w(TAG, "resolve failed for ${cam.id}", e)
                null
            }
            handler.post {
                if (isFinishing || isDestroyed) return@post
                if (s == null) showError(getString(R.string.cam_resolve_failed)) else { stream = s; play(s) }
            }
        }
    }

    private fun play(s: CamsRepository.Stream) {
        releasePlayer()
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(s.headers["User-Agent"] ?: CamsRepository.BROWSER_UA)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(25_000)
            .setAllowCrossProtocolRedirects(true)
            .setDefaultRequestProperties(s.headers)
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(4_000, 30_000, 1_000, 2_000)
            .build()
        val p = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(http))
            .setLoadControl(loadControl)
            .build()
        player = p
        playerView.player = p
        p.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) {
                    tvStatus.visibility = View.GONE
                    errorBox.visibility = View.GONE
                    retries = 0
                }
            }

            override fun onRenderedFirstFrame() {
                Log.i(TAG, "first frame rendered for ${cam.id} (${p.videoSize.width}x${p.videoSize.height})")
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_BUFFERING) {
                    tvStatus.visibility = View.VISIBLE
                    tvStatus.text = getString(R.string.cam_buffering)
                } else if (state == Player.STATE_ENDED && restarts < 5) {
                    // Поток оборвался (у Skyline истёк токен) — заново узнаём адрес и продолжаем.
                    restarts++
                    stream = null
                    handler.postDelayed({ if (!isFinishing) startPlayback() }, 1_000)
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                Log.w(TAG, "player error ${error.errorCodeName} for ${s.url}", error)
                if (retries < 2) {
                    retries++
                    tvStatus.visibility = View.VISIBLE
                    tvStatus.text = getString(R.string.cam_reconnecting)
                    handler.postDelayed({ if (!isFinishing) { p.seekToDefaultPosition(); p.prepare(); p.play() } }, 2_500)
                } else {
                    showError(getString(R.string.cam_failed))
                }
            }
        })
        p.setMediaItem(MediaItem.fromUri(s.url))
        p.playWhenReady = true
        p.prepare()
    }

    private fun showError(text: String) {
        tvStatus.visibility = View.GONE
        tvError.text = text
        btnVlc.visibility = if (stream != null && Launcher.isInstalled(this, Launcher.VLC)) View.VISIBLE else View.GONE
        errorBox.visibility = View.VISIBLE
        showOverlay()
        handler.removeCallbacks(hideOverlay)
        btnRetry.requestFocus()
    }

    private fun releasePlayer() {
        playerView.player = null
        player?.release()
        player = null
    }

    companion object {
        private const val TAG = "NastyaHome"

        fun intent(ctx: Context, c: Cam): Intent = Intent(ctx, PlayerActivity::class.java)
            .putExtra("id", c.id)
            .putExtra("title", c.title)
            .putExtra("subtitle", c.subtitle)
            .putExtra("type", c.type)
            .putExtra("url", c.url)
            .putExtra("referer", c.referer)
            .putExtra("dev", c.dev)
            .putExtra("server", c.server)
            .putExtra("cam", c.cam)
            .putExtra("page", c.page)
    }
}
