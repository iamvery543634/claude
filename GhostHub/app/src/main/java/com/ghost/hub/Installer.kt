package com.ghost.hub

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.security.MessageDigest

/** Downloads an APK from GitHub or the Hub server and hands it to Android's installer. */
object Installer {
    /** The file didn't match its checksum (usually: a newer build was published mid-download). */
    class CorruptDownload : IOException("The download didn't match. Please try again.")

    /** Downloads [entry] to the cache, reporting progress 0..100, and returns the file. */
    fun download(ctx: Context, repo: Repo, entry: HubEntry, onProgress: (Int) -> Unit): File {
        val dir = File(ctx.cacheDir, "apk").apply { mkdirs() }
        val out = File(dir, "${entry.pkg}.apk")
        val tmp = File(dir, "${entry.pkg}.apk.part")
        val c = open(repo.urlFor(entry))
        try {
            val total = if (entry.size > 0) entry.size else c.contentLengthLong
            val digest = MessageDigest.getInstance("SHA-256")
            c.inputStream.use { input ->
                tmp.outputStream().use { output ->
                    val buf = ByteArray(1 shl 16)
                    var done = 0L
                    var lastPct = -1
                    while (true) {
                        val n = try {
                            input.read(buf)
                        } catch (_: SocketTimeoutException) {
                            throw IOException("The download stalled. Check your connection and try again.")
                        }
                        if (n < 0) break
                        output.write(buf, 0, n)
                        digest.update(buf, 0, n)
                        done += n
                        if (total > 0) {
                            val pct = (done * 100 / total).toInt()
                            if (pct != lastPct) {
                                lastPct = pct
                                onProgress(pct)
                            }
                        }
                    }
                }
            }
            if (entry.sha256.isNotBlank()) {
                val got = digest.digest().joinToString("") { "%02x".format(it) }
                if (!got.equals(entry.sha256, ignoreCase = true)) {
                    tmp.delete()
                    throw CorruptDownload()
                }
            }
            tmp.copyTo(out, overwrite = true)
            tmp.delete()
            return out
        } finally {
            c.disconnect()
        }
    }

    /**
     * Opens [url] with timeouts fit for mobile data, following up to five redirects (GitHub sends release
     * downloads through one). A redirect from https to plain http is refused.
     */
    private fun open(url: String): HttpURLConnection {
        var current = url
        repeat(6) {
            val c = URL(current).openConnection() as HttpURLConnection
            c.connectTimeout = 15000
            c.readTimeout = 30000
            c.useCaches = false
            c.instanceFollowRedirects = false
            c.setRequestProperty("Cache-Control", "no-cache")
            val code = try {
                c.responseCode
            } catch (_: UnknownHostException) {
                throw IOException("No internet connection.")
            } catch (_: SocketTimeoutException) {
                throw IOException("The server didn't answer in time.")
            }
            if (code in 300..399 && code != 304) {
                val next = c.getHeaderField("Location") ?: throw IOException("HTTP $code")
                c.disconnect()
                val target = URL(URL(current), next).toString()
                if (current.startsWith("https://") && !target.startsWith("https://")) throw IOException("Unsafe redirect (https to http).")
                current = target
            } else {
                if (code !in 200..299) {
                    c.disconnect()
                    throw IOException("HTTP $code")
                }
                return c
            }
        }
        throw IOException("Too many redirects.")
    }

    /** Opens Android's package installer for [apk]. The user confirms the install. */
    fun install(ctx: Context, apk: File) {
        val uri: Uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        ctx.startActivity(intent)
    }
}
