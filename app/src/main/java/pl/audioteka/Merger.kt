package pl.audioteka

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/** Scalanie wielu plików książki w jeden (MP3 albo M4B), z zachowaniem rozdziałów. */
object Merger {

    fun displayName(ctx: Context, uri: Uri): String {
        try {
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) return c.getString(0) ?: ""
            }
        } catch (e: Exception) {
        }
        return uri.lastPathSegment ?: ""
    }

    /** "mp3", "m4b" albo null, gdy pliki są mieszane lub nieobsługiwane. */
    fun kind(ctx: Context, b: Book): String? {
        val exts = b.files.map { displayName(ctx, Uri.parse(it)).substringAfterLast('.', "").lowercase() }.toSet()
        return when {
            exts == setOf("mp3") -> "mp3"
            exts.isNotEmpty() && exts.all { it in setOf("m4a", "m4b", "mp4", "aac") } -> "m4b"
            else -> null
        }
    }

    private fun absChapters(b: Book, fileStarts: List<Long>): List<Pair<Long, String>> =
        b.ch.map { Pair(fileStarts.getOrElse(it.i) { 0L } + it.start, it.title) }

    // ------------------------------------------------------------ MP3
    fun mergeMp3(ctx: Context, b: Book, dest: Uri, status: (String) -> Unit) {
        val starts = ArrayList<Long>()
        var acc = 0L
        for (d in b.durs) {
            starts.add(acc)
            acc += d
        }
        val chaps = absChapters(b, starts)
        val os = ctx.contentResolver.openOutputStream(dest, "w") ?: throw IOException("Nie mogę zapisać pliku")
        BufferedOutputStream(os, 1 shl 16).use { out ->
            out.write(id3Tag(b.title, chaps, acc))
            for ((n, f) in b.files.withIndex()) {
                status("Scalam plik ${n + 1}/${b.files.size}…")
                val pfd = ctx.contentResolver.openFileDescriptor(Uri.parse(f), "r") ?: throw IOException("Brak pliku ${n + 1}")
                ParcelFileDescriptor.AutoCloseInputStream(pfd).use { ins ->
                    val ch = ins.channel
                    val size = ch.size()
                    var start = id3Size(ch)
                    var end = size
                    if (size > 128) {
                        val t = read(ch, size - 128, 3)
                        if (t[0] == 'T'.code.toByte() && t[1] == 'A'.code.toByte() && t[2] == 'G'.code.toByte()) end -= 128
                    }
                    start = skipInfoFrame(ch, start, end)
                    val buf = ByteBuffer.allocate(1 shl 16)
                    var p = start
                    while (p < end) {
                        buf.clear()
                        if (end - p < buf.capacity()) buf.limit((end - p).toInt())
                        val r = ch.read(buf, p)
                        if (r <= 0) break
                        out.write(buf.array(), 0, r)
                        p += r
                    }
                }
            }
        }
    }

    private fun read(ch: FileChannel, pos: Long, n: Int): ByteArray {
        val b = ByteBuffer.allocate(n)
        ch.read(b, pos)
        return b.array()
    }

    private fun id3Size(ch: FileChannel): Long {
        val h = read(ch, 0, 10)
        if (h[0] != 'I'.code.toByte() || h[1] != 'D'.code.toByte() || h[2] != '3'.code.toByte()) return 0
        val sz = ((h[6].toInt() and 0x7F) shl 21) or ((h[7].toInt() and 0x7F) shl 14) or
            ((h[8].toInt() and 0x7F) shl 7) or (h[9].toInt() and 0x7F)
        val footer = if (h[5].toInt() and 0x10 != 0) 10 else 0
        return 10L + sz + footer
    }

    private val BR1 = intArrayOf(0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320)
    private val BR2 = intArrayOf(0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160)

    /** Pomija ramkę Xing/Info/VBRI pliku, żeby nie myliła długości scalonego nagrania. */
    private fun skipInfoFrame(ch: FileChannel, from: Long, end: Long): Long {
        val win = read(ch, from, 4096.coerceAtMost((end - from).toInt().coerceAtLeast(0)))
        var i = 0
        while (i + 4 < win.size) {
            val b1 = win[i].toInt() and 0xFF
            val b2 = win[i + 1].toInt() and 0xFF
            if (b1 == 0xFF && (b2 and 0xE0) == 0xE0) break
            i++
        }
        if (i + 4 >= win.size) return from
        val sync = from + i
        val b2 = win[i + 1].toInt() and 0xFF
        val b3 = win[i + 2].toInt() and 0xFF
        val b4 = win[i + 3].toInt() and 0xFF
        val ver = (b2 shr 3) and 3
        val layer = (b2 shr 1) and 3
        if (layer != 1) return sync
        val mpeg1 = ver == 3
        val bri = (b3 shr 4) and 0xF
        val sri = (b3 shr 2) and 3
        if (bri == 0 || bri == 15 || sri == 3) return sync
        val br = (if (mpeg1) BR1 else BR2)[bri]
        val sr = when (ver) {
            3 -> intArrayOf(44100, 48000, 32000)[sri]
            2 -> intArrayOf(22050, 24000, 16000)[sri]
            else -> intArrayOf(11025, 12000, 8000)[sri]
        }
        val pad = (b3 shr 1) and 1
        val len = (if (mpeg1) 144 else 72) * br * 1000 / sr + pad
        val mono = ((b4 shr 6) and 3) == 3
        val side = if (mpeg1) (if (mono) 17 else 32) else (if (mono) 9 else 17)
        val tag = String(read(ch, sync + 4 + side, 4), Charsets.ISO_8859_1)
        val vbri = String(read(ch, sync + 36, 4), Charsets.ISO_8859_1)
        return if (tag == "Xing" || tag == "Info" || vbri == "VBRI") sync + len else sync
    }

    private fun syncsafe(n: Int) = byteArrayOf(
        ((n shr 21) and 0x7F).toByte(), ((n shr 14) and 0x7F).toByte(),
        ((n shr 7) and 0x7F).toByte(), (n and 0x7F).toByte()
    )

    private fun be32(n: Long) = byteArrayOf(
        ((n shr 24) and 0xFF).toByte(), ((n shr 16) and 0xFF).toByte(),
        ((n shr 8) and 0xFF).toByte(), (n and 0xFF).toByte()
    )

    private fun frame(id: String, data: ByteArray): ByteArray {
        val o = ByteArrayOutputStream()
        o.write(id.toByteArray(Charsets.ISO_8859_1))
        o.write(syncsafe(data.size))
        o.write(byteArrayOf(0, 0))
        o.write(data)
        return o.toByteArray()
    }

    private fun tit2(t: String) = frame("TIT2", byteArrayOf(3) + t.toByteArray(Charsets.UTF_8))

    /** Znacznik ID3v2.4 z tytułem i rozdziałami (CTOC + CHAP). */
    private fun id3Tag(title: String, chaps: List<Pair<Long, String>>, total: Long): ByteArray {
        val body = ByteArrayOutputStream()
        body.write(tit2(title))
        val list = chaps.take(255)
        if (list.size >= 2) {
            val toc = ByteArrayOutputStream()
            toc.write("toc".toByteArray() + byteArrayOf(0))
            toc.write(byteArrayOf(0x03, list.size.toByte()))
            list.indices.forEach { toc.write("ch$it".toByteArray() + byteArrayOf(0)) }
            body.write(frame("CTOC", toc.toByteArray()))
            list.forEachIndexed { k, (start, t) ->
                val end = list.getOrNull(k + 1)?.first ?: total
                val c = ByteArrayOutputStream()
                c.write("ch$k".toByteArray() + byteArrayOf(0))
                c.write(be32(start))
                c.write(be32(end))
                c.write(be32(0xFFFFFFFFL))
                c.write(be32(0xFFFFFFFFL))
                c.write(tit2(t))
                body.write(frame("CHAP", c.toByteArray()))
            }
        }
        val b = body.toByteArray()
        val o = ByteArrayOutputStream()
        o.write("ID3".toByteArray())
        o.write(byteArrayOf(4, 0, 0))
        o.write(syncsafe(b.size))
        o.write(b)
        return o.toByteArray()
    }

    // ------------------------------------------------------------ M4B (AAC)
    fun mergeM4b(ctx: Context, b: Book, dest: Uri, status: (String) -> Unit) {
        val tmp = File(ctx.cacheDir, "merge.m4b")
        tmp.delete()
        val muxer = MediaMuxer(tmp.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val starts = ArrayList<Long>()
        var track = -1
        var offsetUs = 0L
        var refRate = 0
        var refCh = 0
        val buf = ByteBuffer.allocate(1 shl 20)
        val info = MediaCodec.BufferInfo()
        try {
            for ((n, f) in b.files.withIndex()) {
                status("Scalam plik ${n + 1}/${b.files.size}…")
                val ex = MediaExtractor()
                try {
                    ex.setDataSource(ctx, Uri.parse(f), null)
                    val ti = (0 until ex.trackCount).firstOrNull {
                        ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
                    } ?: throw IOException("Plik ${n + 1} nie ma ścieżki dźwięku")
                    ex.selectTrack(ti)
                    val fmt = ex.getTrackFormat(ti)
                    if (fmt.getString(MediaFormat.KEY_MIME) != "audio/mp4a-latm") throw IOException("Plik ${n + 1} nie jest w formacie AAC")
                    val rate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    val chn = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    if (track < 0) {
                        track = muxer.addTrack(fmt)
                        muxer.start()
                        refRate = rate
                        refCh = chn
                    } else if (rate != refRate || chn != refCh) {
                        throw IOException("Pliki mają różne parametry dźwięku – nie da się ich scalić bez przekodowania")
                    }
                    starts.add(offsetUs / 1000)
                    var last = 0L
                    while (true) {
                        val size = ex.readSampleData(buf, 0)
                        if (size < 0) break
                        info.set(0, size, offsetUs + ex.sampleTime,
                            if (ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                        muxer.writeSampleData(track, buf, info)
                        last = ex.sampleTime
                        ex.advance()
                    }
                    offsetUs += last + 1024L * 1_000_000L / rate
                } finally {
                    ex.release()
                }
            }
            muxer.stop()
        } finally {
            try {
                muxer.release()
            } catch (e: Exception) {
            }
        }
        status("Dopisuję rozdziały…")
        addChpl(tmp, absChapters(b, starts))
        status("Zapisuję plik…")
        val os = ctx.contentResolver.openOutputStream(dest, "w") ?: throw IOException("Nie mogę zapisać pliku")
        os.use { o -> tmp.inputStream().use { it.copyTo(o, 1 shl 16) } }
        tmp.delete()
    }

    /** Dopisuje rozdziały „chpl” do moov (jeśli moov jest na końcu pliku). */
    private fun addChpl(file: File, chaps: List<Pair<Long, String>>) {
        if (chaps.size < 2) return
        RandomAccessFile(file, "rw").use { raf ->
            var p = 0L
            var lastStart = -1L
            var lastType = ""
            val len = raf.length()
            while (p + 8 <= len) {
                raf.seek(p)
                val size = raf.readInt().toLong() and 0xFFFFFFFFL
                val t = ByteArray(4)
                raf.readFully(t)
                if (size < 8) return
                lastStart = p
                lastType = String(t, Charsets.ISO_8859_1)
                p += size
            }
            if (lastType != "moov" || p != len) return
            val payload = ByteArrayOutputStream()
            payload.write(byteArrayOf(0, 0, 0, 0))
            val list = chaps.take(255)
            payload.write(list.size)
            for ((start, title) in list) {
                val v = start * 10_000
                for (s in 7 downTo 0) payload.write(((v shr (s * 8)) and 0xFF).toInt())
                var tb = title.toByteArray(Charsets.UTF_8)
                if (tb.size > 255) tb = tb.copyOf(255)
                payload.write(tb.size)
                payload.write(tb)
            }
            val chpl = payload.toByteArray()
            val chplSize = 8 + chpl.size
            val udtaSize = 8 + chplSize
            raf.seek(len)
            raf.writeInt(udtaSize)
            raf.write("udta".toByteArray())
            raf.writeInt(chplSize)
            raf.write("chpl".toByteArray())
            raf.write(chpl)
            raf.seek(lastStart)
            val moovSize = raf.readInt().toLong() and 0xFFFFFFFFL
            raf.seek(lastStart)
            raf.writeInt((moovSize + udtaSize).toInt())
        }
    }
}
