package pl.audioteka

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.audiofx.LoudnessEnhancer
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlin.math.max
import kotlin.math.sqrt

/** Odtwarzanie w tle: powiadomienie, ekran blokady, słuchawki, widżet. */
class PlaybackService : MediaSessionService() {
    companion object {
        const val CMD_BACK = "pl.audioteka.BACK"
        const val CMD_FWD = "pl.audioteka.FWD"
    }

    private var session: MediaSession? = null
    private lateinit var player: ExoPlayer
    private val h = Handler(Looper.getMainLooper())
    private var lastSave = 0L
    private var lastWidget = 0L
    private var pausedAt = 0L
    private var sensorOn = false
    private var lastShake = 0L
    private lateinit var sm: SensorManager
    private var enhancer: LoudnessEnhancer? = null
    private var enhancerGain = -1

    /** Odtwarzacz widziany z zewnątrz: „następny/poprzedni” = rozdziały, przewijanie wg ustawień. */
    private inner class ChapterPlayer(p: Player) : ForwardingPlayer(p) {
        private fun extra(c: Player.Commands): Player.Commands = c.buildUpon()
            .add(Player.COMMAND_SEEK_TO_NEXT).add(Player.COMMAND_SEEK_TO_PREVIOUS)
            .add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM).add(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
            .add(Player.COMMAND_SEEK_BACK).add(Player.COMMAND_SEEK_FORWARD)
            .build()

        override fun getAvailableCommands(): Player.Commands = extra(super.getAvailableCommands())
        override fun isCommandAvailable(command: Int): Boolean = availableCommands.contains(command)
        override fun hasNextMediaItem(): Boolean = true
        override fun hasPreviousMediaItem(): Boolean = true
        override fun seekToNext() = chapter(1)
        override fun seekToNextMediaItem() = chapter(1)
        override fun seekToPrevious() = chapter(-1)
        override fun seekToPreviousMediaItem() = chapter(-1)
        override fun seekBack() = jumpBy(-Store.back * 1000L)
        override fun seekForward() = jumpBy(Store.fwd * 1000L)
        override fun getSeekBackIncrement(): Long = Store.back * 1000L
        override fun getSeekForwardIncrement(): Long = Store.fwd * 1000L
    }

    private fun chapter(dir: Int) {
        val b = Store.book(Playback.bookIdOf(player)) ?: return
        Playback.jump(player, b, dir)
    }

    private fun jumpBy(ms: Long) {
        player.seekTo(max(0L, player.currentPosition + ms))
    }

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
                PlayerWidget.update(this@PlaybackService, player)
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED) pausedAt = 0
                if (Store.sleepChapter && Store.sleepUntilI >= 0 && player.currentMediaItemIndex > Store.sleepUntilI) {
                    player.pause()
                    Store.clearSleep()
                }
                save()
                PlayerWidget.update(this@PlaybackService, player)
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) {
                    val cur = current()
                    if (cur != null) {
                        val p = Store.prog(cur.first)
                        p.done = true
                        p.updated = System.currentTimeMillis()
                        Store.saveState()
                    }
                }
            }
        })
        player.addAnalyticsListener(object : AnalyticsListener {
            override fun onAudioSessionIdChanged(eventTime: AnalyticsListener.EventTime, audioSessionId: Int) {
                makeEnhancer(audioSessionId)
            }
        })
        makeEnhancer(player.audioSessionId)

        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val backBtn = CommandButton.Builder()
            .setDisplayName("Wstecz")
            .setIconResId(R.drawable.ic_back)
            .setSessionCommand(SessionCommand(CMD_BACK, Bundle.EMPTY))
            .build()
        val fwdBtn = CommandButton.Builder()
            .setDisplayName("Do przodu")
            .setIconResId(R.drawable.ic_fwd)
            .setSessionCommand(SessionCommand(CMD_FWD, Bundle.EMPTY))
            .build()
        session = MediaSession.Builder(this, ChapterPlayer(player))
            .setSessionActivity(open)
            .setCallback(SessionCallback())
            .setCustomLayout(ImmutableList.of(backBtn, fwdBtn))
            .build()
        sm = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        h.post(tick)
    }

    private inner class SessionCallback : MediaSession.Callback {
        override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
            val base = super.onConnect(session, controller)
            val cmds = base.availableSessionCommands.buildUpon()
                .add(SessionCommand(CMD_BACK, Bundle.EMPTY))
                .add(SessionCommand(CMD_FWD, Bundle.EMPTY))
                .build()
            return MediaSession.ConnectionResult.accept(cmds, base.availablePlayerCommands)
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle
        ): ListenableFuture<SessionResult> {
            when (customCommand.customAction) {
                CMD_BACK -> jumpBy(-Store.back * 1000L)
                CMD_FWD -> jumpBy(Store.fwd * 1000L)
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }
    }

    private fun makeEnhancer(sessionId: Int) {
        try {
            enhancer?.release()
        } catch (e: Exception) {
        }
        enhancer = null
        enhancerGain = -1
        if (sessionId == C.AUDIO_SESSION_ID_UNSET) return
        enhancer = try {
            LoudnessEnhancer(sessionId)
        } catch (e: Exception) {
            null
        }
    }

    private fun applyEffects() {
        if (player.skipSilenceEnabled != Store.skipSilence) player.skipSilenceEnabled = Store.skipSilence
        val e = enhancer ?: return
        val gain = Store.boost * 100
        if (gain != enhancerGain) {
            try {
                e.setTargetGain(gain)
                e.setEnabled(gain > 0)
                enhancerGain = gain
            } catch (x: Exception) {
            }
        }
    }

    /** mediaId ma postać „idKsiążki|nrPliku”. */
    private fun current(): Pair<String, Int>? {
        val id = player.currentMediaItem?.mediaId ?: return null
        val parts = id.split("|")
        if (parts.size != 2) return null
        return Pair(parts[0], parts[1].toIntOrNull() ?: 0)
    }

    private fun save() {
        val cur = current() ?: return
        val p = Store.prog(cur.first)
        p.i = cur.second
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
            applyEffects()
            if (player.isPlaying) {
                Store.addListened(1000)
                if (now - lastSave > 10_000) {
                    lastSave = now
                    save()
                }
                if (now - lastWidget > 15_000) {
                    lastWidget = now
                    PlayerWidget.update(this@PlaybackService, player)
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
            if (Store.sleepChapter && Store.sleepUntilI >= 0) {
                val i = player.currentMediaItemIndex
                if (i > Store.sleepUntilI || (i == Store.sleepUntilI && player.currentPosition >= Store.sleepUntilPos)) {
                    player.pause()
                    Store.clearSleep()
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
        try {
            enhancer?.release()
        } catch (e: Exception) {
        }
        session?.release()
        session = null
        player.release()
        super.onDestroy()
    }
}
