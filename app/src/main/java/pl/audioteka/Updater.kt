package pl.audioteka

import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Sprawdza i pobiera nowe wersje z GitHub Releases (prywatne repozytorium, dostęp tokenem). */
object Updater {
    const val REPO = "Bartoszelek/audioteka"

    class Info(val number: Int, val assetUrl: String, val title: String)

    private fun open(url: String, token: String?, accept: String): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000
        c.readTimeout = 30_000
        c.instanceFollowRedirects = false
        if (!token.isNullOrBlank()) c.setRequestProperty("Authorization", "Bearer $token")
        c.setRequestProperty("Accept", accept)
        c.setRequestProperty("User-Agent", "Audioteka")
        return c
    }

    fun check(token: String): Info? {
        val c = open("https://api.github.com/repos/$REPO/releases/latest", token, "application/vnd.github+json")
        val code = c.responseCode
        if (code == 401) throw IOException("Token jest nieprawidłowy albo wygasł")
        if (code == 404) throw IOException("Nie znaleziono wydań – zbuduj aplikację na GitHubie")
        if (code != 200) throw IOException("GitHub odpowiedział kodem $code")
        val o = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
        val n = o.optString("tag_name").substringAfter("build-").toIntOrNull() ?: return null
        val assets = o.optJSONArray("assets") ?: return null
        for (k in 0 until assets.length()) {
            val a = assets.getJSONObject(k)
            if (a.optString("name").endsWith(".apk")) return Info(n, a.getString("url"), o.optString("name"))
        }
        return null
    }

    fun download(token: String, info: Info, dest: File, progress: (Int) -> Unit) {
        var c = open(info.assetUrl, token, "application/octet-stream")
        var code = c.responseCode
        var hops = 0
        while (code in 300..399 && hops < 5) {
            val loc = c.getHeaderField("Location") ?: break
            c.disconnect()
            c = open(loc, null, "application/octet-stream")
            code = c.responseCode
            hops++
        }
        if (code != 200) throw IOException("Pobieranie nieudane (kod $code)")
        val total = c.contentLengthLong
        c.inputStream.use { inp ->
            dest.outputStream().use { out ->
                val buf = ByteArray(65536)
                var done = 0L
                var last = -1
                while (true) {
                    val r = inp.read(buf)
                    if (r < 0) break
                    out.write(buf, 0, r)
                    done += r
                    if (total > 0) {
                        val pct = (done * 100 / total).toInt()
                        if (pct != last) {
                            last = pct
                            progress(pct)
                        }
                    }
                }
            }
        }
    }
}
