package pl.audioteka

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Jedna książka: folder z wieloma plikami albo pojedyncze długie nagranie. */
class Book(
    val id: String,
    val title: String,
    val files: List<String>,
    val names: List<String>,
    val durs: List<Long>,
    val cover: String?
) {
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

/** Wspólny stan aplikacji (ten sam proces dla ekranu i usługi odtwarzania). */
object Store {
    private var dir: File? = null

    val folders = mutableListOf<String>()
    var back = 15
    var fwd = 30
    var autorewind = true
    var invert = false
    var ghToken = ""

    @Volatile var books: List<Book> = emptyList()
    private val progress = HashMap<String, Progress>()
    val hidden = HashSet<String>()
    var statTotal = 0L
    val statDays = HashMap<String, Long>()

    // Wyłącznik czasowy – czyta go usługa odtwarzania
    @Volatile var sleepEnd = 0L
    @Volatile var sleepMinutes = 0
    @Volatile var sleepChapter = false

    @Synchronized
    fun init(ctx: Context) {
        if (dir != null) return
        dir = ctx.applicationContext.filesDir
        loadState()
        loadLibrary()
    }

    fun book(id: String?): Book? = if (id == null) null else books.firstOrNull { it.id == id }

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
            a.put(
                JSONObject().put("id", b.id).put("title", b.title).put("files", JSONArray(b.files))
                    .put("names", JSONArray(b.names)).put("durs", JSONArray(b.durs)).put("cover", b.cover ?: "")
            )
        }
        write("library.json", a.toString())
    }

    private fun loadLibrary() {
        val s = read("library.json") ?: return
        try {
            val a = JSONArray(s)
            val out = ArrayList<Book>()
            for (n in 0 until a.length()) {
                val o = a.getJSONObject(n)
                val f = o.getJSONArray("files")
                val nm = o.getJSONArray("names")
                val d = o.getJSONArray("durs")
                out.add(
                    Book(
                        o.getString("id"), o.getString("title"),
                        (0 until f.length()).map { f.getString(it) },
                        (0 until nm.length()).map { nm.getString(it) },
                        (0 until d.length()).map { d.getLong(it) },
                        o.optString("cover").ifEmpty { null }
                    )
                )
            }
            books = out
        } catch (e: Exception) {
        }
    }
}
