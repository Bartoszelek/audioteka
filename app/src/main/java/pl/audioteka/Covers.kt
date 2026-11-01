package pl.audioteka

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Typeface
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.util.LruCache
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.abs

/** Okładki: z folderu, z pliku, z galerii, z internetu albo wygenerowane w stylu e-ink. */
object Covers {
    private val mem = LruCache<String, Bitmap>(60)

    private fun key(b: Book) = b.id + "|" + (Store.coverOf(b) ?: "") + "|" + (if (Store.eink) "e" else "c")

    fun cached(b: Book): Bitmap? = mem.get(key(b))

    fun clear() = mem.evictAll()

    /** Zawsze zwraca obraz – jeśli nie ma okładki, generuje własną. */
    fun load(ctx: Context, b: Book): Bitmap {
        val k = key(b)
        mem.get(k)?.let { return it }
        val src = Store.coverOf(b)
        var bm: Bitmap? = null
        if (src != null && src != "gen") bm = decode(ctx, src, 480)
        if (bm == null) bm = generate(b)
        mem.put(k, bm)
        return bm
    }

    fun decode(ctx: Context, src: String, max: Int): Bitmap? = try {
        val bytes: ByteArray? = when {
            src.startsWith("emb:") -> {
                val r = MediaMetadataRetriever()
                try {
                    r.setDataSource(ctx, Uri.parse(src.removePrefix("emb:")))
                    r.embeddedPicture
                } finally {
                    try {
                        r.release()
                    } catch (e: Exception) {
                    }
                }
            }
            src.startsWith("file:") -> File(src.removePrefix("file:")).let { if (it.exists()) it.readBytes() else null }
            src.startsWith("http") -> fetch(src)
            else -> ctx.contentResolver.openInputStream(Uri.parse(src))?.use { it.readBytes() }
        }
        if (bytes == null) null else sample(bytes, max)
    } catch (e: Throwable) {
        null
    }

    fun sample(bytes: ByteArray, max: Int): Bitmap? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
        if (o.outWidth <= 0) return null
        var s = 1
        while (o.outWidth / (s * 2) >= max && o.outHeight / (s * 2) >= max) s *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = s })
    }

    /** Kopia w odcieniach szarości (np. do widżetu). */
    fun gray(src: Bitmap, size: Int): Bitmap = square(src, size, true)

    /** Kwadratowa miniatura (w kolorze albo w odcieniach szarości). */
    fun square(src: Bitmap, size: Int, grayscale: Boolean = false): Bitmap {
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val p = Paint(Paint.FILTER_BITMAP_FLAG).apply {
            if (grayscale) colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) })
        }
        val s = minOf(src.width, src.height)
        val sx = (src.width - s) / 2
        val sy = (src.height - s) / 2
        c.drawBitmap(src, android.graphics.Rect(sx, sy, sx + s, sy + s), android.graphics.Rect(0, 0, size, size), p)
        return out
    }

    /** Czarno-biała okładka z wzorem zależnym od tytułu. */
    fun generate(b: Book): Bitmap {
        val size = 480
        val bm = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bm)
        val h = abs(b.id.hashCode())
        val pal = intArrayOf(0xFF4F46E5.toInt(), 0xFF0284C7.toInt(), 0xFF15803D.toInt(), 0xFFE11D74.toInt(),
            0xFFEA580C.toInt(), 0xFF7C3AED.toInt(), 0xFF0F766E.toInt(), 0xFFB45309.toInt())
        val ink = if (Store.eink) Color.BLACK else pal[(h / 7) % pal.size]
        c.drawColor(if (Store.eink) Color.WHITE else Theme.blend(Color.WHITE, ink, 0.14f))
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ink
            style = Paint.Style.STROKE
            strokeWidth = 6f
        }
        c.save()
        c.clipRect(24f, 24f, 456f, 268f)
        when (h % 5) {
            0 -> for (y in 36 until 268 step 22) c.drawLine(24f, y.toFloat(), 456f, y.toFloat(), p)
            1 -> for (r in 18 until 300 step 26) c.drawCircle(240f, 146f, r.toFloat(), p)
            2 -> for (x in -260 until 480 step 28) c.drawLine(x.toFloat(), 268f, (x + 244).toFloat(), 24f, p)
            3 -> {
                p.style = Paint.Style.FILL
                for (y in 40 until 268 step 32) for (x in 40 until 456 step 32) c.drawCircle(x.toFloat(), y.toFloat(), 7f, p)
                p.style = Paint.Style.STROKE
            }
            else -> {
                for (k in 0 until 6) {
                    val r = 30f + k * 34f
                    c.drawRect(240f - r, 146f - r * 0.6f, 240f + r, 146f + r * 0.6f, p)
                }
            }
        }
        c.restore()
        p.strokeWidth = 12f
        c.drawRect(6f, 6f, size - 6f, size - 6f, p)
        p.strokeWidth = 5f
        c.drawLine(24f, 284f, 456f, 284f, p)

        val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (Store.eink) Color.BLACK else Theme.blend(ink, Color.BLACK, 0.45f)
            typeface = Typeface.DEFAULT_BOLD
        }
        val text = b.title.ifBlank { "?" }
        var ts = 54f
        var layout: StaticLayout
        do {
            ts -= 4f
            tp.textSize = ts
            layout = StaticLayout.Builder.obtain(text, 0, text.length, tp, 424)
                .setAlignment(Layout.Alignment.ALIGN_CENTER).setMaxLines(4)
                .setEllipsize(TextUtils.TruncateAt.END).build()
        } while (layout.height > 160 && ts > 26f)
        c.save()
        c.translate(28f, 296f + (164 - layout.height) / 2f)
        layout.draw(c)
        c.restore()
        return bm
    }

    // ------------------------------------------------------------ zapisywanie i internet
    fun save(ctx: Context, b: Book, bm: Bitmap): String {
        val dir = File(ctx.filesDir, "covers")
        dir.mkdirs()
        val f = File(dir, b.id + "_" + System.currentTimeMillis() + ".jpg")
        f.outputStream().use { bm.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        dir.listFiles()?.filter { it.name.startsWith(b.id + "_") && it.name != f.name }?.forEach { it.delete() }
        return "file:" + f.path
    }

    private fun fetch(url: String): ByteArray? {
        var u = url
        repeat(4) {
            val c = URL(u).openConnection() as HttpURLConnection
            c.connectTimeout = 12_000
            c.readTimeout = 20_000
            c.instanceFollowRedirects = false
            c.setRequestProperty("User-Agent", "Audioteka/1.0 (Android)")
            val code = c.responseCode
            if (code in 300..399) {
                u = c.getHeaderField("Location") ?: return null
                if (u.startsWith("//")) u = "https:$u"
                c.disconnect()
            } else {
                return if (code == 200) c.inputStream.use { it.readBytes() } else null
            }
        }
        return null
    }

    /** Szuka okładek po tytule (Google Books + Open Library). Zwraca adresy obrazków. */
    fun search(query: String): List<String> {
        val out = ArrayList<String>()
        val q = URLEncoder.encode(query, "UTF-8")
        try {
            val j = JSONObject(String(fetch("https://www.googleapis.com/books/v1/volumes?q=$q&maxResults=12") ?: ByteArray(0)))
            val items = j.optJSONArray("items")
            if (items != null) for (k in 0 until items.length()) {
                val img = items.getJSONObject(k).optJSONObject("volumeInfo")?.optJSONObject("imageLinks") ?: continue
                val t = img.optString("thumbnail").ifEmpty { img.optString("smallThumbnail") }
                if (t.isNotEmpty()) out.add(t.replace("http://", "https://").replace("&edge=curl", ""))
            }
        } catch (e: Exception) {
        }
        try {
            val j = JSONObject(String(fetch("https://openlibrary.org/search.json?q=$q&limit=12&fields=cover_i") ?: ByteArray(0)))
            val docs = j.optJSONArray("docs")
            if (docs != null) for (k in 0 until docs.length()) {
                val id = docs.getJSONObject(k).optLong("cover_i", 0)
                if (id > 0) out.add("https://covers.openlibrary.org/b/id/$id-L.jpg")
            }
        } catch (e: Exception) {
        }
        return out.distinct().take(16)
    }
}
