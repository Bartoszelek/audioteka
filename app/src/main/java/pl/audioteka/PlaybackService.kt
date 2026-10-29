package pl.audioteka

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import kotlin.math.max
import kotlin.math.sqrt

/** Odtwarzanie w tle z powiadomieniem i sterowaniem z ekranu blokady. */
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null
    private lateinit var player: ExoPlayer
    private val h = Handler(Looper.getMainLooper())
    private var lastSave = 0L
    private var pausedAt = 0L
    private var sensorOn = false
    private var lastShake = 0L
    private lateinit var sm: SensorManager

    override fun onCreate() {
        super.onCreate()
        Store.init(this)
        player = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH).build(), true
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()

        player.addListener(object : Player.Listener {
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                if (playWhenReady) {
                    if (Store.autorewind && pausedAt > 0) {
                        val sec = (System.currentTimeMillis() - pausedAt) / 1000
                        val r = when {
                            sec < 60 -> 2_000L
                            sec < 3600 -> 10_000L
                            else -> 30_000L
                        }
                        player.seekTo(max(0L, player.currentPosition - r))
                    }
                    pausedAt = 0
                } else {
                    if (player.playbackState != Player.STATE_ENDED) pausedAt = System.currentTimeMillis()
                    save()
                }
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED) pausedAt = 0
                if (Store.sleepChapter && reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
                    player.pause()
                    Store.clearSleep()
                }
                save()
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) {
                    current()?.let { (id, _) ->
                        val p = Store.prog(id)
                        p.done = true
                        p.updated = System.currentTimeMillis()
                        Store.saveState()
                    }
                }
            }
        })

        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        session = MediaSession.Builder(this, player).setSessionActivity(open).build()
        sm = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        h.post(tick)
    }

    /** mediaId ma postać „idKsiążki|nrPliku”. */
    private fun current(): Pair<String, Int>? {
        val id = player.currentMediaItem?.mediaId ?: return null
        val parts = id.split("|")
        if (parts.size != 2) return null
        return Pair(parts[0], parts[1].toIntOrNull() ?: 0)
    }

    private fun save() {
        val (id, i) = current() ?: return
        val p = Store.prog(id)
        p.i = i
        p.pos = max(0L, player.currentPosition)
        p.speed = player.playbackParameters.speed
        p.updated = System.currentTimeMillis()
        Store.saveState()
    }

    private val shake = object : SensorEventListener {
        override fun onSensorChanged(e: SensorEvent) {
            val x = e.values[0]
            val y = e.values[1]
            val z = e.values[2]
            val g = sqrt(x * x + y * y + z * z)
            val now = System.currentTimeMillis()
            if (g > 24f && now - lastShake > 1500 && Store.sleepEnd > 0 && Store.sleepMinutes > 0) {
                lastShake = now
                Store.sleepEnd = now + Store.sleepMinutes * 60_000L
                player.volume = 1f
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    private val tick = object : Runnable {
        override fun run() {
            val now = System.currentTimeMillis()
            if (player.isPlaying) {
                Store.addListened(1000)
                if (now - lastSave > 10_000) {
                    lastSave = now
                    save()
                }
            }
            if (Store.sleepEnd > 0) {
                val left = Store.sleepEnd - now
                if (left <= 0) {
                    player.pause()
                    player.volume = 1f
                    Store.clearSleep()
                } else {
                    player.volume = if (left < 10_000) max(0.05f, left / 10_000f) else 1f
                }
            }
            val want = Store.sleepEnd > 0
            if (want != sensorOn) {
                if (want) {
                    sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
                        sm.registerListener(shake, it, SensorManager.SENSOR_DELAY_UI)
                    }
                } else {
                    sm.unregisterListener(shake)
                }
                sensorOn = want
            }
            h.postDelayed(this, 1000)
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        val p = session?.player
        if (p == null || !p.playWhenReady || p.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        save()
        h.removeCallbacks(tick)
        if (sensorOn) sm.unregisterListener(shake)
        session?.run {
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }
}
