package com.alican.devicescope

import android.content.Context
import java.io.BufferedOutputStream
import java.io.InputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Minimal, dependency-free HTTP server bound to the phone's Wi-Fi/LAN address.
 * Only the device owner can use it: every request needs the token that is
 * displayed on the phone screen.
 */
class WebServer(
    private val ctx: Context,
    val port: Int,
    val token: String,
    private val stateProvider: () -> String,
    private val benchmarksProvider: () -> String = { "{\"runs\":[]}" }
) {

    private val running = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null
    private val pool = Executors.newFixedThreadPool(6)

    @Volatile
    var lastError: String? = null
        private set

    val isRunning: Boolean get() = running.get()

    fun start(): Boolean {
        if (running.get()) return true
        return try {
            val socket = ServerSocket()
            socket.reuseAddress = true
            socket.bind(java.net.InetSocketAddress("0.0.0.0", port))
            serverSocket = socket
            running.set(true)
            lastError = null
            acceptThread = Thread({ acceptLoop(socket) }, "ds-http-accept").apply {
                isDaemon = true
                start()
            }
            true
        } catch (t: Throwable) {
            lastError = t.message ?: t.javaClass.simpleName
            running.set(false)
            false
        }
    }

    fun stop() {
        running.set(false)
        try {
            serverSocket?.close()
        } catch (_: Throwable) {
        }
        serverSocket = null
        acceptThread?.interrupt()
        acceptThread = null
    }

    fun shutdown() {
        stop()
        pool.shutdownNow()
    }

    private fun acceptLoop(socket: ServerSocket) {
        while (running.get()) {
            try {
                val client = socket.accept()
                pool.execute { handle(client) }
            } catch (t: Throwable) {
                if (running.get()) continue else break
            }
        }
    }

    private fun handle(client: Socket) {
        try {
            client.soTimeout = 8000
            val input = client.getInputStream()
            val requestLine = readLine(input) ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2) return
            val method = parts[0]
            val target = parts[1]

            // Drain headers, remembering Content-Length so a POST body does not
            // poison the next request on a kept-alive connection.
            var contentLength = 0
            while (true) {
                val line = readLine(input) ?: break
                if (line.isEmpty()) break
                val name = line.substringBefore(':', "").trim().lowercase()
                if (name == "content-length") {
                    contentLength = line.substringAfter(':', "").trim().toIntOrNull() ?: 0
                }
            }
            if (contentLength in 1..65_536) {
                val body = ByteArray(contentLength)
                var read = 0
                while (read < contentLength) {
                    val n = input.read(body, read, contentLength - read)
                    if (n <= 0) break
                    read += n
                }
            }

            val path = target.substringBefore('?')
            val query = target.substringAfter('?', "")
            val params = parseQuery(query)

            if (!authorized(params)) {
                val body = unauthorizedPage()
                respond(client, 401, "text/html; charset=utf-8", body.toByteArray())
                return
            }

            val isRead = method == "GET" || method == "HEAD"
            val isPost = method == "POST"

            if (isPost && path.startsWith("/api/bench/")) {
                handleBenchControl(client, path, params)
                return
            }

            when {
                !isRead -> {
                    respond(client, 405, "text/plain; charset=utf-8", "Method Not Allowed".toByteArray())
                }
                path == "/" || path == "/index.html" || path == "/panel" -> {
                    val html = loadPanel().toByteArray()
                    respond(client, 200, "text/html; charset=utf-8", html, headOnly = method == "HEAD")
                }
                path == "/api/state" -> {
                    val json = stateProvider().toByteArray()
                    respond(client, 200, "application/json; charset=utf-8", json, headOnly = method == "HEAD")
                }
                path == "/api/benchmarks" -> {
                    val json = benchmarksProvider().toByteArray()
                    respond(client, 200, "application/json; charset=utf-8", json, headOnly = method == "HEAD")
                }
                path == "/api/bench/status" -> {
                    val json = benchController().statusJson().toByteArray()
                    respond(client, 200, "application/json; charset=utf-8", json, headOnly = method == "HEAD")
                }
                path == "/api/bench/detail" -> {
                    val json = benchController().detailJson().toByteArray()
                    respond(client, 200, "application/json; charset=utf-8", json, headOnly = method == "HEAD")
                }
                path == "/favicon.ico" -> respond(client, 204, "image/x-icon", ByteArray(0))
                else -> respond(client, 404, "text/plain; charset=utf-8", "Not Found".toByteArray())
            }
        } catch (_: Throwable) {
        } finally {
            try {
                client.close()
            } catch (_: Throwable) {
            }
        }
    }

    /**
     * Test control. These are POST-only on purpose: a link preview, a prefetch
     * or a stray GET from the browser address bar must never start a 15 minute
     * measurement. Every route still needs the token.
     */
    private fun handleBenchControl(client: Socket, path: String, params: Map<String, String>) {
        val controller = benchController()
        val main = android.os.Handler(android.os.Looper.getMainLooper())

        when (path) {
            "/api/bench/start" -> {
                val minutes = (params["minutes"]?.toIntOrNull() ?: 15).coerceIn(15, 180)
                if (controller.anyRunning) {
                    respond(
                        client, 409, "application/json; charset=utf-8",
                        "{\"started\":false,\"reason\":\"already-running\"}".toByteArray()
                    )
                    return
                }
                main.post { controller.runMarathon(minutes, bringToFront = true) }
                respond(
                    client, 202, "application/json; charset=utf-8",
                    "{\"started\":true,\"suite\":\"Maraton\",\"minutes\":$minutes}".toByteArray()
                )
            }
            "/api/bench/quick" -> {
                val suite = params["suite"]?.lowercase() ?: ""
                if (suite != "mikyas" && suite != "kinetik") {
                    respond(
                        client, 400, "application/json; charset=utf-8",
                        "{\"started\":false,\"reason\":\"unknown-suite\"}".toByteArray()
                    )
                    return
                }
                if (controller.anyRunning) {
                    respond(
                        client, 409, "application/json; charset=utf-8",
                        "{\"started\":false,\"reason\":\"already-running\"}".toByteArray()
                    )
                    return
                }
                main.post {
                    controller.bringAppToFront()
                    if (suite == "mikyas") controller.runMikyas() else controller.runKinetik()
                }
                respond(
                    client, 202, "application/json; charset=utf-8",
                    "{\"started\":true,\"suite\":\"$suite\"}".toByteArray()
                )
            }
            "/api/bench/cancel" -> {
                main.post { controller.cancelMarathon() }
                respond(
                    client, 200, "application/json; charset=utf-8",
                    "{\"cancelled\":true}".toByteArray()
                )
            }
            else -> respond(
                client, 404, "text/plain; charset=utf-8", "Not Found".toByteArray()
            )
        }
    }

    private fun benchController(): com.alican.devicescope.bench.BenchController =
        com.alican.devicescope.bench.BenchController.shared(ctx)

    private fun authorized(params: Map<String, String>): Boolean {
        val fromQuery = params["k"]
        return fromQuery == token
    }

    private fun parseQuery(query: String): Map<String, String> {
        if (query.isEmpty()) return emptyMap()
        val map = HashMap<String, String>()
        query.split("&").forEach { pair ->
            if (pair.isEmpty()) return@forEach
            val key = pair.substringBefore('=')
            val value = pair.substringAfter('=', "")
            try {
                map[URLDecoder.decode(key, "UTF-8")] = URLDecoder.decode(value, "UTF-8")
            } catch (_: Throwable) {
            }
        }
        return map
    }

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        var prev = -1
        while (true) {
            val b = input.read()
            if (b == -1) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) {
                if (prev == '\r'.code && sb.isNotEmpty()) sb.setLength(sb.length - 1)
                return sb.toString()
            }
            sb.append(b.toChar())
            prev = b
            if (sb.length > 8192) return sb.toString()
        }
    }

    private fun loadPanel(): String {
        return try {
            val raw = ctx.assets.open("panel.html").bufferedReader(Charsets.UTF_8).use { it.readText() }
            raw.replace("__TOKEN__", token).replace("__PORT__", port.toString())
        } catch (t: Throwable) {
            "<html><body style='background:#070A0E;color:#EAF2F8;font-family:sans-serif;padding:32px'>" +
                "<h2>panel.html yüklenemedi</h2><p>${t.javaClass.simpleName}: ${t.message}</p></body></html>"
        }
    }

    private fun unauthorizedPage(): String = """
<!doctype html>
<html lang="tr"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>DeviceScope · Yetkisiz</title>
<style>
html,body{height:100%;margin:0;background:#070A0E;color:#EAF2F8;
font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,sans-serif;display:flex;align-items:center;justify-content:center}
.box{max-width:420px;padding:32px;border:1px solid #22303D;border-radius:18px;background:#10161D;text-align:center}
h1{font-size:19px;margin:0 0 10px;color:#FFB020}
p{color:#8FA3B5;font-size:14px;line-height:1.6;margin:0 0 8px}
code{background:#161F28;padding:2px 6px;border-radius:5px;color:#00E5A0;font-size:13px}
</style></head><body><div class="box">
<h1>Erişim anahtarı gerekli</h1>
<p>Telefon ekranındaki bağlantıyı, anahtarı dahil ederek aç.</p>
<p><code>?k=ANAHTAR</code></p>
</div></body></html>
""".trimIndent()

    private fun respond(
        client: Socket,
        status: Int,
        contentType: String,
        body: ByteArray,
        headOnly: Boolean = false
    ) {
        val reason = when (status) {
            200 -> "OK"
            202 -> "Accepted"
            204 -> "No Content"
            400 -> "Bad Request"
            401 -> "Unauthorized"
            404 -> "Not Found"
            405 -> "Method Not Allowed"
            409 -> "Conflict"
            else -> "OK"
        }
        val header = buildString {
            append("HTTP/1.1 $status $reason\r\n")
            append("Content-Type: $contentType\r\n")
            append("Content-Length: ${body.size}\r\n")
            append("Cache-Control: no-store, max-age=0\r\n")
            append("X-Content-Type-Options: nosniff\r\n")
            append("Referrer-Policy: no-referrer\r\n")
            append("Connection: close\r\n")
            append("\r\n")
        }
        val out = BufferedOutputStream(client.getOutputStream())
        out.write(header.toByteArray(Charsets.UTF_8))
        if (!headOnly && body.isNotEmpty()) out.write(body)
        out.flush()
    }

    companion object {

        fun localIpv4(): String? {
            val all = allIpv4()
            return all.firstOrNull { it.startsWith("192.168.") }
                ?: all.firstOrNull { it.startsWith("10.") }
                ?: all.firstOrNull { isPrivate172(it) }
                ?: all.firstOrNull()
        }

        private fun isPrivate172(ip: String): Boolean {
            if (!ip.startsWith("172.")) return false
            val second = ip.split(".").getOrNull(1)?.toIntOrNull() ?: return false
            return second in 16..31
        }

        fun allIpv4(): List<String> {
            val out = ArrayList<String>()
            try {
                for (nif in NetworkInterface.getNetworkInterfaces()) {
                    if (!nif.isUp || nif.isLoopback) continue
                    for (addr in nif.inetAddresses) {
                        if (addr is Inet4Address && !addr.isLoopbackAddress && !addr.isLinkLocalAddress) {
                            addr.hostAddress?.let { out.add(it) }
                        }
                    }
                }
            } catch (_: Throwable) {
            }
            return out.distinct()
        }
    }
}
