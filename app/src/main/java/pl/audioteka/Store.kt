package pl.audioteka

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Rozdział: plik nr [i], początek [start] ms w tym pliku. */
class Chap(val i: Int, val start: Long, val title: String)

/** Jedna książka: folder z wieloma plikami albo pojedyncze długie nagranie. */
class Book(
    val id: String,
    val title: String,
    val files: List<String>,
    val names: List<String>,
    val durs: List<Long>,
    val cover: String?,
    val imgs: List<String>,
    chapters: List<Chap>
) {
    val ch: List<Chap> = chapters.ifEmpty { names.mapIndexed { i, n -> Chap(i, 0, n) } }
    val total: Long get() = durs.sum()
}

class Bookmark(val i: Int, val pos: Long, val note: String, val ts: Long)

class Progress(
    var i: Int = 0,
    var pos: Long = 0,
    var speed: Float = 1f,
    var updated: Long = 0,
    var done: Boolean = false,
    val bm: MutableList<Bookmark> = mutableListOf()
)

/** Pomocnicze obliczenia rozdziałów. */
object Ch {
    fun index(b: Book, i: Int, pos: Long): Int {
        var k = 0
        for ((n, c) in b.ch.withIndex()) {
            if (c.i < i || (c.i == i && c.start <= pos + 500)) k = n else break
        }
        return k
    }

    /** Koniec rozdziału k w milisekundach pliku (lub długość pliku). */
    fun end(b: Book, k: Int, fileDur: Long): Long {
        val c = b.ch[k]
        val nx = b.ch.getOrNull(k + 1)
        return if (nx != null && nx.i == c.i) nx.start else fileDur
    }

    fun label(b: Book, k: Int): String =
        (if (b.ch.size > 1) "${k + 1}/${b.ch.size} · " else "") + (b.ch.getOrNull(k)?.title ?: "")
}

/** Wspólny stan aplikacji (ten sam proces dla ekranu, usługi i widżetu). */
object Store {
    private var dir: File? = null

    val folders = mutableListOf<String>()
    var back = 15
    var fwd = 30
    var autorewind = true
    var invert = false
    var ghToken = ""
    var skipSilence = false
    var boost = 0
    val covers = HashMap<String, String>()

    @Volatile var books: List<Book> = emptyList()
    private val progress = HashMap<String, Progress>()
    val hidden = HashSet<String>()
    var statTotal = 0L
    val statDays = HashMap<String, Long>()

    // Wyłącznik czasowy – czyta go usługa odtwarzania
    @Volatile var sleepEnd = 0L
    @Volatile var sleepMinutes = 0
    @Volatile var sleepChapter = false
    @Volatile var sleepUntilI = -1
    @Volatile var sleepUntilPos = 0L

    @Synchronized
    fun init(ctx: Context) {
        if (dir != null) return
        dir = ctx.applicationContext.filesDir
        loadState()
        loadLibrary()
    }

    fun book(id: String?): Book? = if (id == null) null else books.firstOrNull { it.id == id }

    fun lastBook(): Book? = books.filter { it.id !in hidden }
        .filter { val p = peek(it.id); p.updated > 0 && !p.done }
        .maxByOrNull { peek(it.id).updated }

    fun coverOf(b: Book): String? = covers[b.id] ?: b.cover

    @Synchronized
    fun prog(id: String): Progress = progress.getOrPut(id) { Progress() }

    @Synchronized
    fun peek(id: String): Progress = progress[id] ?: Progress()

    fun today(): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    @Synchronized
    fun addListened(ms: Long) {
        statTotal += ms
        val d = today()
        statDays[d] = (statDays[d] ?: 0L) + ms
    }

    fun clearSleep() {
        sleepEnd = 0
        sleepMinutes = 0
        sleepChapter = false
        sleepUntilI = -1
        sleepUntilPos = 0
    }

    fun pct(b: Book): Int {
        val p = peek(b.id)
        if (p.done) return 100
        if (b.total <= 0) return 0
        val doneMs = b.durs.take(p.i).sum() + p.pos
        return (doneMs * 100 / b.total).toInt().coerceIn(0, 99)
    }

    // ------------------------------------------------------------ pliki
    private fun file(n: String) = File(dir, n)

    @Synchronized
    fun write(n: String, s: String) {
        try {
            val t = file("$n.tmp")
            t.writeText(s)
            if (!t.renameTo(file(n))) {
                file(n).writeText(s)
                t.delete()
            }
        } catch (e: Exception) {
        }
    }

    fun read(n: String): String? = try {
        val f = file(n)
        if (f.exists()) f.readText() else null
    } catch (e: Exception) {
        null
    }

    @Synchronized
    fun saveState() {
        val o = JSONObject()
        o.put("folders", JSONArray(folders))
        o.put("back", back)
        o.put("fwd", fwd)
        o.put("autorewind", autorewind)
        o.put("invert", invert)
        o.put("ghToken", ghToken)
        o.put("skipSilence", skipSilence)
        o.put("boost", boost)
        val cv = JSONObject()
        for ((k, v) in covers) cv.put(k, v)
        o.put("covers", cv)
        o.put("hidden", JSONArray(hidden.toList()))
        o.put("statTotal", statTotal)
        val sd = JSONObject()
        for ((k, v) in statDays) sd.put(k, v)
        o.put("statDays", sd)
        val pr = JSONObject()
        for ((k, p) in progress) {
            val bm = JSONArray()
            for (b in p.bm) {
                bm.put(JSONObject().put("i", b.i).put("pos", b.pos).put("note", b.note).put("ts", b.ts))
            }
            pr.put(
                k, JSONObject().put("i", p.i).put("pos", p.pos).put("speed", p.speed.toDouble())
                    .put("updated", p.updated).put("done", p.done).put("bm", bm)
            )
        }
        o.put("progress", pr)
        write("state.json", o.toString())
    }

    private fun loadState() {
        val s = read("state.json") ?: return
        try {
            val o = JSONObject(s)
            folders.clear()
            o.optJSONArray("folders")?.let { a -> for (n in 0 until a.length()) folders.add(a.getString(n)) }
            back = o.optInt("back", 15)
            fwd = o.optInt("fwd", 30)
            autorewind = o.optBoolean("autorewind", true)
            invert = o.optBoolean("invert", false)
            ghToken = o.optString("ghToken", "")
            skipSilence = o.optBoolean("skipSilence", false)
            boost = o.optInt("boost", 0)
            o.optJSONObject("covers")?.let { c -> for (k in c.keys()) covers[k] = c.optString(k) }
            o.optJSONArray("hidden")?.let { a -> for (n in 0 until a.length()) hidden.add(a.getString(n)) }
            statTotal = o.optLong("statTotal", 0)
            o.optJSONObject("statDays")?.let { d -> for (k in d.keys()) statDays[k] = d.optLong(k) }
            o.optJSONObject("progress")?.let { pr ->
                for (k in pr.keys()) {
                    val p = pr.getJSONObject(k)
                    val bm = mutableListOf<Bookmark>()
                    p.optJSONArray("bm")?.let { a ->
                        for (n in 0 until a.length()) {
                            val b = a.getJSONObject(n)
                            bm.add(Bookmark(b.optInt("i"), b.optLong("pos"), b.optString("note"), b.optLong("ts")))
                        }
                    }
                    progress[k] = Progress(
                        p.optInt("i"), p.optLong("pos"), p.optDouble("speed", 1.0).toFloat(),
                        p.optLong("updated"), p.optBoolean("done"), bm
                    )
                }
            }
        } catch (e: Exception) {
        }
    }

    fun saveLibrary(list: List<Book>) {
        books = list
        val a = JSONArray()
        for (b in list) {
            val ch = JSONArray()
            for (c in b.ch) ch.put(JSONObject().put("i", c.i).put("s", c.start).put("t", c.title))
            a.put(
                JSONObject().put("id", b.id).put("title", b.title).put("files", JSONArray(b.files))
                    .put("names", JSONArray(b.names)).put("durs", JSONArray(b.durs)).put("cover", b.cover ?: "")
                    .put("imgs", JSONArray(b.imgs)).put("ch", ch)
            )
        }
        write("library.json", a.toString())
    }

    private fun strings(a: JSONArray?): List<String> =
        if (a == null) emptyList() else (0 until a.length()).map { a.getString(it) }

    private fun loadLibrary() {
        val s = read("library.json") ?: return
        try {
            val a = JSONArray(s)
            val out = ArrayList<Book>()
            for (n in 0 until a.length()) {
                val o = a.getJSONObject(n)
                val d = o.getJSONArray("durs")
                val chs = ArrayList<Chap>()
                o.optJSONArray("ch")?.let { c ->
                    for (k in 0 until c.length()) {
                        val x = c.getJSONObject(k)
                        chs.add(Chap(x.optInt("i"), x.optLong("s"), x.optString("t")))
                    }
                }
                out.add(
                    Book(
                        o.getString("id"), o.getString("title"),
                        strings(o.getJSONArray("files")), strings(o.getJSONArray("names")),
                        (0 until d.length()).map { d.getLong(it) },
                        o.optString("cover").ifEmpty { null }, strings(o.optJSONArray("imgs")), chs
                    )
                )
            }
            books = out
        } catch (e: Exception) {
        }
    }
}
