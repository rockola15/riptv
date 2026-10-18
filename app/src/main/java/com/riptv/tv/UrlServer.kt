package com.riptv.tv

import java.io.Closeable
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import kotlin.concurrent.thread

/**
 * Servidor HTTP mínimo para recibir la URL de la lista desde otro dispositivo de la red local
 * (móvil o PC): sirve un formulario y entrega el texto enviado a [onUrl]. La dirección lleva un código aleatorio
 * (el del QR) y se ignora cualquier petición que no lo tenga, para que otros equipos de la red no puedan
 * mandarle una lista.
 */
class UrlServer(
    private val port: Int = 8080,
    private val token: String = newToken(),
    private val onUrl: (String) -> Unit,
) : Closeable {
    private var server: ServerSocket? = null

    /** Dirección que hay que abrir en el otro dispositivo, o null si no hay red local. */
    val address: String?
        get() = localIp()?.let { "http://$it:$port/$token" }

    fun start(): Boolean {
        if (server != null) return true
        server = try { ServerSocket(port) } catch (e: Exception) { return false }
        thread(isDaemon = true) {
            val s = server ?: return@thread
            while (!s.isClosed) {
                try {
                    s.accept().use(::handle)
                } catch (e: Throwable) {
                    // Cierre del servidor o conexión rota: se ignora y se sigue escuchando.
                }
            }
        }
        return true
    }

    override fun close() {
        server?.close()
        server = null
    }

    private fun handle(sock: Socket) {
        sock.soTimeout = 5_000
        val input = sock.getInputStream().buffered()
        fun line(): String? {
            val sb = StringBuilder()
            while (true) {
                val c = input.read()
                if (c < 0) return if (sb.isEmpty()) null else sb.toString()
                if (c == '\n'.code) return sb.toString().trimEnd('\r')
                sb.append(c.toChar())
            }
        }

        val request = line() ?: return
        var length = 0
        while (true) {
            val h = line() ?: break
            if (h.isEmpty()) break
            if (h.startsWith("Content-Length:", ignoreCase = true)) length = h.substringAfter(':').trim().toIntOrNull() ?: 0
        }

        val path = request.split(' ').getOrNull(1)?.substringBefore('?')
        if (path != "/$token") {
            reply(sock, "404 Not Found", "No encontrado")
            return
        }

        val sent = request.startsWith("POST") && length in 1..8192
        if (sent) {
            // Lectura a mano: InputStream.readNBytes no existe antes de Android 13 (API 33).
            val buf = ByteArray(length)
            var read = 0
            while (read < length) {
                val n = input.read(buf, read, length - read)
                if (n < 0) break
                read += n
            }
            val body = String(buf, 0, read, Charsets.UTF_8)
            val value = body.split('&').firstOrNull { it.startsWith("url=") }?.substringAfter('=').orEmpty()
            val url = URLDecoder.decode(value, "UTF-8").trim()
            if (url.isNotEmpty()) onUrl(url)
        }

        reply(sock, "200 OK", page(sent))
    }

    private fun reply(sock: Socket, status: String, html: String) {
        val bytes = html.toByteArray(Charsets.UTF_8)
        sock.getOutputStream().apply {
            write(
                ("HTTP/1.1 $status\r\nContent-Type: text/html; charset=utf-8\r\n" +
                    "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n").toByteArray(),
            )
            write(bytes)
            flush()
        }
    }

    companion object {
        /** Código aleatorio de 8 caracteres (sin letras que se confunden) para la dirección del QR. */
        fun newToken(): String {
            val alphabet = "abcdefghjkmnpqrstuvwxyz23456789"
            val random = java.security.SecureRandom()
            return String(CharArray(8) { alphabet[random.nextInt(alphabet.length)] })
        }
    }

    private fun page(sent: Boolean) = """
        <!doctype html><html><head><meta charset="utf-8">
        <meta name="viewport" content="width=device-width,initial-scale=1">
        <title>RipTV</title>
        <style>body{font-family:sans-serif;margin:24px}textarea{width:100%;height:140px;font-size:16px}
        button{font-size:18px;padding:10px 20px;margin-top:12px}</style></head><body>
        <h2>RipTV · URL de la lista</h2>
        ${if (sent) "<p><b>✓ Enviada. La tele está cargando la lista.</b></p>" else ""}
        <form method="post"><textarea name="url" placeholder="http://…/lista.m3u"></textarea><br>
        <button type="submit">Enviar a la tele</button></form></body></html>
    """.trimIndent()

    // En algunos dispositivos listar las interfaces de red falla o devuelve null: entonces simplemente no hay QR.
    private fun localIp(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces()?.asSequence().orEmpty()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.asSequence() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { it.isSiteLocalAddress }
            ?.hostAddress
    }.getOrNull()
}
