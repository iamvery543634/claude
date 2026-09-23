package com.ghost.hub

import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
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

class Repo(private val ctx: Context) {
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

    /** Fetches the app list, finding the server first if needed. Throws on failure. */
    fun load(): List<AppStatus> {
        var base = server
        if (base == null || !ping(base)) {
            base = discover() ?: base ?: throw IOException("Couldn't find the Ghost Hub PC on this Wi-Fi.")
        }
        val body = httpGet("$base/index.json")
        val arr = JSONObject(body).getJSONArray("apps")
        val pm = ctx.packageManager
        return (0 until arr.length()).map { i ->
            val entry = HubEntry.from(arr.getJSONObject(i))
            val installed = runCatching {
                val info = pm.getPackageInfo(entry.pkg, 0)
                val code = if (android.os.Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
                code to info.versionName
            }.getOrNull()
            AppStatus(entry, installed?.first, installed?.second)
        }
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

    private fun httpGet(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 4000
        c.readTimeout = 8000
        try {
            if (c.responseCode !in 200..299) throw IOException("HTTP ${c.responseCode}")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    /** Re-reads just one app's details from the PC, so a download always matches the newest build. */
    fun fresh(pkg: String): HubEntry? = runCatching {
        val base = server ?: return null
        val arr = JSONObject(httpGet("$base/index.json")).getJSONArray("apps")
        (0 until arr.length()).map { HubEntry.from(arr.getJSONObject(it)) }.firstOrNull { it.pkg == pkg }
    }.getOrNull()

    fun urlFor(entry: HubEntry): String = "${server?.trimEnd('/')}/${entry.file}"

    companion object {
        const val DISCOVERY_PORT = 8766
    }
}
