package pl.audioteka

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.security.MessageDigest

/**
 * Pliki bez spisu pozycji (MP3 VBR bez nagłówka, surowe AAC, WebM bez „Cues”…) nie pozwalają przewijać
 * ani wrócić do zapamiętanego miejsca. Robimy z nich kopię M4A z pełnym spisem – raz, w tle.
 */
object Fixer {
    fun dir(c: Context): File = File(c.getExternalFilesDir(null) ?: c.filesDir, "naprawione").apply { mkdirs() }

    fun outFile(c: Context, uri: String): File {
        val h = MessageDigest.getInstance("MD5").digest(uri.toByteArray()).joinToString("") { "%02x".format(it) }.take(16)
        return File(dir(c), "$h.m4a")
    }

    fun usedMb(c: Context): Long = (dir(c).listFiles()?.sumOf { it.length() } ?: 0L) / 1_048_576

    fun clear(c: Context) {
        dir(c).listFiles()?.forEach { it.delete() }
        Store.fixed.clear()
        Store.checked.clear()
        Store.saveState()
    }

    // ------------------------------------------------------------ wykrywanie
    private fun head(c: Context, u: Uri, n: Int): ByteArray {
        c.contentResolver.openInputStream(u)?.use { s ->
            val b = ByteArray(n)
            var off = 0
            while (off < n) {
                val r = s.read(b, off, n - off)
                if (r <= 0) break
                off += r
            }
            return b.copyOf(off)
        }
        return ByteArray(0)
    }

    private fun tail(c: Context, u: Uri, n: Int): ByteArray = try {
        c.contentResolver.openFileDescriptor(u, "r")?.use { pfd ->
            FileInputStream(pfd.fileDescriptor).use { fis ->
                val ch = fis.channel
                val size = ch.size()
                val start = (size - n).coerceAtLeast(0)
                val bb = ByteBuffer.allocate((size - start).toInt())
                ch.position(start)
                while (bb.hasRemaining() && ch.read(bb) > 0) Unit
                bb.array().copyOf(bb.position())
            }
        } ?: ByteArray(0)
    } catch (e: Exception) {
        ByteArray(0)
    }

    private fun find(b: ByteArray, pat: ByteArray, from: Int = 0, to: Int = b.size): Int {
        var i = from
        val end = minOf(to, b.size) - pat.size
        while (i <= end) {
            var ok = true
            for (k in pat.indices) if (b[i + k] != pat[k]) { ok = false; break }
            if (ok) return i
            i++
        }
        return -1
    }

    private fun id3Skip(b: ByteArray): Int {
        if (b.size >= 10 && b[0] == 'I'.code.toByte() && b[1] == 'D'.code.toByte() && b[2] == '3'.code.toByte()) {
            val sz = ((b[6].toInt() and 0x7f) shl 21) or ((b[7].toInt() and 0x7f) shl 14) or ((b[8].toInt() and 0x7f) shl 7) or (b[9].toInt() and 0x7f)
            return 10 + sz + if (b[5].toInt() and 0x10 != 0) 10 else 0
        }
        return 0
    }

    private val BR1 = intArrayOf(0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 0)
    private val BR2 = intArrayOf(0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160, 0)
    private val SR1 = intArrayOf(44100, 48000, 32000, 0)

    /** MP3: czy ma nagłówek Xing/Info/VBRI albo stałą przepływność (wtedy przewijanie jest dokładne). */
    private fun mp3Seekable(c: Context, u: Uri): Boolean {
        val b0 = head(c, u, 64 * 1024)
        var skip = id3Skip(b0)
        val b = if (skip > b0.size - 4096) {
            // duży tag ID3 (np. okładka) – czytamy dalej
            head(c, u, (skip + 64 * 1024).coerceAtMost(8 * 1024 * 1024))
        } else b0
        if (skip >= b.size) return true
        // pierwsza ramka
        var i = skip
        while (i < b.size - 4 && !((b[i].toInt() and 0xff) == 0xff && (b[i + 1].toInt() and 0xe0) == 0xe0)) i++
        if (i >= b.size - 4) return true
        if (find(b, "Xing".toByteArray(), i, i + 200) >= 0 || find(b, "Info".toByteArray(), i, i + 200) >= 0 || find(b, "VBRI".toByteArray(), i, i + 200) >= 0) return true
        // stała przepływność? – sprawdź kolejne ramki
        val rates = HashSet<Int>()
        var frames = 0
        while (i < b.size - 4 && frames < 60) {
            val h1 = b[i + 1].toInt() and 0xff
            val h2 = b[i + 2].toInt() and 0xff
            if ((b[i].toInt() and 0xff) != 0xff || (h1 and 0xe0) != 0xe0) break
            val ver = (h1 shr 3) and 3          // 3 = MPEG1, 2 = MPEG2, 0 = MPEG2.5
            val layer = (h1 shr 1) and 3        // 1 = Layer III
            val bri = (h2 shr 4) and 15
            val sri = (h2 shr 2) and 3
            val pad = (h2 shr 1) and 1
            if (layer != 1 || bri == 0 || bri == 15 || sri == 3) break
            val br = (if (ver == 3) BR1[bri] else BR2[bri]) * 1000
            val sr = SR1[sri] / when (ver) { 3 -> 1; 2 -> 2; else -> 4 }
            val len = (if (ver == 3) 144 else 72) * br / sr + pad
            if (len < 24) break
            rates.add(bri)
            frames++
            i += len
        }
        return frames < 5 || rates.size <= 1
    }

    /** Czy plik wymaga naprawy (brak spisu pozycji). */
    fun needsFix(c: Context, uriStr: String): Boolean {
        val u = Uri.parse(uriStr)
        val b = head(c, u, 16 * 1024)
        if (b.size < 16) return false
        val s = id3Skip(b)
        // surowe AAC (ADTS) – zawsze bez spisu
        if (s < b.size - 2 && (b[s].toInt() and 0xff) == 0xff && (b[s + 1].toInt() and 0xf6) == 0xf0) return true
        // Matroska / WebM – spis to element „Cues”
        if ((b[0].toInt() and 0xff) == 0x1a && (b[1].toInt() and 0xff) == 0x45 && (b[2].toInt() and 0xff) == 0xdf && (b[3].toInt() and 0xff) == 0xa3) {
            val cues = byteArrayOf(0x1c, 0x53, 0xbb.toByte(), 0x6b)
            return find(head(c, u, 4 * 1024 * 1024), cues) < 0 && find(tail(c, u, 4 * 1024 * 1024), cues) < 0
        }
        // MP3
        val mime = try {
            val ex = MediaExtractor()
            ex.setDataSource(c, u, null)
            val m = (0 until ex.trackCount).map { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME) ?: "" }.firstOrNull { it.startsWith("audio/") } ?: ""
            ex.release()
            m
        } catch (e: Exception) {
            ""
        }
        if (mime == "audio/mpeg") return !mp3Seekable(c, u)
        return false
    }

    // ------------------------------------------------------------ naprawa
    /** Tworzy kopię M4A z pełnym spisem pozycji. progress 0..100. */
    fun fix(c: Context, uriStr: String, progress: (Int) -> Unit): File {
        val out = outFile(c, uriStr)
        val tmp = File(out.path + ".part")
        tmp.delete()
        val ex = MediaExtractor()
        ex.setDataSource(c, Uri.parse(uriStr), null)
        val ti = (0 until ex.trackCount).firstOrNull { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
            ?: throw IllegalStateException("Brak ścieżki dźwięku")
        ex.selectTrack(ti)
        val fmt = ex.getTrackFormat(ti)
        val durUs = if (fmt.containsKey(MediaFormat.KEY_DURATION)) fmt.getLong(MediaFormat.KEY_DURATION) else 0L
        try {
            if (fmt.getString(MediaFormat.KEY_MIME) == "audio/mp4a-latm") remux(ex, fmt, tmp, durUs, progress)
            else transcode(ex, fmt, tmp, durUs, progress)
        } finally {
            ex.release()
        }
        // kontrola: czy kopia jest poprawna
        val mmr = MediaMetadataRetriever()
        val d = try {
            mmr.setDataSource(tmp.path)
            mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } catch (e: Exception) {
            0L
        } finally {
            try { mmr.release() } catch (e: Exception) {}
        }
        if (d <= 0) {
            tmp.delete(); throw IllegalStateException("Kopia jest uszkodzona")
        }
        out.delete()
        tmp.renameTo(out)
        return out
    }

    /** AAC → M4A bez przekodowania (bez utraty jakości, szybko). */
    private fun remux(ex: MediaExtractor, fmt: MediaFormat, out: File, durUs: Long, progress: (Int) -> Unit) {
        val mux = MediaMuxer(out.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val track = mux.addTrack(fmt)
        mux.start()
        val buf = ByteBuffer.allocate(1 shl 20)
        val info = MediaCodec.BufferInfo()
        var last = -1
        try {
            while (true) {
                val n = ex.readSampleData(buf, 0)
                if (n < 0) break
                info.set(0, n, ex.sampleTime, if (ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                mux.writeSampleData(track, buf, info)
                if (durUs > 0) {
                    val p = (ex.sampleTime * 100 / durUs).toInt()
                    if (p != last) { last = p; progress(p) }
                }
                ex.advance()
            }
            mux.stop()
        } finally {
            try { mux.release() } catch (e: Exception) {}
        }
    }

    /** Inne formaty (MP3, Opus, Vorbis, FLAC…) → AAC w M4A. */
    private fun transcode(ex: MediaExtractor, fmt: MediaFormat, out: File, durUs: Long, progress: (Int) -> Unit) {
        val dec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
        dec.configure(fmt, null, null, 0)
        dec.start()
        var rate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var ch = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        var enc: MediaCodec? = null
        val mux = MediaMuxer(out.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var track = -1
        var muxOn = false
        val pend = ArrayDeque<ByteArray>()
        var headOff = 0
        var pendBytes = 0L
        var fed = 0L // ramki PCM podane do kodera
        val di = MediaCodec.BufferInfo()
        val ei = MediaCodec.BufferInfo()
        var inEos = false
        var decEos = false
        var encInEos = false
        var encEos = false
        var lastP = -1
        try {
            while (!encEos) {
                // 1. dane do dekodera
                if (!inEos) {
                    val ii = dec.dequeueInputBuffer(2000)
                    if (ii >= 0) {
                        val b = dec.getInputBuffer(ii)!!
                        val n = ex.readSampleData(b, 0)
                        if (n < 0) {
                            dec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inEos = true
                        } else {
                            dec.queueInputBuffer(ii, 0, n, ex.sampleTime, 0)
                            if (durUs > 0) {
                                val p = (ex.sampleTime * 100 / durUs).toInt().coerceIn(0, 99)
                                if (p != lastP) { lastP = p; progress(p) }
                            }
                            ex.advance()
                        }
                    }
                }
                // 2. PCM z dekodera
                if (!decEos) {
                    val oi = dec.dequeueOutputBuffer(di, 2000)
                    if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        val f = dec.outputFormat
                        rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        ch = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    } else if (oi >= 0) {
                        if (di.size > 0) {
                            val ob = dec.getOutputBuffer(oi)!!
                            ob.position(di.offset); ob.limit(di.offset + di.size)
                            val arr = ByteArray(di.size)
                            ob.get(arr)
                            pend.addLast(arr); pendBytes += arr.size
                        }
                        dec.releaseOutputBuffer(oi, false)
                        if (di.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) decEos = true
                    }
                }
                // 3. koder AAC (tworzony, gdy znamy parametry PCM)
                if (enc == null && (pendBytes > 0 || decEos)) {
                    if (ch > 2) throw IllegalStateException("Nagrania wielokanałowe nie są obsługiwane")
                    val ef = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, rate, ch)
                    ef.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                    ef.setInteger(MediaFormat.KEY_BIT_RATE, if (ch == 1) 64_000 else 96_000)
                    ef.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 64 * 1024)
                    enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).also {
                        it.configure(ef, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE); it.start()
                    }
                }
                val e = enc ?: continue
                // 4. PCM do kodera
                if (!encInEos && (pendBytes > 0 || decEos)) {
                    val ii = e.dequeueInputBuffer(2000)
                    if (ii >= 0) {
                        val b = e.getInputBuffer(ii)!!
                        b.clear()
                        val frame = 2 * ch
                        if (pendBytes > 0) {
                            var room = (b.remaining() / frame) * frame
                            var put = 0
                            while (room > 0 && pend.isNotEmpty()) {
                                val h = pend.first()
                                val take = minOf(room, h.size - headOff)
                                b.put(h, headOff, take)
                                headOff += take; room -= take; put += take
                                if (headOff >= h.size) { pend.removeFirst(); headOff = 0 }
                            }
                            pendBytes -= put
                            val pts = fed * 1_000_000L / rate
                            e.queueInputBuffer(ii, 0, put, pts, 0)
                            fed += put / frame
                        } else {
                            e.queueInputBuffer(ii, 0, 0, fed * 1_000_000L / rate, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            encInEos = true
                        }
                    }
                }
                // 5. AAC do pliku
                val oi = e.dequeueOutputBuffer(ei, 2000)
                if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    track = mux.addTrack(e.outputFormat); mux.start(); muxOn = true
                } else if (oi >= 0) {
                    val ob = e.getOutputBuffer(oi)!!
                    if (ei.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) ei.size = 0
                    if (ei.size > 0 && muxOn) {
                        ob.position(ei.offset); ob.limit(ei.offset + ei.size)
                        mux.writeSampleData(track, ob, ei)
                    }
                    e.releaseOutputBuffer(oi, false)
                    if (ei.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) encEos = true
                }
            }
            if (muxOn) mux.stop()
        } finally {
            try { dec.stop(); dec.release() } catch (x: Exception) {}
            try { enc?.stop(); enc?.release() } catch (x: Exception) {}
            try { mux.release() } catch (x: Exception) {}
        }
        progress(100)
    }
}

/** Usługa w tle: sprawdza bibliotekę i naprawia pliki bez spisu pozycji. */
class FixService : Service() {
    companion object {
        @Volatile var busy = false
        @Volatile var status = ""

        fun start(c: Context, force: String? = null) {
            try {
                c.startForegroundService(Intent(c, FixService::class.java).putExtra("force", force))
            } catch (e: Exception) {
            }
        }
    }

    private val h = Handler(Looper.getMainLooper())
    private val forced = LinkedHashSet<String>()
    private var worker: Thread? = null
    private var wake: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    private fun notif(t: String, pct: Int): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm?.createNotificationChannel(NotificationChannel("fix", "Naprawa plików", NotificationManager.IMPORTANCE_LOW))
        return Notification.Builder(this, "fix")
            .setSmallIcon(Icon.createWithResource(this, R.drawable.ic_play))
            .setContentTitle("Naprawiam przewijanie")
            .setContentText(t)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, pct, pct <= 0)
            .build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Store.init(this)
        val n = notif("Sprawdzam pliki…", 0)
        if (Build.VERSION.SDK_INT >= 29) startForeground(21, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) else startForeground(21, n)
        intent?.getStringExtra("force")?.let { synchronized(forced) { forced.add(it) } }
        if (worker?.isAlive != true) {
            wake = (getSystemService(Context.POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "audioteka:fix")
                .apply { setReferenceCounted(false); acquire(4 * 3600_000L) }
            worker = Thread { work() }.also { it.start() }
        }
        return START_NOT_STICKY
    }

    private fun show(t: String, pct: Int) {
        status = t
        try { getSystemService(NotificationManager::class.java)?.notify(21, notif(t, pct)) } catch (e: SecurityException) {}
    }

    private fun work() {
        busy = true
        try {
            while (true) {
                // najpierw pliki wskazane przez odtwarzacz, potem cała biblioteka
                val f = synchronized(forced) { forced.firstOrNull()?.also { forced.remove(it) } }
                val todo: List<Pair<Book, String>> = if (f != null) {
                    Store.books.filter { f in it.files }.map { Pair(it, f) }.take(1)
                } else {
                    Store.books.flatMap { b -> b.files.map { Pair(b, it) } }
                        .filter { (_, u) -> u !in Store.fixed && u !in Store.checked }
                }
                if (todo.isEmpty()) break
                var k = 0
                for ((b, u) in todo) {
                    k++
                    if (f == null && synchronized(forced) { forced.isNotEmpty() }) break
                    val label = b.title.take(40) + if (b.files.size > 1) " (plik ${b.files.indexOf(u) + 1})" else ""
                    val need = f != null || try { show("Sprawdzam: $label", 0); Fixer.needsFix(this, u) } catch (e: Exception) { false }
                    if (need && u !in Store.fixed) {
                        try {
                            val out = Fixer.fix(this, u) { p -> show("$label – $p%", p) }
                            synchronized(Store) {
                                Store.fixed[u] = out.path
                                Store.checked.add(u)
                                Store.saveState()
                            }
                            h.post { PlaybackService.instance?.onFixed(u) }
                        } catch (e: Exception) {
                            Store.checked.add(u); Store.saveState()
                        }
                    } else {
                        Store.checked.add(u)
                        if (k % 20 == 0) Store.saveState()
                    }
                }
                Store.saveState()
                if (f == null && synchronized(forced) { forced.isEmpty() }) break
            }
        } finally {
            busy = false
            status = ""
            try { wake?.release() } catch (e: Exception) {}
            h.post { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
        }
    }
}
