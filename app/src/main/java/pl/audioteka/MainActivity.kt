package pl.audioteka

import android.Manifest
import android.app.Activity
import android.app.Dialog
import android.app.PendingIntent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.Future

class MainActivity : Activity() {
    private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    private val REQ_FOLDER = 42
    private val REQ_COVER = 43
    private val REQ_MERGE = 44
    private val ACTION_INSTALL = "pl.audioteka.INSTALL_RESULT"

    private var controller: MediaController? = null
    private var future: Future<MediaController>? = null
    private lateinit var root: FrameLayout
    private val h = Handler(Looper.getMainLooper())
    private val io = Executors.newFixedThreadPool(3)

    private var screen = "lib"
    private var tab = "prog"
    private var query = ""
    private var cur: Book? = null
    private var scanning = false
    private var scanMsg = ""
    private var statusView: TextView? = null
    private var update: Updater.Info? = null
    private var updMsg = ""
    private var updStatus: TextView? = null
    private var pendingBookId: String? = null
    private var pendingKind = ""

    // widoki odtwarzacza odświeżane co pół sekundy
    private var vChapter: TextView? = null
    private var vSeek: SeekBar? = null
    private var vCur: TextView? = null
    private var vDur: TextView? = null
    private var vLeft: TextView? = null
    private var vPP: ImageView? = null
    private var vSleep: TextView? = null
    private var vSpeed: TextView? = null
    private var dragging = false
    private var spanI = 0
    private var spanStart = 0L
    private var spanEnd = 0L

    private val fg get() = if (!Store.eink) Theme.fg else if (Store.invert) Color.WHITE else Color.BLACK
    private val bg get() = if (!Store.eink) Theme.bg else if (Store.invert) Color.BLACK else Color.WHITE
    private val dim get() = if (!Store.eink) Theme.dim else if (Store.invert) Color.rgb(170, 170, 170) else Color.rgb(85, 85, 85)
    private val surface get() = if (Store.eink) bg else Theme.surface
    private val lineC get() = if (Store.eink) fg else Theme.line
    private val accent get() = if (Store.eink) fg else Theme.accent
    private val gray = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) })

    // ------------------------------------------------------------ cykl życia
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Store.init(this)
        root = FrameLayout(this)
        root.fitsSystemWindows = true
        setContentView(root)
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        render()
        if (Store.books.isEmpty() && Store.folders.isNotEmpty()) rescan()
        handleInstall(intent)
        checkUpdate(false)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleInstall(intent)
    }

    override fun onStart() {
        super.onStart()
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        val f = MediaController.Builder(this, token).buildAsync()
        future = f
        f.addListener({
            controller = try {
                f.get()
            } catch (e: Exception) {
                null
            }
            controller?.addListener(ctlListener)
            if (cur == null) cur = Store.book(Playback.bookIdOf(controller))
            if (screen == "lib" || screen == "player") render()
        }, ContextCompat.getMainExecutor(this))
        h.post(ui)
    }

    override fun onStop() {
        h.removeCallbacks(ui)
        controller?.removeListener(ctlListener)
        future?.let { MediaController.releaseFuture(it) }
        controller = null
        PlayerWidget.update(this, null)
        super.onStop()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when (screen) {
            "help" -> {
                screen = "settings"
                render()
            }
            "lib" -> {
                @Suppress("DEPRECATION")
                super.onBackPressed()
            }
            else -> {
                screen = "lib"
                render()
            }
        }
    }

    private val ctlListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            if (screen == "player") updatePlayer()
        }
    }

    private val ui = object : Runnable {
        override fun run() {
            if (screen == "player") updatePlayer()
            h.postDelayed(this, 500)
        }
    }

    // ------------------------------------------------------------ pomocnicze widoki
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun tv(text: String, size: Float = 16f, bold: Boolean = false, color: Int = fg): TextView =
        TextView(this).apply {
            this.text = text
            textSize = size
            setTextColor(color)
            if (bold) typeface = Typeface.DEFAULT_BOLD
        }

    private fun border(stroke: Int = 2, radius: Int = 10, fill: Int = surface) = GradientDrawable().apply {
        setColor(fill)
        if (Store.eink) {
            setStroke(dp(stroke), fg)
            cornerRadius = dp(radius).toFloat()
        } else {
            if (stroke > 0 && fill == surface) setStroke(1, lineC)
            cornerRadius = dp(radius).toFloat() * 1.5f
        }
    }

    private fun btn(text: String, selected: Boolean = false, size: Float = 15f, onClick: () -> Unit): TextView =
        tv(text, size, true, if (selected) (if (Store.eink) bg else Color.WHITE) else fg).apply {
            background = border(fill = if (selected) accent else surface)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            gravity = Gravity.CENTER
            setOnClickListener { onClick() }
        }

    private fun icon(res: Int, sizeDp: Int = 28, onClick: (() -> Unit)? = null) = ImageView(this).apply {
        setImageResource(res)
        setColorFilter(fg)
        layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
        if (onClick != null) setOnClickListener { onClick() }
    }

    private fun vbox() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    private fun hbox() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    private fun lp(w: Int = MATCH, hh: Int = WRAP, weight: Float = 0f, top: Int = 0) =
        LinearLayout.LayoutParams(w, hh, weight).apply { topMargin = dp(top) }

    private fun line() = View(this).apply {
        setBackgroundColor(lineC)
        layoutParams = LinearLayout.LayoutParams(MATCH, if (Store.eink) dp(1) else 1)
    }

    private fun page(): LinearLayout {
        val sv = ScrollView(this)
        sv.isVerticalScrollBarEnabled = false
        val col = vbox()
        col.setPadding(dp(16), dp(12), dp(16), dp(32))
        sv.addView(col)
        root.addView(sv, FrameLayout.LayoutParams(MATCH, MATCH))
        return col
    }

    private fun bar(pct: Int, onHero: Boolean = false): View {
        val o = LinearLayout(this)
        if (Store.eink) {
            o.background = border(1, 0)
            o.setPadding(dp(1), dp(1), dp(1), dp(1))
        } else {
            o.background = GradientDrawable().apply {
                setColor(if (onHero) Theme.withAlpha(Color.WHITE, 0.3f) else lineC); cornerRadius = dp(3).toFloat()
            }
            o.clipToOutline = true
        }
        o.addView(View(this).apply {
            if (Store.eink) setBackgroundColor(fg)
            else background = GradientDrawable().apply { setColor(if (onHero) Color.WHITE else accent); cornerRadius = dp(3).toFloat() }
        },
            LinearLayout.LayoutParams(0, dp(4), maxOf(pct, 0).toFloat() + 0.001f))
        o.addView(View(this), LinearLayout.LayoutParams(0, dp(4), maxOf(100 - pct, 0).toFloat() + 0.001f))
        return o
    }

    private fun hms(ms: Long): String {
        val s = maxOf(0L, ms / 1000)
        val hh = s / 3600
        val m = s % 3600 / 60
        val x = s % 60
        return if (hh > 0) String.format(Locale.US, "%d:%02d:%02d", hh, m, x) else String.format(Locale.US, "%d:%02d", m, x)
    }

    private fun hm(ms: Long): String {
        val m = maxOf(0L, ms) / 60000
        return if (m >= 60) "${m / 60} h ${m % 60} min" else "$m min"
    }

    private fun sp(v: Float) = String.format(Locale("pl"), "%.2f×", v)

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    private fun applyWindow() {
        if (!Store.eink) Theme.apply(this, Store.theme, Store.mode)
        root.setBackgroundColor(bg)
        window.statusBarColor = bg
        window.navigationBarColor = bg
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = if ((Store.eink && Store.invert) || (!Store.eink && Theme.dark)) 0 else
            (View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR)
    }

    // ------------------------------------------------------------ okładki
    private fun coverView(b: Book, sizeDp: Int): View {
        val f = FrameLayout(this)
        f.background = border(2, 6)
        if (Store.eink) f.setPadding(dp(2), dp(2), dp(2), dp(2)) else {
            f.clipToOutline = true
            f.elevation = dp(if (sizeDp > 120) 8 else 2).toFloat()
        }
        val letter = tv(b.title.trim().take(1).uppercase(), sizeDp / 2.6f, true).apply { gravity = Gravity.CENTER }
        f.addView(letter, FrameLayout.LayoutParams(MATCH, MATCH))
        val iv = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            if (Store.eink) colorFilter = gray
            tag = b.id
        }
        f.addView(iv, FrameLayout.LayoutParams(MATCH, MATCH))
        val cached = Covers.cached(b)
        if (cached != null) iv.setImageBitmap(cached) else {
            io.execute {
                val bm = Covers.load(this, b)
                h.post { if (iv.tag == b.id) iv.setImageBitmap(bm) }
            }
        }
        f.layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
        return f
    }

    private fun thumb(src: String, sizeDp: Int, onClick: () -> Unit): View {
        val f = FrameLayout(this)
        f.background = border(2, 6)
        f.setPadding(dp(2), dp(2), dp(2), dp(2))
        val iv = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; if (Store.eink) colorFilter = gray }
        f.addView(iv, FrameLayout.LayoutParams(MATCH, dp(sizeDp)))
        f.setOnClickListener { onClick() }
        io.execute {
            val bm = Covers.decode(this, src, 240)
            h.post { if (bm != null) iv.setImageBitmap(bm) else f.visibility = View.GONE }
        }
        return f
    }

    // ------------------------------------------------------------ ekrany
    private fun render() {
        applyWindow()
        root.removeAllViews()
        vChapter = null; vSeek = null; vCur = null; vDur = null; vLeft = null; vPP = null; vSleep = null; vSpeed = null
        statusView = null
        updStatus = null
        when (screen) {
            "player" -> renderPlayer()
            "settings" -> renderSettings()
            "help" -> renderHelp()
            else -> renderLib()
        }
    }

    private fun emptyBox(col: LinearLayout, title: String, sub: String, action: String, onClick: () -> Unit) {
        val box = vbox().apply {
            gravity = Gravity.CENTER_HORIZONTAL
            background = border(2, 14)
            setPadding(dp(18), dp(22), dp(18), dp(22))
        }
        box.addView(tv(title, 17f, true).apply { gravity = Gravity.CENTER })
        if (sub.isNotEmpty()) box.addView(tv(sub, 15f, color = dim).apply { gravity = Gravity.CENTER }, lp(top = 6))
        box.addView(btn(action, onClick = onClick), lp(WRAP, WRAP, top = 14))
        col.addView(box, lp(top = 16))
    }

    private fun renderLib() {
        val col = page()
        val head = hbox()
        head.addView(tv("Audioteka", 28f, true), lp(0, WRAP, 1f))
        head.addView(icon(R.drawable.ic_settings, 30) { screen = "settings"; render() })
        col.addView(head)

        val upd = update
        if (upd != null) {
            val box = hbox().apply {
                background = border(3, 14)
                setPadding(dp(14), dp(10), dp(10), dp(10))
            }
            val busy = updMsg.startsWith("Pobieram") || updMsg.startsWith("Błąd") || updMsg.startsWith("Potwierdź")
            val st = tv(if (busy) updMsg else "Nowa wersja 1.${upd.number}", 15f, true)
            updStatus = st
            box.addView(st, lp(0, WRAP, 1f))
            box.addView(btn("Zainstaluj", size = 14f) { installUpdate() })
            col.addView(box, lp(top = 12))
        }

        if (Store.folders.isEmpty()) {
            emptyBox(col, "Wybierz folder z audiobookami.", "Aplikacja będzie szukać nagrań tylko w nim.", "Wybierz folder") { pickFolder() }
            return
        }
        if (scanning) {
            val st = tv(scanMsg, 14f, color = dim)
            statusView = st
            col.addView(st, lp(top = 8))
        }
        val vis = Store.books.filter { it.id !in Store.hidden }
        if (vis.isEmpty()) {
            if (!scanning) emptyBox(col, "W wybranych folderach nie ma nagrań.", "", "Zmień foldery") { screen = "settings"; render() }
            return
        }

        val last = Store.lastBook()
        if (last != null) {
            val hero = !Store.eink
            val card = hbox().apply {
                background = if (hero) Theme.gradient(dp(24).toFloat()) else border(3, 14)
                if (hero) elevation = dp(4).toFloat()
                setPadding(dp(14), dp(14), dp(14), dp(14))
                gravity = Gravity.TOP
            }
            card.addView(coverView(last, 84))
            val t = vbox()
            val heroText = if (hero) Color.WHITE else fg
            t.addView(tv(last.title, 18f, true, heroText).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END })
            val p = Store.peek(last.id)
            t.addView(tv(Ch.label(last, Ch.index(last, p.i, p.pos)), 13f, color = if (hero) Theme.withAlpha(Color.WHITE, 0.85f) else dim).apply {
                maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            })
            t.addView(bar(Store.pct(last), hero), lp(top = 8))
            t.addView(if (hero) tv("▶  Słuchaj dalej", 15f, true, Theme.accent).apply {
                background = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = dp(14).toFloat() }
                setPadding(dp(12), dp(10), dp(12), dp(10))
                gravity = Gravity.CENTER
                setOnClickListener { openBook(last, true) }
            } else btn("Słuchaj dalej") { openBook(last, true) }, lp(top = 10))
            card.addView(t, lp(0, WRAP, 1f).apply { leftMargin = dp(14) })
            col.addView(card, lp(top = 14))
        }

        val tabsScroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        val tabs = hbox()
        for ((k, label) in listOf("prog" to "W trakcie", "new" to "Nowe", "done" to "Ukończone", "all" to "Wszystkie")) {
            tabs.addView(btn(label, tab == k, 14f) { tab = k; render() }, lp(WRAP, WRAP).apply { rightMargin = dp(6) })
        }
        tabsScroll.addView(tabs)
        col.addView(tabsScroll, lp(top = 16))

        val list = vbox()
        val search = EditText(this).apply {
            setText(query)
            hint = "Szukaj tytułu"
            setTextColor(fg)
            setHintTextColor(dim)
            textSize = 16f
            setSingleLine(true)
            background = border()
            setPadding(dp(12), dp(10), dp(12), dp(10))
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    query = s?.toString() ?: ""
                    fillList(list, vis)
                }
            })
        }
        col.addView(search, lp(top = 10))
        col.addView(list, lp(top = 4))
        fillList(list, vis)
    }

    private fun fillList(list: LinearLayout, vis: List<Book>) {
        list.removeAllViews()
        val f = vis.filter { b ->
            val p = Store.peek(b.id)
            val okTab = when (tab) {
                "done" -> p.done
                "prog" -> !p.done && p.updated > 0
                "new" -> !p.done && p.updated == 0L
                else -> true
            }
            okTab && b.title.contains(query, ignoreCase = true)
        }
        if (f.isEmpty()) {
            list.addView(tv("Pusto w tej zakładce.", 15f, color = dim), lp(top = 12))
            return
        }
        for (b in f) {
            val r = hbox()
            r.setPadding(0, dp(10), 0, dp(10))
            r.addView(coverView(b, 54))
            val t = vbox()
            t.addView(tv(b.title, 16f, true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
            val p = Store.peek(b.id)
            val pct = Store.pct(b)
            val meta = hm(b.total) + (if (b.ch.size > 1) " · ${b.ch.size} rozdz." else "") +
                (if (p.done) " · ukończona" else if (pct > 0) " · $pct%" else "")
            t.addView(tv(meta, 13f, color = dim))
            t.addView(bar(pct), lp(top = 6))
            r.addView(t, lp(0, WRAP, 1f).apply { leftMargin = dp(12); rightMargin = dp(6) })
            r.addView(icon(R.drawable.ic_more, 30) { bookMenu(b) })
            r.setOnClickListener { openBook(b, true) }
            list.addView(r)
            list.addView(line())
        }
    }

    // ------------------------------------------------------------ odtwarzanie
    private fun activeController(): MediaController? {
        val b = cur ?: return null
        val c = controller ?: return null
        return if (Playback.bookIdOf(c) == b.id) c else null
    }

    private fun openBook(b: Book, play: Boolean) {
        val c = controller
        if (c == null) {
            toast("Odtwarzacz się uruchamia – spróbuj za chwilę")
            return
        }
        Playback.load(c, b)
        cur = b
        if (play) c.play()
        screen = "player"
        render()
    }

    private fun togglePlay() {
        val b = cur ?: return
        val c = activeController()
        if (c == null) {
            openBook(b, true)
            return
        }
        if (c.playWhenReady) c.pause() else {
            if (c.playbackState == Player.STATE_ENDED) c.seekTo(0, 0)
            c.play()
        }
    }

    private fun seekBy(ms: Long) {
        val c = activeController() ?: return
        c.seekTo(maxOf(0L, c.currentPosition + ms))
    }

    private fun goTo(i: Int, pos: Long) {
        val b = cur ?: return
        val c = activeController() ?: run {
            openBook(b, false)
            activeController()
        } ?: return
        c.seekTo(i.coerceIn(0, b.files.size - 1), pos)
    }

    private fun renderPlayer() {
        val b = cur
        if (b == null) {
            screen = "lib"
            renderLib()
            return
        }
        val col = page()
        val top = hbox()
        top.addView(tv("‹ Biblioteka", 17f, true).apply {
            setPadding(0, dp(8), 0, dp(8))
            setOnClickListener { screen = "lib"; render() }
        }, lp(0, WRAP, 1f))
        top.addView(icon(R.drawable.ic_more, 30) { bookMenu(b) })
        col.addView(top)

        val cv = coverView(b, 250)
        (cv.layoutParams as LinearLayout.LayoutParams).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = dp(8) }
        cv.setOnClickListener { coverDialog(b) }
        col.addView(cv)
        col.addView(tv(b.title, 20f, true).apply { gravity = Gravity.CENTER }, lp(top = 14))
        val chV = tv("", 15f, color = dim).apply { gravity = Gravity.CENTER; maxLines = 2 }
        vChapter = chV
        col.addView(chV, lp(top = 4))

        val seek = SeekBar(this).apply {
            max = 1000
            progressTintList = ColorStateList.valueOf(accent)
            thumbTintList = ColorStateList.valueOf(accent)
            progressBackgroundTintList = ColorStateList.valueOf(if (Store.eink) dim else lineC)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {}
                override fun onStartTrackingTouch(s: SeekBar?) {
                    dragging = true
                }

                override fun onStopTrackingTouch(s: SeekBar?) {
                    dragging = false
                    val c = activeController() ?: return
                    if (spanEnd > spanStart) c.seekTo(spanI, spanStart + (spanEnd - spanStart) * (s?.progress ?: 0) / 1000)
                }
            })
        }
        vSeek = seek
        col.addView(seek, lp(top = 16))
        val times = hbox()
        val curV = tv("0:00", 13f)
        val durV = tv("0:00", 13f)
        vCur = curV
        vDur = durV
        times.addView(curV, lp(0, WRAP, 1f))
        times.addView(durV)
        col.addView(times)

        val ctl = hbox()
        fun cell(v: View) {
            val f = FrameLayout(this)
            f.addView(v, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER))
            ctl.addView(f, lp(0, WRAP, 1f))
        }

        fun labeled(res: Int, label: String, onClick: () -> Unit): View {
            val v = vbox().apply { gravity = Gravity.CENTER_HORIZONTAL; setOnClickListener { onClick() } }
            v.addView(icon(res, 30).apply { setColorFilter(accent) })
            v.addView(tv(label, 12f, true))
            return v
        }
        cell(icon(R.drawable.ic_prev, 30) { activeController()?.let { Playback.jump(it, b, -1) } }.apply { setColorFilter(accent) })
        cell(labeled(R.drawable.ic_back, "${Store.back} s") { seekBy(-Store.back * 1000L) })
        val ppBox = FrameLayout(this).apply {
            background = if (Store.eink) GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(bg)
                setStroke(dp(3), fg)
            } else GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Theme.accent, Theme.accent2)).apply { shape = GradientDrawable.OVAL }
            if (!Store.eink) elevation = dp(6).toFloat()
            setOnClickListener { togglePlay() }
        }
        val ppIcon = ImageView(this).apply { setImageResource(R.drawable.ic_play); setColorFilter(if (Store.eink) fg else Color.WHITE) }
        vPP = ppIcon
        ppBox.addView(ppIcon, FrameLayout.LayoutParams(dp(36), dp(36), Gravity.CENTER))
        val ppWrap = FrameLayout(this)
        ppWrap.addView(ppBox, FrameLayout.LayoutParams(dp(78), dp(78), Gravity.CENTER))
        ctl.addView(ppWrap, lp(0, WRAP, 1.3f))
        cell(labeled(R.drawable.ic_fwd, "${Store.fwd} s") { seekBy(Store.fwd * 1000L) })
        cell(icon(R.drawable.ic_next, 30) { activeController()?.let { Playback.jump(it, b, 1) } }.apply { setColorFilter(accent) })
        col.addView(ctl, lp(top = 18))

        val leftV = tv("", 14f, color = dim).apply { gravity = Gravity.CENTER }
        vLeft = leftV
        col.addView(leftV, lp(top = 12))

        val opts = hbox()
        fun opt(icon: View, label: TextView, onClick: () -> Unit) {
            val v = vbox().apply {
                gravity = Gravity.CENTER_HORIZONTAL
                background = border()
                setPadding(dp(4), dp(10), dp(4), dp(10))
                setOnClickListener { onClick() }
            }
            if (icon is ImageView) icon.setColorFilter(accent)
            v.addView(icon)
            v.addView(label.apply { gravity = Gravity.CENTER })
            opts.addView(v, lp(0, WRAP, 1f).apply { leftMargin = dp(4); rightMargin = dp(4) })
        }
        val spV = tv(sp(Store.peek(b.id).speed), 16f, true)
        vSpeed = spV
        opt(spV, tv("Prędkość", 12f)) { speedDialog() }
        val slV = tv("Wyłącznik", 12f)
        vSleep = slV
        opt(icon(R.drawable.ic_moon, 26), slV) { sleepDialog() }
        opt(icon(R.drawable.ic_bookmark, 26), tv("Zakładki", 12f)) { bookmarksDialog() }
        opt(icon(R.drawable.ic_list, 26), tv("Rozdziały", 12f)) { chaptersDialog() }
        col.addView(opts, lp(top = 18))
        updatePlayer()
    }

    private fun updatePlayer() {
        val b = cur ?: return
        val c = activeController()
        val p = Store.peek(b.id)
        val i = c?.currentMediaItemIndex ?: p.i
        val pos = c?.currentPosition ?: p.pos
        val d0 = c?.duration ?: C.TIME_UNSET
        val fileDur = if (d0 != C.TIME_UNSET && d0 > 0) d0 else b.durs.getOrElse(i) { 0L }
        val k = Ch.index(b, i, pos)
        val chap = b.ch[k]
        spanI = i
        spanStart = if (chap.i == i) chap.start else 0L
        spanEnd = maxOf(spanStart + 1, Ch.end(b, k, fileDur))
        vChapter?.text = Ch.label(b, k)
        if (!dragging) vSeek?.progress = ((pos - spanStart) * 1000 / (spanEnd - spanStart)).toInt().coerceIn(0, 1000)
        vCur?.text = hms(pos - spanStart)
        vDur?.text = hms(spanEnd - spanStart)
        val speed = c?.playbackParameters?.speed ?: p.speed
        val left = b.durs.drop(i + 1).sum() + maxOf(0L, fileDur - pos)
        vLeft?.text = "Do końca książki: " + hm((left / speed).toLong()) + (if (speed != 1f) " (przy ${sp(speed)})" else "")
        vSpeed?.text = sp(speed)
        vPP?.setImageResource(if (c != null && c.playWhenReady) R.drawable.ic_pause else R.drawable.ic_play)
        vSleep?.text = when {
            Store.sleepChapter -> "Do końca rozdz."
            Store.sleepEnd > 0 -> hms(Store.sleepEnd - System.currentTimeMillis())
            else -> "Wyłącznik"
        }
    }

    // ------------------------------------------------------------ okna dialogowe
    private fun sheet(title: String, build: (LinearLayout, Dialog) -> Unit): Dialog {
        val d = Dialog(this)
        d.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val col = vbox().apply {
            setPadding(dp(16), dp(14), dp(16), dp(22))
            background = GradientDrawable().apply {
                setColor(bg)
                if (Store.eink) setStroke(dp(3), fg)
                else cornerRadii = floatArrayOf(dp(24).toFloat(), dp(24).toFloat(), dp(24).toFloat(), dp(24).toFloat(), 0f, 0f, 0f, 0f)
            }
        }
        val head = hbox()
        head.addView(tv(title, 19f, true).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END }, lp(0, WRAP, 1f))
        head.addView(icon(R.drawable.ic_close, 28) { d.dismiss() })
        col.addView(head)
        build(col, d)
        val sv = ScrollView(this)
        sv.addView(col)
        d.setContentView(sv)
        d.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(MATCH, WRAP)
            setGravity(Gravity.BOTTOM)
            setWindowAnimations(0)
        }
        d.show()
        return d
    }

    private fun grid(col: LinearLayout, cols: Int, items: List<View>) {
        for (start in items.indices step cols) {
            val row = hbox()
            for (k in 0 until cols) {
                val v = items.getOrNull(start + k) ?: View(this)
                row.addView(v, lp(0, WRAP, 1f).apply { leftMargin = dp(3); rightMargin = dp(3) })
            }
            col.addView(row, lp(top = 8))
        }
    }

    private fun item(col: LinearLayout, left: View, right: View? = null, onClick: (() -> Unit)? = null) {
        val r = hbox()
        r.setPadding(0, dp(11), 0, dp(11))
        r.addView(left, lp(0, WRAP, 1f))
        if (right != null) r.addView(right)
        if (onClick != null) r.setOnClickListener { onClick() }
        col.addView(r)
        col.addView(line())
    }

    private fun setSpeed(v: Float) {
        val b = cur ?: return
        val s = Math.round(v.coerceIn(0.5f, 3f) * 100) / 100f
        activeController()?.setPlaybackSpeed(s)
        Store.prog(b.id).speed = s
        Store.saveState()
        updatePlayer()
    }

    private fun speedDialog() {
        val b = cur ?: return
        sheet("Prędkość") { col, d ->
            val big = tv(sp(Store.peek(b.id).speed), 40f, true).apply { gravity = Gravity.CENTER }
            col.addView(big, lp(top = 8))
            fun refresh() {
                big.text = sp(Store.peek(b.id).speed)
            }
            grid(col, 2, listOf(
                btn("− 0,05") { setSpeed(Store.peek(b.id).speed - 0.05f); refresh() },
                btn("+ 0,05") { setSpeed(Store.peek(b.id).speed + 0.05f); refresh() }
            ))
            grid(col, 4, listOf(0.8f, 1f, 1.1f, 1.25f, 1.5f, 1.75f, 2f, 2.5f).map { v ->
                btn(sp(v), size = 13f) { setSpeed(v); d.dismiss() }
            })
        }
    }

    private fun sleepDialog() {
        sheet("Wyłącznik czasowy") { col, d ->
            val opts = listOf(5, 10, 15, 20, 30, 45, 60).map { m ->
                btn("$m min", size = 14f) {
                    Store.clearSleep()
                    Store.sleepMinutes = m
                    Store.sleepEnd = System.currentTimeMillis() + m * 60_000L
                    activeController()?.let { if (!it.playWhenReady) it.play() }
                    d.dismiss()
                }
            } + btn("Koniec rozdz.", size = 14f) {
                val b = cur
                val c = activeController()
                if (b != null && c != null) {
                    Store.clearSleep()
                    val i = c.currentMediaItemIndex
                    val k = Ch.index(b, i, c.currentPosition)
                    val nx = b.ch.getOrNull(k + 1)
                    Store.sleepUntilI = i
                    Store.sleepUntilPos = if (nx != null && nx.i == i) nx.start else Long.MAX_VALUE
                    Store.sleepChapter = true
                    if (!c.playWhenReady) c.play()
                }
                d.dismiss()
            }
            grid(col, 4, opts)
            col.addView(btn("Wyłącz wyłącznik") { Store.clearSleep(); d.dismiss() }, lp(top = 12))
            col.addView(tv("Potrząśnij telefonem, żeby odliczać od nowa. Ostatnie 10 sekund ścisza się stopniowo.", 13f, color = dim), lp(top = 10))
        }
    }

    private fun bookmarksDialog() {
        val b = cur ?: return
        sheet("Zakładki") { col, d ->
            col.addView(btn("Dodaj zakładkę w tym miejscu") { d.dismiss(); addBookmark() }, lp(top = 10))
            val bm = Store.peek(b.id).bm
            if (bm.isEmpty()) col.addView(tv("Brak zakładek.", 15f, color = dim), lp(top = 12))
            for (z in bm.toList()) {
                val k = Ch.index(b, z.i, z.pos)
                val chStart = if (b.ch[k].i == z.i) b.ch[k].start else 0L
                val t = vbox()
                t.addView(tv(Ch.label(b, k), 15f, true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
                t.addView(tv(hms(z.pos - chStart) + if (z.note.isNotEmpty()) " · ${z.note}" else "", 13f, color = dim))
                val right = hbox()
                right.addView(btn("Idź", size = 13f) {
                    goTo(z.i, z.pos)
                    d.dismiss()
                })
                right.addView(icon(R.drawable.ic_close, 26) {
                    Store.prog(b.id).bm.remove(z)
                    Store.saveState()
                    d.dismiss()
                    bookmarksDialog()
                }.apply { (layoutParams as LinearLayout.LayoutParams).leftMargin = dp(10) })
                item(col, t, right)
            }
        }
    }

    private fun addBookmark() {
        val b = cur ?: return
        val c = activeController()
        val i = c?.currentMediaItemIndex ?: Store.peek(b.id).i
        val pos = c?.currentPosition ?: Store.peek(b.id).pos
        sheet("Nowa zakładka") { col, d ->
            col.addView(tv(Ch.label(b, Ch.index(b, i, pos)), 14f, color = dim), lp(top = 6))
            val e = EditText(this).apply {
                hint = "Notatka (opcjonalnie)"
                setTextColor(fg)
                setHintTextColor(dim)
                background = border()
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                setPadding(dp(12), dp(10), dp(12), dp(10))
            }
            col.addView(e, lp(top = 10))
            col.addView(btn("Zapisz zakładkę") {
                Store.prog(b.id).bm.add(Bookmark(i, pos, e.text.toString().trim(), System.currentTimeMillis()))
                Store.saveState()
                d.dismiss()
                toast("Zapisano zakładkę")
            }, lp(top = 12))
        }
    }

    private fun chaptersDialog() {
        val b = cur ?: return
        val c = activeController()
        val curK = Ch.index(b, c?.currentMediaItemIndex ?: Store.peek(b.id).i, c?.currentPosition ?: Store.peek(b.id).pos)
        sheet("Rozdziały (${b.ch.size})") { col, d ->
            b.ch.forEachIndexed { k, ch ->
                val len = Ch.end(b, k, b.durs.getOrElse(ch.i) { 0L }) - ch.start
                val left = tv("${k + 1}. ${ch.title}", 15f, k == curK).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END }
                item(col, left, tv(hms(len), 13f, color = dim).apply { setPadding(dp(8), 0, 0, 0) }) {
                    goTo(ch.i, ch.start)
                    d.dismiss()
                }
            }
        }
    }

    private fun bookMenu(b: Book) {
        val p = Store.peek(b.id)
        sheet(b.title) { col, d ->
            col.addView(btn("Zmień okładkę") { d.dismiss(); coverDialog(b) }, lp(top = 10))
            col.addView(btn(if (p.done) "Oznacz jako nieukończoną" else "Oznacz jako ukończoną") {
                val q = Store.prog(b.id)
                q.done = !q.done
                q.updated = System.currentTimeMillis()
                Store.saveState()
                d.dismiss()
                render()
            }, lp(top = 8))
            col.addView(btn("Zacznij od początku") {
                val q = Store.prog(b.id)
                q.done = false
                q.i = 0
                q.pos = 0
                Store.saveState()
                if (cur?.id == b.id) activeController()?.seekTo(0, 0)
                d.dismiss()
                render()
            }, lp(top = 8))
            if (b.files.size > 1) col.addView(btn("Scal w jeden plik") { d.dismiss(); mergeDialog(b) }, lp(top = 8))
            col.addView(btn("Ukryj z biblioteki") {
                Store.hidden.add(b.id)
                Store.saveState()
                d.dismiss()
                if (screen == "player") screen = "lib"
                render()
            }, lp(top = 8))
        }
    }

    // ------------------------------------------------------------ okładki – wybór
    private fun setCover(b: Book, src: String) {
        Store.covers[b.id] = src
        Store.saveState()
        Covers.clear()
        PlayerWidget.update(this, controller)
        render()
    }

    private fun coverDialog(b: Book) {
        sheet("Okładka: ${b.title}") { col, d ->
            if (b.imgs.isNotEmpty()) {
                col.addView(tv("Z folderu i z plików nagrania:", 14f, color = dim), lp(top = 8))
                grid(col, 3, b.imgs.map { src -> thumb(src, 100) { setCover(b, src); d.dismiss() } })
            }
            col.addView(btn("Wygenerowana okładka (czarno-biała)") { setCover(b, "gen"); d.dismiss() }, lp(top = 12))
            col.addView(btn("Wybierz zdjęcie z telefonu") {
                d.dismiss()
                pendingBookId = b.id
                try {
                    startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*"), REQ_COVER)
                } catch (e: Exception) {
                    toast("Nie mogę otworzyć wyboru zdjęć")
                }
            }, lp(top = 8))
            col.addView(btn("Szukaj okładki w internecie") { d.dismiss(); searchDialog(b) }, lp(top = 8))
        }
    }

    private fun saveCoverFrom(b: Book, src: String) {
        toast("Zapisuję okładkę…")
        io.execute {
            val bm = Covers.decode(this, src, 900)
            val path = if (bm != null) Covers.save(this, b, bm) else null
            h.post { if (path != null) setCover(b, path) else toast("Nie udało się pobrać obrazu") }
        }
    }

    private fun searchDialog(b: Book) {
        sheet("Okładka z internetu") { col, d ->
            val e = EditText(this).apply {
                setText(b.title)
                setTextColor(fg)
                background = border()
                setSingleLine(true)
                setPadding(dp(12), dp(10), dp(12), dp(10))
            }
            col.addView(e, lp(top = 10))
            col.addView(tv("Wpisz tytuł i autora – wyniki z Google Books i Open Library.", 13f, color = dim), lp(top = 6))
            val results = vbox()
            val status = tv("", 14f, color = dim)
            fun go() {
                status.text = "Szukam…"
                results.removeAllViews()
                val q = e.text.toString().trim()
                io.execute {
                    val urls = try {
                        Covers.search(q)
                    } catch (x: Exception) {
                        emptyList()
                    }
                    h.post {
                        status.text = if (urls.isEmpty()) "Nic nie znaleziono. Spróbuj krótszego tytułu albo dopisz autora." else "Stuknij okładkę, żeby ją ustawić:"
                        grid(results, 3, urls.map { u -> thumb(u, 120) { d.dismiss(); saveCoverFrom(b, u) } })
                    }
                }
            }
            col.addView(btn("Szukaj") { go() }, lp(top = 10))
            col.addView(status, lp(top = 10))
            col.addView(results)
            go()
        }
    }

    // ------------------------------------------------------------ scalanie
    private fun mergeDialog(b: Book) {
        val kind = Merger.kind(this, b)
        sheet("Scal w jeden plik") { col, d ->
            if (kind == null) {
                col.addView(tv("Scalanie działa, gdy wszystkie pliki są MP3 albo wszystkie są M4A/M4B/AAC. Ta książka ma inne lub mieszane formaty.", 15f), lp(top = 10))
                return@sheet
            }
            col.addView(tv("Powstanie jeden plik ${kind.uppercase()} z ${b.files.size} części, z zachowaniem rozdziałów. Oryginały zostaną bez zmian.", 15f), lp(top = 10))
            col.addView(tv("Zapisz go w folderze z audiobookami – pojawi się w bibliotece, a części możesz potem ukryć. Przy plikach MP3 najlepiej działa stała jakość (CBR).", 13f, color = dim), lp(top = 8))
            col.addView(btn("Wybierz, gdzie zapisać") {
                d.dismiss()
                pendingBookId = b.id
                pendingKind = kind
                val i = Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                    .setType(if (kind == "mp3") "audio/mpeg" else "audio/mp4")
                    .putExtra(Intent.EXTRA_TITLE, b.title.replace(Regex("[\\\\/:*?\"<>|]"), "_") + "." + kind)
                try {
                    startActivityForResult(i, REQ_MERGE)
                } catch (e: Exception) {
                    toast("Nie mogę otworzyć zapisu pliku")
                }
            }, lp(top = 12))
        }
    }

    private fun runMerge(b: Book, kind: String, dest: Uri) {
        var label: TextView? = null
        val dlg = sheet("Scalanie") { col, _ ->
            val t = tv("Przygotowuję…", 15f)
            label = t
            col.addView(t, lp(top = 10))
            col.addView(tv("Nie zamykaj aplikacji. Duże książki mogą zająć kilka minut.", 13f, color = dim), lp(top = 8))
        }
        dlg.setCancelable(false)
        Thread {
            val err = try {
                val status: (String) -> Unit = { m -> h.post { label?.text = m } }
                if (kind == "mp3") Merger.mergeMp3(this, b, dest, status) else Merger.mergeM4b(this, b, dest, status)
                null
            } catch (e: Throwable) {
                e.message ?: "nieznany błąd"
            }
            h.post {
                dlg.dismiss()
                if (err == null) {
                    toast("Gotowe – scalony plik zapisany")
                    rescan()
                } else {
                    try {
                        contentResolver.delete(dest, null, null)
                    } catch (e: Exception) {
                    }
                    sheet("Scalanie nieudane") { col, _ -> col.addView(tv(err, 15f), lp(top = 10)) }
                }
            }
        }.start()
    }

    // ------------------------------------------------------------ ustawienia
    private fun seg(col: LinearLayout, label: String, opts: List<Pair<String, Boolean>>, onPick: (Int) -> Unit) {
        col.addView(tv(label, 15f), lp(top = 14))
        val r = hbox().apply { gravity = Gravity.TOP }
        opts.forEachIndexed { n, (text, sel) ->
            r.addView(btn(text, sel, 14f) { onPick(n) }, lp(WRAP, WRAP, top = 6).apply { rightMargin = dp(6) })
        }
        val hs = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        hs.addView(r)
        col.addView(hs)
    }

    private fun renderSettings() {
        val col = page()
        col.addView(tv("‹ Biblioteka", 17f, true).apply {
            setPadding(0, dp(8), 0, dp(8))
            setOnClickListener { screen = "lib"; render() }
        })
        col.addView(tv("Ustawienia", 28f, true), lp(top = 4))

        col.addView(tv("Foldery z audiobookami", 17f, true), lp(top = 20))
        if (Store.folders.isEmpty()) col.addView(tv("Nie wybrano folderu.", 15f, color = dim), lp(top = 8))
        for (f in Store.folders.toList()) {
            item(col, tv(Scanner.folderLabel(f), 15f), icon(R.drawable.ic_close, 26) {
                Store.folders.remove(f)
                try {
                    contentResolver.releasePersistableUriPermission(Uri.parse(f), Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } catch (e: Exception) {
                }
                Store.saveState()
                rescan()
            })
        }
        col.addView(btn("Dodaj folder") { pickFolder() }, lp(top = 10))

        col.addView(tv("Odtwarzanie", 17f, true), lp(top = 24))
        val backs = listOf(10, 15, 30, 60)
        seg(col, "Przeskok wstecz", backs.map { "$it s" to (it == Store.back) }) { n -> Store.back = backs[n]; Store.saveState(); render() }
        val fwds = listOf(15, 30, 60)
        seg(col, "Przeskok do przodu", fwds.map { "$it s" to (it == Store.fwd) }) { n -> Store.fwd = fwds[n]; Store.saveState(); render() }
        seg(col, "Cofnij trochę po pauzie", listOf("Tak" to Store.autorewind, "Nie" to !Store.autorewind)) { n ->
            Store.autorewind = n == 0; Store.saveState(); render()
        }
        seg(col, "Pomijaj ciszę", listOf("Tak" to Store.skipSilence, "Nie" to !Store.skipSilence)) { n ->
            Store.skipSilence = n == 0; Store.saveState(); render()
        }
        val boosts = listOf(0, 3, 6, 10)
        seg(col, "Wzmocnienie głośności", boosts.map { (if (it == 0) "Brak" else "+$it dB") to (it == Store.boost) }) { n ->
            Store.boost = boosts[n]; Store.saveState(); render()
        }
        seg(col, "Styl", listOf("E-ink (czarno-biały)" to Store.eink, "Kolorowy" to !Store.eink)) { n ->
            Store.eink = n == 0; Store.saveState(); Covers.clear(); PlayerWidget.update(this, controller); render()
        }
        if (Store.eink) {
            seg(col, "Kolory", listOf("Czarne na białym" to !Store.invert, "Białe na czarnym" to Store.invert)) { n ->
                Store.invert = n == 1; Store.saveState(); PlayerWidget.update(this, controller); render()
            }
        } else {
            col.addView(tv("Motyw kolorów", 15f), lp(top = 14))
            val sw = Theme.PRESETS.filter { it.id != "auto" || Theme.hasWallpaperColors() }.map { pr ->
                swatch(pr, Store.theme == pr.id) { Store.theme = pr.id; Store.saveState(); PlayerWidget.update(this, controller); render() }
            }
            for (start in sw.indices step 4) {
                val row = hbox().apply { gravity = Gravity.TOP }
                for (k in 0 until 4) row.addView(sw.getOrNull(start + k) ?: View(this), lp(0, WRAP, 1f))
                col.addView(row, lp(top = 4))
            }
            seg(col, "Tryb", Theme.MODES.map { (id, label) -> label to (Store.mode == id) }) { n ->
                Store.mode = Theme.MODES[n].first; Store.saveState(); PlayerWidget.update(this, controller); render()
            }
        }

        col.addView(tv("Widżet", 17f, true), lp(top = 24))
        col.addView(tv("Na ekranie głównym przytrzymaj puste miejsce → Widżety → Audioteka. Widżet ma okładkę, rozdział i przyciski. Działa w launcherach, które obsługują widżety.", 14f, color = dim), lp(top = 6))

        col.addView(tv("Statystyki słuchania", 17f, true), lp(top = 24))
        var week = 0L
        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US)
        for (k in 0 until 7) week += Store.statDays[fmt.format(java.util.Date(System.currentTimeMillis() - k * 86_400_000L))] ?: 0L
        item(col, tv("Dziś", 15f), tv(hm(Store.statDays[Store.today()] ?: 0L), 15f, true))
        item(col, tv("Ostatnie 7 dni", 15f), tv(hm(week), 15f, true))
        item(col, tv("Łącznie", 15f), tv(hm(Store.statTotal), 15f, true))
        item(col, tv("Ukończone książki", 15f), tv("${Store.books.count { Store.peek(it.id).done }}", 15f, true))

        col.addView(tv("Ukryte", 17f, true), lp(top = 24))
        val hid = Store.books.filter { it.id in Store.hidden }
        if (hid.isEmpty()) col.addView(tv("Nic nie jest ukryte.", 15f, color = dim), lp(top = 8))
        for (b in hid) {
            item(col, tv(b.title, 15f).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }, btn("Przywróć", size = 13f) {
                Store.hidden.remove(b.id); Store.saveState(); render()
            })
        }

        col.addView(btn(if (scanning) "Przeszukuję…" else "Przeszukaj foldery ponownie") { rescan() }, lp(top = 22))

        col.addView(tv("Aktualizacje", 17f, true), lp(top = 26))
        col.addView(btn("Instrukcja: jak zaktualizować aplikację") { screen = "help"; render() }, lp(top = 8))
        item(col, tv("Zainstalowana wersja", 15f), tv(versionName(), 15f, true))
        if (Store.ghToken.isBlank()) {
            col.addView(tv("Wklej token GitHub (ten sam co w Termuksie). Aplikacja będzie sama sprawdzać i instalować nowe wersje.", 13f, color = dim), lp(top = 8))
            val e = EditText(this).apply {
                hint = "ghp_…"
                setTextColor(fg)
                setHintTextColor(dim)
                background = border()
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                setPadding(dp(12), dp(10), dp(12), dp(10))
            }
            col.addView(e, lp(top = 8))
            col.addView(btn("Zapisz token") {
                Store.ghToken = e.text.toString().trim()
                Store.saveState()
                checkUpdate(true)
            }, lp(top = 8))
        } else {
            item(col, tv("Token GitHub zapisany", 15f), btn("Usuń", size = 13f) {
                Store.ghToken = ""
                Store.saveState()
                update = null
                updMsg = ""
                render()
            })
            val st = tv(updMsg, 14f, color = dim)
            updStatus = st
            if (updMsg.isNotEmpty()) col.addView(st, lp(top = 8))
            val upd = update
            if (upd != null) col.addView(btn("Pobierz i zainstaluj 1.${upd.number}") { installUpdate() }, lp(top = 8))
            else col.addView(btn("Sprawdź aktualizacje") { checkUpdate(true) }, lp(top = 8))
        }
        col.addView(tv("Audioteka ${versionName()} · słuchanie działa bez internetu", 12f, color = dim), lp(top = 16))
    }

    /** Kółko z kolorami motywu do wyboru w ustawieniach. */
    private fun swatch(pr: Theme.Preset, selected: Boolean, onClick: () -> Unit): View {
        val (a, b) = Theme.colorsOf(this, pr)
        val v = vbox().apply { gravity = Gravity.CENTER_HORIZONTAL; setPadding(0, dp(6), 0, dp(4)); setOnClickListener { onClick() } }
        val ring = FrameLayout(this).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.TRANSPARENT); if (selected) setStroke(dp(3), fg) }
        }
        val dot = View(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(a, b)).apply { shape = GradientDrawable.OVAL }
        }
        ring.addView(dot, FrameLayout.LayoutParams(dp(40), dp(40), Gravity.CENTER))
        v.addView(ring, LinearLayout.LayoutParams(dp(50), dp(50)))
        v.addView(tv(pr.name, 11f, selected, if (selected) fg else dim).apply { gravity = Gravity.CENTER; maxLines = 1; ellipsize = TextUtils.TruncateAt.END }, lp(top = 2))
        return v
    }

    // ------------------------------------------------------------ instrukcja
    private val UPDATE_CMD =
        "cd ~ && unzip -o \"\$(ls -t ~/storage/downloads/Audioteka*.zip | head -1)\" && cd Audioteka && git add -A && " +
            "git -c user.name=ja -c user.email=ja@x.pl commit -m aktualizacja && git push"
    private val TOKEN_CMD =
        "cd ~/Audioteka && git remote set-url origin https://Bartoszelek:NOWY_TOKEN@github.com/${Updater.REPO}.git"

    private fun copy(text: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("Audioteka", text))
        toast("Skopiowano – wklej w Termuksie")
    }

    private fun openUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: Exception) {
            toast("Nie mogę otworzyć przeglądarki")
        }
    }

    private fun openTermux() {
        val i = packageManager.getLaunchIntentForPackage("com.termux")
        if (i != null) startActivity(i) else toast("Termux nie jest zainstalowany (F-Droid)")
    }

    private fun code(col: LinearLayout, cmd: String) {
        val t = tv(cmd, 13f).apply {
            typeface = Typeface.MONOSPACE
            background = border(1, 6)
            setPadding(dp(10), dp(8), dp(10), dp(8))
            setTextIsSelectable(true)
        }
        col.addView(t, lp(top = 8))
        col.addView(btn("Kopiuj polecenie", size = 14f) { copy(cmd) }, lp(WRAP, WRAP, top = 6))
    }

    private fun step(col: LinearLayout, n: Int, text: String) {
        val r = hbox().apply { gravity = Gravity.TOP }
        r.addView(tv("$n.", 15f, true), LinearLayout.LayoutParams(dp(26), WRAP))
        r.addView(tv(text, 15f), lp(0, WRAP, 1f))
        col.addView(r, lp(top = 10))
    }

    private fun renderHelp() {
        val col = page()
        col.addView(tv("‹ Ustawienia", 17f, true).apply {
            setPadding(0, dp(8), 0, dp(8))
            setOnClickListener { screen = "settings"; render() }
        })
        col.addView(tv("Jak zaktualizować Audiotekę", 26f, true), lp(top = 4))
        col.addView(tv("Nową wersję kodu dostajesz od Claude jako plik Audioteka.zip. Telefon wysyła go na GitHub, GitHub buduje aplikację, a Audioteka sama ją pobiera.", 14f, color = dim), lp(top = 8))

        col.addView(tv("Zwykła aktualizacja", 18f, true), lp(top = 22))
        step(col, 1, "Pobierz Audioteka.zip z rozmowy z Claude do folderu Pobrane. Nie otwieraj go – wystarczy, że się pobierze.")
        step(col, 2, "Otwórz Termux. Jeśli na początku linii nie ma znaku \$, najpierw stuknij CTRL, a potem c.")
        col.addView(btn("Otwórz Termux") { openTermux() }, lp(WRAP, WRAP, top = 8))
        step(col, 3, "Skopiuj to polecenie i wklej w Termuksie (przytrzymaj ekran → Paste), potem Enter:")
        code(col, UPDATE_CMD)
        step(col, 4, "Na końcu powinno pojawić się „main -> main”. GitHub buduje aplikację ok. 3–5 minut.")
        col.addView(btn("Otwórz budowanie na GitHubie") { openUrl("https://github.com/${Updater.REPO}/actions") }, lp(WRAP, WRAP, top = 8))
        step(col, 5, "Gdy jest zielony ptaszek, otwórz Audiotekę. Pojawi się ramka „Nowa wersja” – stuknij Zainstaluj i potwierdź. Możesz też wejść w Ustawienia → Sprawdź aktualizacje.")

        col.addView(tv("Gdy coś pójdzie nie tak", 18f, true), lp(top = 24))
        step(col, 1, "Czerwony krzyżyk na GitHubie: stuknij build → build → Build debug APK, przewiń w górę do linijek zaczynających się od „e:” i wyślij ich zrzut Claude.")
        step(col, 2, "Termux pisze „nothing to commit”: wgrałeś ten sam plik co poprzednio – pobierz nowy Audioteka.zip.")
        step(col, 3, "Termux pyta o hasło albo pisze „Authentication failed”: token wygasł. Zrób nowy, potem w Termuksie wklej to polecenie, wpisując nowy token w miejsce NOWY_TOKEN:")
        col.addView(btn("Otwórz stronę tokenów GitHub") { openUrl("https://github.com/settings/tokens/new") }, lp(WRAP, WRAP, top = 8))
        code(col, TOKEN_CMD)
        step(col, 4, "Audioteka pisze „Token jest nieprawidłowy”: w Ustawieniach usuń token i wklej nowy (zaznacz przy tworzeniu: repo i workflow).")
        step(col, 5, "Android blokuje instalację: zezwól Audiotece na instalowanie aplikacji (telefon sam pokaże ten przełącznik) i stuknij Zainstaluj jeszcze raz.")

        col.addView(tv("Nowy telefon albo reset", 18f, true), lp(top = 24))
        step(col, 1, "Zainstaluj Termux z F-Droid, w Termuksie wpisz: termux-setup-storage oraz pkg install git unzip.")
        step(col, 2, "Pobierz projekt z GitHuba (podmień NOWY_TOKEN):")
        code(col, "cd ~ && git clone https://Bartoszelek:NOWY_TOKEN@github.com/${Updater.REPO}.git Audioteka")
        step(col, 3, "Najnowszy plik APK pobierzesz z GitHuba: repozytorium → Releases → audioteka.apk.")
        col.addView(btn("Otwórz wydania (Releases)") { openUrl("https://github.com/${Updater.REPO}/releases") }, lp(WRAP, WRAP, top = 8))
    }

    // ------------------------------------------------------------ aktualizacje
    private fun installedVersion(): Long = try {
        packageManager.getPackageInfo(packageName, 0).longVersionCode
    } catch (e: Exception) {
        0L
    }

    private fun versionName(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
    } catch (e: Exception) {
        "?"
    }

    private fun checkUpdate(manual: Boolean) {
        val token = Store.ghToken
        if (token.isBlank()) {
            if (manual) toast("Najpierw wklej token GitHub")
            return
        }
        if (manual) {
            updMsg = "Sprawdzam…"
            render()
        }
        Thread {
            val res: Pair<Updater.Info?, String> = try {
                val i = Updater.check(token)
                if (i != null && i.number > installedVersion()) Pair(i, "Dostępna wersja 1.${i.number}")
                else Pair(null, "Masz najnowszą wersję")
            } catch (e: Exception) {
                Pair(null, "Błąd: " + (e.message ?: "brak połączenia"))
            }
            h.post {
                update = res.first
                if (manual || res.first != null) {
                    updMsg = res.second
                    render()
                }
            }
        }.start()
    }

    private fun installUpdate() {
        val info = update ?: return
        if (!packageManager.canRequestPackageInstalls()) {
            toast("Zezwól Audiotece na instalowanie aplikacji, potem wróć i stuknij ponownie")
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
            } catch (e: Exception) {
            }
            return
        }
        updMsg = "Pobieram… 0%"
        render()
        val file = File(cacheDir, "update.apk")
        Thread {
            try {
                Updater.download(Store.ghToken, info, file) { p ->
                    h.post {
                        updMsg = "Pobieram… $p%"
                        updStatus?.text = updMsg
                    }
                }
                installApk(file)
                h.post {
                    updMsg = "Potwierdź instalację"
                    updStatus?.text = updMsg
                }
            } catch (e: Exception) {
                h.post {
                    updMsg = "Błąd: " + (e.message ?: "pobieranie nieudane")
                    render()
                }
            }
        }.start()
    }

    private fun installApk(file: File) {
        val pi = packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        val id = pi.createSession(params)
        val session = pi.openSession(id)
        try {
            session.openWrite("audioteka", 0, file.length()).use { out ->
                file.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val i = Intent(this, MainActivity::class.java).setAction(ACTION_INSTALL)
            val pend = PendingIntent.getActivity(
                this, 7, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            )
            session.commit(pend.intentSender)
        } finally {
            session.close()
        }
    }

    private fun handleInstall(i: Intent?) {
        if (i == null || i.action != ACTION_INSTALL) return
        when (i.getIntExtra(PackageInstaller.EXTRA_STATUS, -999)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = i.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                if (confirm != null) startActivity(confirm)
            }
            PackageInstaller.STATUS_SUCCESS -> toast("Zaktualizowano")
            -999 -> {}
            else -> {
                val msg = i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "nieznany błąd"
                updMsg = "Instalacja nieudana: $msg"
                render()
            }
        }
    }

    // ------------------------------------------------------------ foldery i skanowanie
    @Suppress("DEPRECATION")
    private fun pickFolder() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
        )
        try {
            startActivityForResult(i, REQ_FOLDER)
        } catch (e: Exception) {
            toast("Nie mogę otworzyć wyboru folderu")
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != Activity.RESULT_OK) return
        val uri = data?.data ?: return
        when (requestCode) {
            REQ_FOLDER -> {
                try {
                    contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } catch (e: Exception) {
                }
                val s = uri.toString()
                if (s !in Store.folders) Store.folders.add(s)
                Store.saveState()
                screen = "lib"
                rescan()
            }
            REQ_COVER -> {
                val b = Store.book(pendingBookId) ?: return
                saveCoverFrom(b, uri.toString())
            }
            REQ_MERGE -> {
                val b = Store.book(pendingBookId) ?: return
                runMerge(b, pendingKind, uri)
            }
        }
    }

    private fun rescan() {
        if (scanning) return
        scanning = true
        scanMsg = "Szukam nagrań…"
        render()
        Thread {
            val list = try {
                Scanner.scan(this) { m ->
                    h.post {
                        scanMsg = m
                        statusView?.text = m
                    }
                }
            } catch (e: Exception) {
                null
            }
            h.post {
                if (list != null) {
                    Store.saveLibrary(list)
                    Covers.clear()
                    cur?.let { c -> cur = Store.book(c.id) ?: c }
                }
                scanning = false
                render()
            }
        }.start()
    }
}
