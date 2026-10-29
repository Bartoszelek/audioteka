package pl.audioteka

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import org.json.JSONObject
import java.security.MessageDigest

/** Przeszukuje wybrane foldery (Storage Access Framework) i składa z plików książki. */
object Scanner {
    private val AUDIO = setOf("mp3", "m4a", "m4b", "mp4", "aac", "ogg", "opus", "flac", "wav")
    private val IMG = setOf("jpg", "jpeg", "png")
    private val COVER_NAMES = setOf("cover", "folder", "front", "okladka", "okładka")
    private val NOISE = Regex("\\b(rozdzial|rozdział|chapter|czesc|część|cz|part|track|cd|disc|odcinek|ep)\\b")
    private val NONWORD = Regex("[\\d\\W_]+")
    private val SUBPART = Regex("^(cd|disc|dysk|część|czesc|cz|part|tom)[\\s._-]*\\d+$", RegexOption.IGNORE_CASE)
    private val CHUNK = Regex("\\d+|\\D+")

    class Doc(val id: String, val uri: Uri, val name: String, val dir: Boolean, val size: Long, val mtime: Long) {
        var rel: String = name
    }

    private class Group(val title: String, val folderOk: Boolean, val dirIds: MutableList<String>) {
        val files = mutableListOf<Doc>()
    }

    fun ext(n: String) = n.substringAfterLast('.', "").lowercase()
    fun base(n: String) = if (n.contains('.')) n.substringBeforeLast('.') else n

    /** Sortowanie „naturalne”: Rozdział 2 przed Rozdział 10. */
    val natCmp = Comparator<String> { a, b ->
        val x = CHUNK.findAll(a.lowercase()).map { it.value }.toList()
        val y = CHUNK.findAll(b.lowercase()).map { it.value }.toList()
        var res = 0
        var k = 0
        while (res == 0 && k < minOf(x.size, y.size)) {
            val p = x[k]
            val q = y[k]
            res = if (p[0].isDigit() && q[0].isDigit()) {
                val pt = p.trimStart('0')
                val qt = q.trimStart('0')
                if (pt.length != qt.length) pt.length - qt.length else pt.compareTo(qt)
            } else p.compareTo(q)
            k++
        }
        if (res != 0) res else x.size - y.size
    }

    private fun stem(n: String): String {
        val s = NOISE.replace(base(n).lowercase(), " ")
        return NONWORD.replace(s, " ").trim()
    }

    private fun sha(s: String): String =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }.take(12)

    private fun children(ctx: Context, tree: Uri, parentId: String): List<Doc> {
        val out = ArrayList<Doc>()
        val q = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
        val cols = arrayOf(
            Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED
        )
        try {
            ctx.contentResolver.query(q, cols, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getString(0)
                    val name = c.getString(1) ?: ""
                    if (id != null && name.isNotEmpty()) {
                        out.add(
                            Doc(
                                id, DocumentsContract.buildDocumentUriUsingTree(tree, id), name,
                                c.getString(2) == Document.MIME_TYPE_DIR,
                                if (c.isNull(3)) 0L else c.getLong(3),
                                if (c.isNull(4)) 0L else c.getLong(4)
                            )
                        )
                    }
                }
            }
        } catch (e: Exception) {
        }
        return out
    }

    private fun displayName(ctx: Context, tree: Uri, docId: String): String? {
        return try {
            ctx.contentResolver.query(
                DocumentsContract.buildDocumentUriUsingTree(tree, docId),
                arrayOf(Document.COLUMN_DISPLAY_NAME), null, null, null
            )?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        } catch (e: Exception) {
            null
        }
    }

    private fun duration(ctx: Context, uri: Uri): Long {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(ctx, uri)
            r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } catch (e: Exception) {
            0L
        } finally {
            try {
                r.release()
            } catch (e: Exception) {
            }
        }
    }

    /** Folder „ładna nazwa” do pokazania w ustawieniach. */
    fun folderLabel(treeUri: String): String {
        val seg = Uri.parse(treeUri).lastPathSegment ?: treeUri
        val path = seg.substringAfter(':', seg)
        return if (seg.startsWith("primary:")) "Pamięć/" + path else path
    }

    fun scan(ctx: Context, status: (String) -> Unit): List<Book> {
        val cache = HashMap<String, Long>()
        Store.read("dur.json")?.let {
            try {
                val o = JSONObject(it)
                for (k in o.keys()) cache[k] = o.getLong(k)
            } catch (e: Exception) {
            }
        }
        val groups = LinkedHashMap<String, Group>()
        val images = HashMap<String, List<Doc>>()
        var count = 0

        for (t in Store.folders.toList()) {
            val tree = Uri.parse(t)
            val rootId: String = (try {
                DocumentsContract.getTreeDocumentId(tree)
            } catch (e: Exception) {
                null
            }) ?: continue
            val rootName = displayName(ctx, tree, rootId) ?: "Folder"

            fun walk(id: String, name: String, parentId: String?, parentName: String?, isRoot: Boolean) {
                val kids = children(ctx, tree, id)
                images[id] = kids.filter { !it.dir && ext(it.name) in IMG }
                val audio = kids.filter { !it.dir && ext(it.name) in AUDIO }
                if (audio.isNotEmpty()) {
                    if (isRoot) {
                        for ((k0, list) in audio.groupBy { stem(it.name) }) {
                            val k = k0.ifEmpty { "_" }
                            val title = when {
                                list.size == 1 -> base(list[0].name)
                                k == "_" -> rootName
                                else -> k.split(" ").joinToString(" ") { w -> w.replaceFirstChar { c -> c.uppercase() } }
                            }
                            val g = groups.getOrPut("$t|$k") { Group(title, false, mutableListOf(id)) }
                            g.files.addAll(list)
                        }
                    } else {
                        val sub = parentId != null && SUBPART.matches(name)
                        val key = if (sub) "$t|$parentId" else "$t|$id"
                        val g = groups.getOrPut(key) {
                            if (sub) Group(parentName ?: name, true, mutableListOf(parentId!!))
                            else Group(name, true, mutableListOf(id))
                        }
                        if (sub && !g.dirIds.contains(id)) g.dirIds.add(id)
                        for (d in audio) {
                            d.rel = if (sub) "$name/${d.name}" else d.name
                            g.files.add(d)
                        }
                    }
                    count += audio.size
                    status("Wczytuję… $count plików")
                }
                for (d in kids) {
                    if (d.dir && !d.name.startsWith(".")) walk(d.id, d.name, id, name, false)
                }
            }
            walk(rootId, rootName, null, null, true)
        }

        val out = ArrayList<Book>()
        var n = 0
        for ((key, g) in groups) {
            val files = g.files.sortedWith(compareBy(natCmp) { it.rel })
            val durs = files.map { d ->
                n++
                if (n % 3 == 0) status("Sprawdzam długość nagrań… $n/$count")
                val k = "${d.uri}|${d.size}|${d.mtime}"
                cache.getOrPut(k) { duration(ctx, d.uri) }
            }
            out.add(
                Book(
                    sha(key), g.title, files.map { it.uri.toString() }, files.map { base(it.name) },
                    durs, pickCover(g, images, files)
                )
            )
        }
        val o = JSONObject()
        for ((k, v) in cache) o.put(k, v)
        Store.write("dur.json", o.toString())
        return out.sortedWith(compareBy(natCmp) { it.title })
    }

    private fun pickCover(g: Group, images: Map<String, List<Doc>>, files: List<Doc>): String? {
        for (dirId in g.dirIds) {
            val imgs = images[dirId] ?: continue
            val named = imgs.firstOrNull { base(it.name).lowercase() in COVER_NAMES }
            if (named != null) return named.uri.toString()
            if (g.folderOk && imgs.isNotEmpty()) return imgs.sortedWith(compareBy(natCmp) { it.name }).first().uri.toString()
        }
        return if (files.isEmpty()) null else "emb:" + files.first().uri.toString()
    }
}
