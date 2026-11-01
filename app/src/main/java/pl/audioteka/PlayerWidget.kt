package pl.audioteka

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.widget.RemoteViews
import androidx.core.content.ContextCompat
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken

/** Widżet na ekran główny: okładka, tytuł, rozdział i przyciski. */
class PlayerWidget : AppWidgetProvider() {

    companion object {
        const val ACT_PLAY = "pl.audioteka.W_PLAY"
        const val ACT_BACK = "pl.audioteka.W_BACK"
        const val ACT_FWD = "pl.audioteka.W_FWD"
        const val ACT_PREV = "pl.audioteka.W_PREV"
        const val ACT_NEXT = "pl.audioteka.W_NEXT"

        private fun pi(ctx: Context, action: String, code: Int): PendingIntent =
            PendingIntent.getBroadcast(
                ctx, code, Intent(ctx, PlayerWidget::class.java).setAction(action),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )

        /** Odświeża wszystkie widżety. [p] – odtwarzacz, jeśli jest dostępny. */
        fun update(ctx: Context, p: Player?) {
            try {
                val mgr = AppWidgetManager.getInstance(ctx)
                val ids = mgr.getAppWidgetIds(ComponentName(ctx, PlayerWidget::class.java))
                if (ids.isEmpty()) return
                Store.init(ctx)
                val color = !Store.eink
                if (color) Theme.apply(ctx, Store.theme, Store.mode)
                val inv = if (color) Theme.dark else Store.invert
                val fg = if (color) Theme.fg else if (inv) Color.WHITE else Color.BLACK
                val dim = if (color) Theme.dim else if (inv) Color.rgb(170, 170, 170) else Color.rgb(85, 85, 85)
                val btnColor = if (color) Theme.accent else fg
                val v = RemoteViews(ctx.packageName, R.layout.widget)
                v.setInt(R.id.root, "setBackgroundResource", when {
                    color && inv -> R.drawable.widget_bg_color_dark
                    color -> R.drawable.widget_bg_color
                    inv -> R.drawable.widget_bg_inv
                    else -> R.drawable.widget_bg
                })
                val b = Store.book(Playback.bookIdOf(p)) ?: Store.lastBook()
                if (b == null) {
                    v.setTextViewText(R.id.title, "Audioteka")
                    v.setTextViewText(R.id.chapter, "Wybierz książkę w aplikacji")
                    v.setImageViewResource(R.id.cover, R.mipmap.ic_launcher)
                } else {
                    val active = p != null && Playback.bookIdOf(p) == b.id
                    val pr = Store.peek(b.id)
                    val i = if (active) p!!.currentMediaItemIndex else pr.i
                    val pos = if (active) p!!.currentPosition else pr.pos
                    v.setTextViewText(R.id.title, b.title)
                    v.setTextViewText(R.id.chapter, Ch.label(b, Ch.index(b, i, pos)))
                    v.setImageViewBitmap(R.id.cover, if (color) Covers.square(Covers.load(ctx, b), 160) else Covers.gray(Covers.load(ctx, b), 160))
                }
                v.setTextColor(R.id.title, fg)
                v.setTextColor(R.id.chapter, dim)
                v.setImageViewResource(R.id.play, if (p?.playWhenReady == true) R.drawable.ic_pause else R.drawable.ic_play)
                for (id in intArrayOf(R.id.prev, R.id.back, R.id.play, R.id.fwd, R.id.next)) v.setInt(id, "setColorFilter", btnColor)
                v.setOnClickPendingIntent(R.id.play, pi(ctx, ACT_PLAY, 1))
                v.setOnClickPendingIntent(R.id.back, pi(ctx, ACT_BACK, 2))
                v.setOnClickPendingIntent(R.id.fwd, pi(ctx, ACT_FWD, 3))
                v.setOnClickPendingIntent(R.id.prev, pi(ctx, ACT_PREV, 4))
                v.setOnClickPendingIntent(R.id.next, pi(ctx, ACT_NEXT, 5))
                val open = PendingIntent.getActivity(
                    ctx, 6, Intent(ctx, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
                v.setOnClickPendingIntent(R.id.cover, open)
                v.setOnClickPendingIntent(R.id.title, open)
                mgr.updateAppWidget(ids, v)
            } catch (e: Exception) {
            }
        }
    }

    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        update(ctx, null)
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        super.onReceive(ctx, intent)
        val action = intent.action ?: return
        if (action !in setOf(ACT_PLAY, ACT_BACK, ACT_FWD, ACT_PREV, ACT_NEXT)) return
        Store.init(ctx)
        val pending = goAsync()
        val app = ctx.applicationContext
        val token = SessionToken(app, ComponentName(app, PlaybackService::class.java))
        val f = MediaController.Builder(app, token).buildAsync()
        f.addListener({
            try {
                val c = f.get()
                val loaded = Playback.bookIdOf(c)
                val b = Store.book(loaded) ?: Store.lastBook()
                if (loaded == null && b != null) Playback.load(c, b)
                when (action) {
                    ACT_PLAY -> if (c.playWhenReady) c.pause() else c.play()
                    ACT_BACK -> c.seekTo(maxOf(0L, c.currentPosition - Store.back * 1000L))
                    ACT_FWD -> c.seekTo(c.currentPosition + Store.fwd * 1000L)
                    ACT_PREV -> if (b != null) Playback.jump(c, b, -1)
                    ACT_NEXT -> if (b != null) Playback.jump(c, b, 1)
                }
                Handler(Looper.getMainLooper()).postDelayed({
                    MediaController.releaseFuture(f)
                    pending.finish()
                }, 800)
            } catch (e: Exception) {
                pending.finish()
            }
        }, ContextCompat.getMainExecutor(app))
    }
}
