package pl.audioteka

import android.Manifest
import android.app.Activity
import android.app.Dialog
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.util.LruCache
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
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    private val REQ_FOLDER = 42

    private var controller: MediaController? = null
    private var future: ListenableFuture<MediaController>? = null
    private lateinit var root: FrameLayout
    private val h = Handler(Looper.getMainLooper())
    private val io = Executors.newFixedThreadPool(2)
    private val covers = LruCache<String, Bitmap>(40)
    private val noCover = HashSet<String>()

    private var screen = "lib"
    private var tab = "prog"
    private var query = ""
    private var cur: Book? = null
    private var scanning = false
    private var scanMsg = ""
    private var statusView: TextView? = null

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

    private val fg get() = if (Store.invert) Color.WHITE else Color.BLACK
    private val bg get() = if (Store.invert) Color.BLACK else Color.WHITE
    private val dim get() = if (Store.invert) Color.rgb(170, 170, 170) else Color.rgb(85, 85, 85)
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
            if (cur == null) cur = Store.book(controller?.currentMediaItem?.mediaId?.substringBefore("|"))
            if (screen != "settings") render()
        }, ContextCompat.getMainExecutor(this))
        h.post(ui)
    }

    override fun onStop() {
        h.removeCallbacks(ui)
        controller?.removeListener(ctlListener)
        future?.let { MediaController.releaseFuture(it) }
        controller = null
        super.onStop()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (screen != "lib") {
            screen = "lib"
            render()
        } else {
            @Suppress("DEPRECATION")
            super.onBackPressed()
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

    private fun border(stroke: Int = 2, radius: Int = 10, fill: Int = bg) = GradientDrawable().apply {
        setColor(fill)
        setStroke(dp(stroke), fg)
        cornerRadius = dp(radius).toFloat()
    }

    private fun btn(text: String, selected: Boolean = false, size: Float = 15f, onClick: () -> Unit): TextView =
        tv(text, size, true, if (selected) bg else fg).apply {
            background = border(fill = if (selected) fg else bg)
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
        setBackgroundColor(fg)
        layoutParams = LinearLayout.LayoutParams(MATCH, dp(1))
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

    private fun bar(pct: Int): View {
        val o = LinearLayout(this)
        o.background = border(1, 0)
        o.setPadding(dp(1), dp(1), dp(1), dp(1))
        o.addView(View(this).apply { setBackgroundColor(fg) },
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
        root.setBackgroundColor(bg)
        window.statusBarColor = bg
        window.navigationBarColor = bg
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = if (Store.invert) 0 else
            (View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR)
    }

    // ------------------------------------------------------------ okładki
    private fun coverView(b: Book, sizeDp: Int): View {
        val f = FrameLayout(this)
        f.background = border(2, 6)
        f.setPadding(dp(2), dp(2), dp(2), dp(2))
        val letter = tv(b.title.trim().take(1).uppercase(), sizeDp / 2.6f, true).apply { gravity = Gravity.CENTER }
        f.addView(letter, FrameLayout.LayoutParams(MATCH, MATCH))
        val iv = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            colorFilter = gray
            tag = b.id
        }
        f.addView(iv, FrameLayout.LayoutParams(MATCH, MATCH))
        val cached = covers.get(b.id)
        if (cached != null) {
            iv.setImageBitmap(cached)
        } else if (b.id !in noCover) {
            io.execute {
                val bm = decodeCover(b)
                h.post {
                    if (bm != null) {
                        covers.put(b.id, bm)
                        if (iv.tag == b.id) iv.setImageBitmap(bm)
                    } else noCover.add(b.id)
                }
            }
        }
        f.layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
        return f
    }

    private fun decodeCover(b: Book): Bitmap? {
        val c = b.cover ?: return null
        return try {
            val bytes: ByteArray? = if (c.startsWith("emb:")) {
                val r = MediaMetadataRetriever()
                try {
                    r.setDataSource(this, Uri.parse(c.removePrefix("emb:")))
                    r.embeddedPicture
                } finally {
                    try {
                        r.release()
                    } catch (e: Exception) {
                    }
                }
            } else contentResolver.openInputStream(Uri.parse(c))?.use { it.readBytes() }
            if (bytes == null) return null
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
            var s = 1
            while (o.outWidth / (s * 2) >= 400 && o.outHeight / (s * 2) >= 400) s *= 2
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = s })
        } catch (e: Exception) {
            null
        }
    }

    // ------------------------------------------------------------ ekrany
    private fun render() {
        applyWindow()
        root.removeAllViews()
        vChapter = null; vSeek = null; vCur = null; vDur = null; vLeft = null; vPP = null; vSleep = null; vSpeed = null
        statusView = null
        when (screen) {
            "player" -> renderPlayer()
            "settings" -> renderSettings()
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

        val last = vis.filter { val p = Store.peek(it.id); p.updated > 0 && !p.done }.maxByOrNull { Store.peek(it.id).updated }
        if (last != null) {
            val card = hbox().apply {
                background = border(3, 14)
                setPadding(dp(14), dp(14), dp(14), dp(14))
                gravity = Gravity.TOP
            }
            card.addView(coverView(last, 84))
            val t = vbox()
            t.addView(tv(last.title, 18f, true).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END })
            val p = Store.peek(last.id)
            t.addView(tv(chapterLabel(last, p.i), 13f, color = dim).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
            t.addView(bar(Store.pct(last)), lp(top = 8))
            t.addView(btn("Słuchaj dalej") { openBook(last, true) }, lp(top = 10))
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
            isSingleLine = true
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
            val meta = hm(b.total) + (if (b.files.size > 1) " · ${b.files.size} plików" else "") +
                (if (p.done) " · ukończona" else if (Store.pct(b) > 0) " · ${Store.pct(b)}%" else "")
            t.addView(tv(meta, 13f, color = dim))
            t.addView(bar(Store.pct(b)), lp(top = 6))
            r.addView(t, lp(0, WRAP, 1f).apply { leftMargin = dp(12); rightMargin = dp(6) })
            r.addView(icon(R.drawable.ic_more, 30) { bookMenu(b) })
            r.setOnClickListener { openBook(b, true) }
            list.addView(r)
            list.addView(line())
        }
    }

    private fun chapterLabel(b: Book, i: Int): String =
        (if (b.files.size > 1) "${i + 1}/${b.files.size} · " else "") + b.names.getOrElse(i) { "" }

    // ------------------------------------------------------------ odtwarzanie
    private fun activeController(): MediaController? {
        val b = cur ?: return null
        val c = controller ?: return null
        return if (c.currentMediaItem?.mediaId?.substringBefore("|") == b.id) c else null
    }

    private fun openBook(b: Book, play: Boolean) {
        val c = controller
        if (c == null) {
            toast("Odtwarzacz się uruchamia – spróbuj za chwilę")
            return
        }
        val curId = c.currentMediaItem?.mediaId?.substringBefore("|")
        if (curId != b.id) {
            if (curId != null) {
                val old = Store.prog(curId)
                old.i = c.currentMediaItemIndex
                old.pos = c.currentPosition
                old.updated = System.currentTimeMillis()
                Store.saveState()
            }
            val items = b.files.mapIndexed { i, u ->
                MediaItem.Builder().setUri(Uri.parse(u)).setMediaId("${b.id}|$i")
                    .setMediaMetadata(
                        MediaMetadata.Builder().setTitle(b.names[i]).setArtist(b.title).setAlbumTitle(b.title).build()
                    ).build()
            }
            val p = Store.prog(b.id)
            if (p.done) {
                p.done = false
                p.i = 0
                p.pos = 0
            }
            c.setMediaItems(items, p.i.coerceIn(0, items.size - 1), p.pos)
            c.setPlaybackSpeed(p.speed)
            c.prepare()
        }
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
        val c = activeController() ?: return
        val b = cur ?: return
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
        col.addView(cv)
        col.addView(tv(b.title, 20f, true).apply { gravity = Gravity.CENTER }, lp(top = 14))
        val chV = tv("", 15f, color = dim).apply { gravity = Gravity.CENTER; maxLines = 2 }
        vChapter = chV
        col.addView(chV, lp(top = 4))

        val seek = SeekBar(this).apply {
            max = 1000
            progressTintList = ColorStateList.valueOf(fg)
            thumbTintList = ColorStateList.valueOf(fg)
            progressBackgroundTintList = ColorStateList.valueOf(dim)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {}
                override fun onStartTrackingTouch(s: SeekBar?) {
                    dragging = true
                }

                override fun onStopTrackingTouch(s: SeekBar?) {
                    dragging = false
                    val c = activeController() ?: return
                    val d = c.duration
                    if (d != C.TIME_UNSET && d > 0) c.seekTo(d * (s?.progress ?: 0) / 1000)
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
            v.addView(icon(res, 30))
            v.addView(tv(label, 12f, true))
            return v
        }
        cell(icon(R.drawable.ic_prev, 30) {
            val c = activeController()
            if (c != null) {
                if (c.currentPosition > 5000 || c.currentMediaItemIndex == 0) c.seekTo(0) else goTo(c.currentMediaItemIndex - 1, 0)
            }
        })
        cell(labeled(R.drawable.ic_back, "${Store.back} s") { seekBy(-Store.back * 1000L) })
        val ppBox = FrameLayout(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(bg)
                setStroke(dp(3), fg)
            }
            setOnClickListener { togglePlay() }
        }
        val ppIcon = ImageView(this).apply { setImageResource(R.drawable.ic_play); setColorFilter(fg) }
        vPP = ppIcon
        ppBox.addView(ppIcon, FrameLayout.LayoutParams(dp(36), dp(36), Gravity.CENTER))
        val ppWrap = FrameLayout(this)
        ppWrap.addView(ppBox, FrameLayout.LayoutParams(dp(78), dp(78), Gravity.CENTER))
        ctl.addView(ppWrap, lp(0, WRAP, 1.3f))
        cell(labeled(R.drawable.ic_fwd, "${Store.fwd} s") { seekBy(Store.fwd * 1000L) })
        cell(icon(R.drawable.ic_next, 30) {
            val c = activeController()
            if (c != null && c.currentMediaItemIndex < b.files.size - 1) goTo(c.currentMediaItemIndex + 1, 0)
        })
        col.addView(ctl, lp(top = 18))

        val leftV = tv("", 14f, color = dim).apply { gravity = Gravity.CENTER }
        vLeft = leftV
        col.addView(leftV, lp(top = 12))

        val opts = hbox()
        fun opt(top: View, label: TextView, onClick: () -> Unit) {
            val v = vbox().apply {
                gravity = Gravity.CENTER_HORIZONTAL
                background = border()
                setPadding(dp(4), dp(10), dp(4), dp(10))
                setOnClickListener { onClick() }
            }
            v.addView(top)
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
        val dur = if (d0 != C.TIME_UNSET && d0 > 0) d0 else b.durs.getOrElse(i) { 0L }
        vChapter?.text = chapterLabel(b, i)
        if (!dragging) vSeek?.progress = if (dur > 0) (pos * 1000 / dur).toInt() else 0
        vCur?.text = hms(pos)
        vDur?.text = hms(dur)
        val speed = c?.playbackParameters?.speed ?: p.speed
        val left = b.durs.drop(i + 1).sum() + maxOf(0L, dur - pos)
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
    private fun sheet(title: String, build: (LinearLayout, Dialog) -> Unit) {
        val d = Dialog(this)
        d.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val col = vbox().apply {
            setPadding(dp(16), dp(14), dp(16), dp(22))
            background = GradientDrawable().apply {
                setColor(bg)
                setStroke(dp(3), fg)
            }
        }
        val head = hbox()
        head.addView(tv(title, 19f, true), lp(0, WRAP, 1f))
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
        val s = (Math.round(v.coerceIn(0.5f, 3f) * 100) / 100f)
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
                    Store.sleepChapter = false
                    Store.sleepMinutes = m
                    Store.sleepEnd = System.currentTimeMillis() + m * 60_000L
                    activeController()?.let { if (!it.playWhenReady) it.play() }
                    d.dismiss()
                }
            } + btn("Koniec rozdz.", size = 14f) {
                Store.sleepEnd = 0
                Store.sleepMinutes = 0
                Store.sleepChapter = true
                activeController()?.let { if (!it.playWhenReady) it.play() }
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
            bm.toList().forEachIndexed { k, z ->
                val t = vbox()
                t.addView(tv(chapterLabel(b, z.i), 15f, true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
                t.addView(tv(hms(z.pos) + if (z.note.isNotEmpty()) " · ${z.note}" else "", 13f, color = dim))
                val right = hbox()
                right.addView(btn("Idź", size = 13f) {
                    if (activeController() == null) openBook(b, false)
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
            col.addView(tv(chapterLabel(b, i) + " · " + hms(pos), 14f, color = dim), lp(top = 6))
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
        val curI = activeController()?.currentMediaItemIndex ?: Store.peek(b.id).i
        sheet("Rozdziały (${b.files.size})") { col, d ->
            b.names.forEachIndexed { i, n ->
                val left = tv("${i + 1}. $n", 15f, i == curI).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END }
                item(col, left, tv(hms(b.durs.getOrElse(i) { 0L }), 13f, color = dim).apply { setPadding(dp(8), 0, 0, 0) }) {
                    if (activeController() == null) openBook(b, false)
                    goTo(i, 0)
                    d.dismiss()
                }
            }
        }
    }

    private fun bookMenu(b: Book) {
        val p = Store.peek(b.id)
        sheet(b.title) { col, d ->
            col.addView(btn(if (p.done) "Oznacz jako nieukończoną" else "Oznacz jako ukończoną") {
                val q = Store.prog(b.id)
                q.done = !q.done
                q.updated = System.currentTimeMillis()
                Store.saveState()
                d.dismiss()
                render()
            }, lp(top = 10))
            col.addView(btn("Zacznij od początku") {
                val q = Store.prog(b.id)
                q.done = false
                q.i = 0
                q.pos = 0
                Store.saveState()
                if (cur?.id == b.id) goTo(0, 0)
                d.dismiss()
                render()
            }, lp(top = 8))
            col.addView(btn("Ukryj z biblioteki") {
                Store.hidden.add(b.id)
                Store.saveState()
                d.dismiss()
                if (screen == "player") screen = "lib"
                render()
            }, lp(top = 8))
        }
    }

    // ------------------------------------------------------------ ustawienia
    private fun seg(col: LinearLayout, label: String, opts: List<Pair<String, Boolean>>, onPick: (Int) -> Unit) {
        col.addView(tv(label, 15f), lp(top = 14))
        val r = hbox()
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
        seg(col, "Kolory", listOf("Czarne na białym" to !Store.invert, "Białe na czarnym" to Store.invert)) { n ->
            Store.invert = n == 1; Store.saveState(); render()
        }

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
        col.addView(tv("Audioteka 1.0 · działa bez internetu", 12f, color = dim), lp(top = 16))
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
        if (requestCode != REQ_FOLDER || resultCode != Activity.RESULT_OK) return
        val uri = data?.data ?: return
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
                if (list != null) Store.setBooks(list)
                scanning = false
                render()
            }
        }.start()
    }
}
