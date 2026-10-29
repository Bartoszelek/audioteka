package pl.audioteka

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player

/** Wczytywanie książki do odtwarzacza i skoki między rozdziałami. */
object Playback {
    fun bookIdOf(p: Player?): String? = p?.currentMediaItem?.mediaId?.substringBefore("|")

    /** Ładuje książkę do odtwarzacza (zapamiętując miejsce w poprzedniej). */
    fun load(p: Player, b: Book) {
        val curId = bookIdOf(p)
        if (curId == b.id) return
        if (curId != null) {
            val old = Store.prog(curId)
            old.i = p.currentMediaItemIndex
            old.pos = p.currentPosition
            old.updated = System.currentTimeMillis()
        }
        val items = b.files.mapIndexed { i, u ->
            MediaItem.Builder().setUri(Uri.parse(u)).setMediaId("${b.id}|$i")
                .setMediaMetadata(
                    MediaMetadata.Builder().setTitle(b.names[i]).setArtist(b.title).setAlbumTitle(b.title).build()
                ).build()
        }
        val pr = Store.prog(b.id)
        if (pr.done) {
            pr.done = false
            pr.i = 0
            pr.pos = 0
        }
        pr.updated = System.currentTimeMillis()
        Store.saveState()
        p.setMediaItems(items, pr.i.coerceIn(0, items.size - 1), pr.pos)
        p.setPlaybackSpeed(pr.speed)
        p.prepare()
    }

    /** Rozdział wstecz (z początku rozdziału) albo naprzód. */
    fun jump(p: Player, b: Book, dir: Int) {
        val i = p.currentMediaItemIndex
        val pos = p.currentPosition
        val k = Ch.index(b, i, pos)
        val target = if (dir < 0) {
            val c = b.ch[k]
            if (c.i == i && pos - c.start > 5000) k else maxOf(0, k - 1)
        } else {
            if (k + 1 >= b.ch.size) return
            k + 1
        }
        val c = b.ch[target]
        p.seekTo(c.i, c.start)
    }
}
