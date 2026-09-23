package com.ghost.hub

import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL

/** One app listed by the Hub server. */
data class HubEntry(
    val pkg: String,
    val name: String,
    val icon: String,
    val description: String,
    val changes: List<String>,
    val versionCode: Long,
    val versionName: String,
    val size: Long,
    val sha256: String,
    /** Download: a full https:// URL (GitHub) or a path relative to the PC server ("apps/x.apk"). */
    val file: String,
    /** When the PC published this build (seconds since 1970). */
    val published: Long,
) {
    companion object {
        fun from(o: JSONObject) = HubEntry(
            pkg = o.getString("package"),
            name = o.getString("name"),
            icon = o.optString("icon", "ghost"),
            description = o.optString("description"),
            changes = o.optJSONArray("changes")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList(),
            versionCode = o.optLong("versionCode"),
            versionName = o.optString("versionName"),
            size = o.optLong("size"),
            sha256 = o.optString("sha256"),
            file = o.optString("file"),
            published = o.optLong("published"),
        )
    }
}

/** How an app on the phone compares with what the server offers. */
enum class InstallState { NOT_INSTALLED, UP_TO_DATE, UPDATE, INSTALLED_NEWER }

data class AppStatus(val entry: HubEntry, val installedCode: Long?, val installedName: String?) {
    val state: InstallState get() = when {
        installedCode == null -> InstallState.NOT_INSTALLED
        installedCode >= entry.versionCode -> if (installedCode > entry.versionCode) InstallState.INSTALLED_NEWER else InstallState.UP_TO_DATE
        else -> InstallState.UPDATE
    }
}

/** The server answered, but not with 2xx (as opposed to a network problem). */
class HttpStatus(val code: Int) : IOException("HTTP $code")

class Repo(private val ctx: Context) {
    /** Where the user wants the app list from. */
    enum class Source(val key: String, val title: String) {
        AUTO("auto", "Auto: GitHub, or your PC on home Wi-Fi"),
        GITHUB("github", "GitHub only"),
        PC("pc", "PC only (home Wi-Fi)"),
    }

    /** Where the current list actually came from. */
    enum class From(val key: String, val label: String) { GITHUB("github", "GitHub"), PC("pc", "PC (Wi-Fi)") }

    private val sp = ctx.applicationContext.getSharedPreferences("hub", Context.MODE_PRIVATE)

    /** Base URL of the PC server, e.g. "http://192.168.0.78:8765". */
    var server: String?
        get() = sp.getString("server", null)
        set(v) = sp.edit().putString("server", v?.trimEnd('/')).apply()

    var serverName: String?
        get() = sp.getString("serverName", null)
        set(v) = sp.edit().putString("serverName", v).apply()

    var autoCheck: Boolean
        get() = sp.getBoolean("auto", true)
        set(v) = sp.edit().putBoolean("auto", v).apply()

    var source: Source
        get() = Source.entries.firstOrNull { it.key == sp.getString("source", null) } ?: Source.AUTO
        set(v) = sp.edit().putString("source", v.key).apply()

    /** URL of index.json in the GitHub builds repo. Blank means the built-in default. */
    var githubIndex: String
        get() = sp.getString("github", null)?.takeIf { it.isNotBlank() } ?: DEFAULT_GITHUB_INDEX
        set(v) = sp.edit().putString("github", v.trim().ifBlank { null }).apply()

    /** Where the last successful load came from, so fresh() and downloads use the same place. */
    var from: From?
        get() = From.entries.firstOrNull { it.key == sp.getString("from", null) }
        private set(v) = sp.edit().putString("from", v?.key).apply()

    /** Look for the server on the local network (UDP broadcast). Returns its base URL, or null. */
    fun discover(): String? {
        val wifi = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val lock = wifi?.createMulticastLock("ghosthub")?.apply { setReferenceCounted(false); runCatching { acquire() } }
        try {
            DatagramSocket().use { sock ->
                sock.broadcast = true
                sock.soTimeout = 1200
                val msg = "GHOSTHUB_DISCOVER".toByteArray()
                val targets = broadcastAddresses()
                for (addr in targets) {
                    runCatching { sock.send(DatagramPacket(msg, msg.size, addr, DISCOVERY_PORT)) }
                }
                val buf = ByteArray(256)
                val deadline = System.currentTimeMillis() + 1500
                while (System.currentTimeMillis() < deadline) {
                    val reply = DatagramPacket(buf, buf.size)
                    try {
                        sock.receive(reply)
                    } catch (_: IOException) {
                        break
                    }
                    val text = String(reply.data, 0, reply.length).trim()
                    if (text.startsWith("GHOSTHUB ")) {
                        val parts = text.split(" ")
                        val port = parts.getOrNull(1)?.toIntOrNull() ?: continue
                        val name = parts.drop(2).joinToString(" ").ifBlank { "PC" }
                        val base = "http://${reply.address.hostAddress}:$port"
                        server = base
                        serverName = name
                        return base
                    }
                }
            }
        } catch (_: Exception) {
        } finally {
            runCatching { lock?.release() }
        }
        return null
    }

    private fun broadcastAddresses(): List<InetAddress> {
        val list = mutableListOf<InetAddress>()
        runCatching { list.add(InetAddress.getByName("255.255.255.255")) }
        runCatching {
            java.net.NetworkInterface.getNetworkInterfaces().toList().forEach { ni ->
                if (ni.isUp && !ni.isLoopback) {
                    ni.interfaceAddresses.forEach { ia -> ia.broadcast?.let { list.add(it) } }
                }
            }
        }
        return list.distinct()
    }

    /**
     * Fetches the app list from GitHub and/or the PC, depending on [source]. Throws an IOException whose
     * message is fit to show. [quick] uses shorter timeouts, for the background check.
     */
    fun load(quick: Boolean = false): List<AppStatus> {
        val body = when (source) {
            Source.GITHUB -> fetchGitHub(quick)
            Source.PC -> fetchPc()
            Source.AUTO -> try {
                fetchGitHub(quick)
            } catch (e: IOException) {
                // No internet, or GitHub is down: at home the PC still works over Wi-Fi.
                try {
                    fetchPc()
                } catch (_: IOException) {
                    throw IOException("${e.message} Your PC isn't on this Wi-Fi either.")
                }
            }
        }
        val pm = ctx.packageManager
        return entries(body).map { entry ->
            val installed = runCatching {
                val info = pm.getPackageInfo(entry.pkg, 0)
                val code = if (android.os.Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
                code to info.versionName
            }.getOrNull()
            AppStatus(entry, installed?.first, installed?.second)
        }
    }

    /** Reads index.json from GitHub. [bust] adds a changing query string to skip GitHub's ~5 minute cache. */
    private fun fetchGitHub(quick: Boolean, bust: Boolean = false): String {
        if (!online()) throw IOException("No internet connection.")
        var url = githubIndex
        if (bust) url += (if ('?' in url) "&" else "?") + "t=" + System.currentTimeMillis()
        val body = try {
            httpGet(url, connect = if (quick) 5000 else 10000, read = if (quick) 10000 else 20000)
        } catch (e: HttpStatus) {
            throw IOException(
                if (e.code == 404) "GitHub has no app list at that address yet. Check the GitHub address in Settings."
                else "GitHub answered with an error (HTTP ${e.code}).",
            )
        } catch (_: IOException) {
            throw IOException("GitHub didn't answer. Check your connection and try again.")
        }
        from = From.GITHUB
        return body
    }

    /** Reads index.json from the PC on this Wi-Fi, finding it first if needed. */
    private fun fetchPc(): String {
        var base = server
        if (base == null || !ping(base)) {
            base = discover() ?: base ?: throw IOException("Couldn't find the Ghost Hub PC on this Wi-Fi.")
        }
        val body = try {
            httpGet("$base/index.json", connect = 4000, read = 8000)
        } catch (_: IOException) {
            throw IOException("Couldn't reach the Ghost Hub PC at $base.")
        }
        from = From.PC
        return body
    }

    /** True when Android thinks the current network reaches the internet. */
    private fun online(): Boolean {
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return true
        val caps = cm.getNetworkCapabilities(cm.activeNetwork ?: return false) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun ping(base: String): Boolean = runCatching {
        val c = (URL("$base/index.json").openConnection() as HttpURLConnection)
        c.connectTimeout = 900
        c.readTimeout = 900
        c.requestMethod = "HEAD"
        val ok = c.responseCode in 200..399
        c.disconnect()
        ok
    }.getOrDefault(false)

    private fun httpGet(url: String, connect: Int, read: Int): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = connect
        c.readTimeout = read
        c.useCaches = false
        c.setRequestProperty("Cache-Control", "no-cache")
        try {
            if (c.responseCode !in 200..299) throw HttpStatus(c.responseCode)
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    private fun entries(body: String): List<HubEntry> = try {
        val arr = JSONObject(body).getJSONArray("apps")
        (0 until arr.length()).map { HubEntry.from(arr.getJSONObject(it)) }
    } catch (_: JSONException) {
        throw IOException("The app list couldn't be read. Try again in a minute.")
    }

    /** Re-reads one app's details from wherever the list came from, so a download always matches the newest build. */
    fun fresh(pkg: String): HubEntry? = runCatching {
        val where = from ?: if (source == Source.PC) From.PC else From.GITHUB
        val body = if (where == From.PC) fetchPc() else fetchGitHub(quick = false, bust = true)
        entries(body).firstOrNull { it.pkg == pkg }
    }.getOrNull()

    /** Full download URL: GitHub entries carry a full https:// URL, the PC's are relative to its server. */
    fun urlFor(entry: HubEntry): String =
        if (entry.file.startsWith("https://") || entry.file.startsWith("http://")) entry.file
        else "${server?.trimEnd('/')}/${entry.file.trimStart('/')}"

    companion object {
        const val DISCOVERY_PORT = 8766
        const val DEFAULT_GITHUB_INDEX = "https://raw.githubusercontent.com/iamvery543634/Ghost-projects/main/index.json"
    }
}
