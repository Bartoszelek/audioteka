package pl.audioteka

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.Charset

/** Odczyt rozdziałów zapisanych wewnątrz plików (m4b/m4a: chpl i ścieżka rozdziałów, mp3: ID3 CHAP). */
object Chapters {
    class C(val start: Long, val title: String)

    fun read(ctx: Context, uri: Uri, ext: String): List<C> {
        return try {
            val pfd = ctx.contentResolver.openFileDescriptor(uri, "r") ?: return emptyList()
            ParcelFileDescriptor.AutoCloseInputStream(pfd).use { ins ->
                val ch = ins.channel
                val list = when (ext) {
                    "mp3" -> id3(ch)
                    "m4b", "m4a", "mp4" -> mp4(ch)
                    else -> emptyList()
                }
                if (list.size >= 2) list.sortedBy { it.start } else emptyList()
            }
        } catch (e: Throwable) {
            emptyList()
        }
    }

    private fun readAt(ch: FileChannel, pos: Long, n: Int): ByteBuffer {
        val b = ByteBuffer.allocate(n)
        var p = pos
        while (b.hasRemaining()) {
            val r = ch.read(b, p)
            if (r <= 0) break
            p += r
        }
        b.flip()
        return b
    }

    private fun u8(b: ByteBuffer) = b.get().toInt() and 0xFF
    private fun u16(b: ByteBuffer) = b.short.toInt() and 0xFFFF
    private fun u32(b: ByteBuffer) = b.int.toLong() and 0xFFFFFFFFL

    // ------------------------------------------------------------ MP4
    private class Box(val type: String, val start: Long, val data: Long, val end: Long)

    private fun boxes(ch: FileChannel, from: Long, to: Long): List<Box> {
        val out = ArrayList<Box>()
        var p = from
        while (p + 8 <= to && out.size < 10_000) {
            val h = readAt(ch, p, 16)
            if (h.remaining() < 8) break
            var size = u32(h)
            val t = ByteArray(4)
            h.get(t)
            val type = String(t, Charsets.ISO_8859_1)
            var hdr = 8L
            if (size == 1L) {
                if (h.remaining() < 8) break
                size = h.long
                hdr = 16
            } else if (size == 0L) {
                size = to - p
            }
            if (size < hdr) break
            out.add(Box(type, p, p + hdr, minOf(p + size, to)))
            p += size
        }
        return out
    }

    private fun child(ch: FileChannel, parent: Box?, type: String): Box? =
        if (parent == null) null else boxes(ch, parent.data, parent.end).firstOrNull { it.type == type }

    private fun mp4(ch: FileChannel): List<C> {
        val moov = boxes(ch, 0, ch.size()).firstOrNull { it.type == "moov" } ?: return emptyList()
        val kids = boxes(ch, moov.data, moov.end)
        // 1) Nero „chpl”
        val chpl = child(ch, kids.firstOrNull { it.type == "udta" }, "chpl")
        if (chpl != null) {
            val b = readAt(ch, chpl.data, (chpl.end - chpl.data).toInt().coerceAtMost(1 shl 20))
            val ver = u8(b)
            b.position(b.position() + 3)
            if (ver != 0) b.position(b.position() + 4)
            val n = u8(b)
            val out = ArrayList<C>()
            for (k in 0 until n) {
                if (b.remaining() < 9) break
                val start = b.long / 10_000
                val len = u8(b)
                if (b.remaining() < len) break
                val t = ByteArray(len)
                b.get(t)
                out.add(C(start, String(t, Charsets.UTF_8).trim()))
            }
            if (out.size >= 2) return out
        }
        // 2) Ścieżka rozdziałów QuickTime (tref/chap)
        val traks = kids.filter { it.type == "trak" }
        val chapIds = HashSet<Long>()
        for (t in traks) {
            val chap = child(ch, child(ch, t, "tref"), "chap") ?: continue
            val b = readAt(ch, chap.data, (chap.end - chap.data).toInt())
            while (b.remaining() >= 4) chapIds.add(u32(b))
        }
        if (chapIds.isEmpty()) return emptyList()
        for (t in traks) {
            val tkhd = child(ch, t, "tkhd") ?: continue
            val h = readAt(ch, tkhd.data, 32)
            val ver = u8(h)
            h.position(h.position() + 3 + if (ver == 1) 16 else 8)
            if (u32(h) !in chapIds) continue
            return textTrack(ch, t)
        }
        return emptyList()
    }

    private fun textTrack(ch: FileChannel, trak: Box): List<C> {
        val mdia = child(ch, trak, "mdia") ?: return emptyList()
        val mdhd = child(ch, mdia, "mdhd") ?: return emptyList()
        val hb = readAt(ch, mdhd.data, 32)
        val ver = u8(hb)
        hb.position(hb.position() + 3 + if (ver == 1) 16 else 8)
        val timescale = u32(hb).coerceAtLeast(1)
        val stbl = child(ch, child(ch, mdia, "minf"), "stbl") ?: return emptyList()
        val kids = boxes(ch, stbl.data, stbl.end)
        fun body(type: String): ByteBuffer? = kids.firstOrNull { it.type == type }?.let {
            readAt(ch, it.data, (it.end - it.data).toInt().coerceAtMost(4 shl 20))
        }

        // czasy próbek
        val stts = body("stts") ?: return emptyList()
        stts.position(4)
        val times = ArrayList<Long>()
        var t = 0L
        repeat(u32(stts).toInt()) {
            val count = u32(stts)
            val delta = u32(stts)
            for (k in 0 until count) {
                times.add(t)
                t += delta
                if (times.size > 5000) return@repeat
            }
        }
        // rozmiary
        val stsz = body("stsz") ?: return emptyList()
        stsz.position(4)
        val fixed = u32(stsz)
        val sc = u32(stsz).toInt().coerceAtMost(5000)
        val sizes = LongArray(sc) { if (fixed != 0L) fixed else u32(stsz) }
        // przesunięcia kawałków
        val offsets = ArrayList<Long>()
        body("stco")?.let { b ->
            b.position(4)
            repeat(u32(b).toInt()) { offsets.add(u32(b)) }
        } ?: body("co64")?.let { b ->
            b.position(4)
            repeat(u32(b).toInt()) { offsets.add(b.long) }
        }
        val stsc = body("stsc") ?: return emptyList()
        stsc.position(4)
        val rules = ArrayList<LongArray>()
        repeat(u32(stsc).toInt()) { rules.add(longArrayOf(u32(stsc), u32(stsc), u32(stsc))) }
        if (offsets.isEmpty() || rules.isEmpty()) return emptyList()

        // adres każdej próbki
        val sampleOff = ArrayList<Long>()
        var s = 0
        for (c in offsets.indices) {
            val chunk = (c + 1).toLong()
            val rule = rules.lastOrNull { it[0] <= chunk } ?: rules[0]
            var off = offsets[c]
            for (k in 0 until rule[1]) {
                if (s >= sizes.size) break
                sampleOff.add(off)
                off += sizes[s]
                s++
            }
        }
        val out = ArrayList<C>()
        for (k in sampleOff.indices) {
            if (k >= times.size) break
            val b = readAt(ch, sampleOff[k], (sizes[k].coerceAtMost(1024)).toInt())
            if (b.remaining() < 2) continue
            val len = u16(b).coerceAtMost(b.remaining())
            val raw = ByteArray(len)
            b.get(raw)
            val title = if (len >= 2 && raw[0] == 0xFE.toByte() && raw[1] == 0xFF.toByte())
                String(raw, Charset.forName("UTF-16")) else String(raw, Charsets.UTF_8)
            out.add(C(times[k] * 1000 / timescale, title.trim()))
        }
        return out
    }

    // ------------------------------------------------------------ MP3 (ID3v2.3 / 2.4)
    private fun syncsafe(b: ByteBuffer): Int =
        (u8(b) shl 21) or (u8(b) shl 14) or (u8(b) shl 7) or u8(b)

    private fun id3(ch: FileChannel): List<C> {
        val h = readAt(ch, 0, 10)
        if (h.remaining() < 10) return emptyList()
        val tag = ByteArray(3)
        h.get(tag)
        if (String(tag, Charsets.ISO_8859_1) != "ID3") return emptyList()
        val ver = u8(h)
        u8(h)
        val flags = u8(h)
        val size = syncsafe(h)
        if (ver < 3 || size <= 0) return emptyList()
        val d = readAt(ch, 10, size.coerceAtMost(32 shl 20))
        if (flags and 0x40 != 0) {
            val ext = if (ver == 4) syncsafe(d) else d.int + 4
            d.position((if (ver == 4) ext else ext).coerceIn(0, d.limit()))
        }
        val out = ArrayList<C>()
        frames(d, ver) { id, body ->
            if (id == "CHAP") {
                while (body.hasRemaining() && body.get() != 0.toByte()) { /* identyfikator elementu */ }
                if (body.remaining() >= 16) {
                    val start = u32(body)
                    body.position(body.position() + 12)
                    var title = ""
                    frames(body, ver) { sid, sb -> if (sid == "TIT2") title = text(sb) }
                    out.add(C(start, title))
                }
            }
        }
        out.forEachIndexed { n, c -> if (c.title.isBlank()) out[n] = C(c.start, "Rozdział ${n + 1}") }
        return out
    }

    private fun frames(d: ByteBuffer, ver: Int, f: (String, ByteBuffer) -> Unit) {
        while (d.remaining() >= 10) {
            val idb = ByteArray(4)
            d.get(idb)
            if (idb[0] == 0.toByte()) break
            val id = String(idb, Charsets.ISO_8859_1)
            val size = if (ver == 4) syncsafe(d) else d.int
            d.short
            if (size < 0 || size > d.remaining()) break
            val body = d.slice()
            body.limit(size)
            d.position(d.position() + size)
            try {
                f(id, body)
            } catch (e: Exception) {
            }
        }
    }

    private fun text(b: ByteBuffer): String {
        if (!b.hasRemaining()) return ""
        val enc = u8(b)
        val raw = ByteArray(b.remaining())
        b.get(raw)
        val cs = when (enc) {
            1 -> Charset.forName("UTF-16")
            2 -> Charset.forName("UTF-16BE")
            3 -> Charsets.UTF_8
            else -> Charsets.ISO_8859_1
        }
        return String(raw, cs).trim { it <= ' ' || it == '\u0000' }
    }
}
