package com.ghost.hub

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** Downloads an APK from the Hub server and hands it to Android's installer. */
object Installer {
    /** The file didn't match its checksum (usually: the PC published a newer build mid-download). */
    class CorruptDownload : IOException("The download didn't match. Please try again.")

    /** Downloads [entry] to the cache, reporting progress 0..100, and returns the file. */
    fun download(ctx: Context, repo: Repo, entry: HubEntry, onProgress: (Int) -> Unit): File {
        val dir = File(ctx.cacheDir, "apk").apply { mkdirs() }
        val out = File(dir, "${entry.pkg}.apk")
        val tmp = File(dir, "${entry.pkg}.apk.part")
        val c = URL(repo.urlFor(entry)).openConnection() as HttpURLConnection
        c.connectTimeout = 5000
        c.readTimeout = 15000
        c.useCaches = false
        c.setRequestProperty("Cache-Control", "no-cache")
        try {
            if (c.responseCode !in 200..299) throw IOException("HTTP ${c.responseCode}")
            val total = if (entry.size > 0) entry.size else c.contentLengthLong
            val digest = MessageDigest.getInstance("SHA-256")
            c.inputStream.use { input ->
                tmp.outputStream().use { output ->
                    val buf = ByteArray(1 shl 16)
                    var done = 0L
                    var lastPct = -1
                    while (true) {
                        val n = input.read(buf)
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
