package io.github.m4hdisa.bridge

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/** A downloaded page. Header names are lower case. */
class Page(val code: Int, val headers: Map<String, String>, val body: String) {
    val ok get() = code in 200..299
}

/**
 * HTTP for Bridge's own requests. Bridge itself stays outside the VPN, so `direct` uses the
 * phone's network; `through` goes through the VPN's local proxy, for lists blocked in Iran.
 */
object Net {
    // Panels (Marzban, 3x-ui, Hiddify...) pick the output format from the User-Agent.
    const val USER_AGENT = "v2rayN/7.0 Bridge-Android/${BuildConfig.VERSION_NAME}"
    private const val MAX_BODY = 16 * 1024 * 1024

    fun direct(url: String, timeoutMs: Int = 15_000): Page {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = timeoutMs
            connection.readTimeout = timeoutMs
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("User-Agent", USER_AGENT)
            val code = connection.responseCode
            val stream = if (code >= 400) connection.errorStream else connection.inputStream
            val body = stream?.use { readLimited(it) } ?: ""
            val headers = connection.headerFields.filterKeys { it != null }.mapKeys { it.key.lowercase() }.mapValues { it.value.joinToString(",") }
            return Page(code, headers, body)
        } finally {
            connection.disconnect()
        }
    }

    /** The same request through the VPN's local proxy (HTTP CONNECT with a password). */
    fun through(proxy: LocalAccess, url: String, timeoutMs: Int = 20_000): Page {
        var target = URL(url)
        repeat(4) {
            val page = once(proxy, target, timeoutMs)
            val location = page.headers["location"]
            if (page.code !in 300..399 || location == null) return page
            target = URL(target, location)
        }
        throw IOException("too many redirects")
    }

    private fun once(proxy: LocalAccess, url: URL, timeoutMs: Int): Page {
        val https = url.protocol == "https"
        val port = if (url.port > 0) url.port else url.defaultPort
        val socket = Socket()
        try {
            socket.connect(InetSocketAddress("127.0.0.1", proxy.port), timeoutMs)
            socket.soTimeout = timeoutMs
            val auth = android.util.Base64.encodeToString("${proxy.user}:${proxy.secret}".toByteArray(), android.util.Base64.NO_WRAP)
            write(socket.getOutputStream(), "CONNECT ${url.host}:$port HTTP/1.1\r\nHost: ${url.host}:$port\r\nProxy-Authorization: Basic $auth\r\n\r\n")
            val raw = socket.getInputStream()
            val status = readLine(raw)
            while (readLine(raw).isNotEmpty()) Unit
            if (status.split(' ').getOrNull(1) != "200") throw IOException("the VPN's proxy answered: $status")
            val stream: Socket = if (https) {
                val ssl = (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(socket, url.host, port, true) as SSLSocket
                ssl.soTimeout = timeoutMs
                ssl.startHandshake()
                if (!HttpsURLConnection.getDefaultHostnameVerifier().verify(url.host, ssl.session)) throw IOException("the certificate is not for ${url.host}")
                ssl
            } else {
                socket
            }
            val path = (url.path.ifEmpty { "/" }) + (url.query?.let { "?$it" } ?: "")
            write(stream.getOutputStream(), "GET $path HTTP/1.1\r\nHost: ${url.host}\r\nUser-Agent: $USER_AGENT\r\nAccept-Encoding: identity\r\nConnection: close\r\n\r\n")
            return readResponse(stream.getInputStream())
        } finally {
            runCatching { socket.close() }
        }
    }

    private fun readResponse(input: InputStream): Page {
        val status = readLine(input)
        val code = status.split(' ').getOrNull(1)?.toIntOrNull() ?: throw IOException("not an HTTP answer")
        val headers = mutableMapOf<String, String>()
        while (true) {
            val line = readLine(input)
            if (line.isEmpty()) break
            val name = line.substringBefore(':').trim().lowercase()
            val value = line.substringAfter(':', "").trim()
            headers[name] = headers[name]?.let { "$it,$value" } ?: value
        }
        val body = when {
            headers["transfer-encoding"]?.contains("chunked", ignoreCase = true) == true -> readChunked(input)
            headers["content-length"] != null -> String(readExactly(input, headers["content-length"]!!.trim().toInt()), Charsets.UTF_8)
            else -> readLimited(input)
        }
        return Page(code, headers, body)
    }

    private fun readChunked(input: InputStream): String {
        val out = ByteArrayOutputStream()
        while (true) {
            val size = readLine(input).substringBefore(';').trim().toInt(16)
            if (size == 0) break
            out.write(readExactly(input, size))
            readLine(input)
            if (out.size() > MAX_BODY) throw IOException("the answer is too large")
        }
        return out.toString("UTF-8")
    }

    private fun readExactly(input: InputStream, size: Int): ByteArray {
        if (size > MAX_BODY) throw IOException("the answer is too large")
        val bytes = ByteArray(size)
        var read = 0
        while (read < size) {
            val n = input.read(bytes, read, size - read)
            if (n < 0) throw IOException("the connection closed early")
            read += n
        }
        return bytes
    }

    private fun readLimited(input: InputStream): String {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            out.write(buffer, 0, n)
            if (out.size() > MAX_BODY) throw IOException("the answer is too large")
        }
        return out.toString("UTF-8")
    }

    /** One header line, read a byte at a time so nothing past it is taken from the stream. */
    private fun readLine(input: InputStream): String {
        val out = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) {
                if (out.size() == 0) throw IOException("the connection closed early")
                break
            }
            if (b == '\n'.code) break
            if (b != '\r'.code) out.write(b)
            if (out.size() > 8192) throw IOException("the header is too long")
        }
        return out.toString("UTF-8")
    }

    private fun write(out: OutputStream, text: String) {
        out.write(text.toByteArray(Charsets.UTF_8))
        out.flush()
    }

    /** How long a TCP connection to the server takes, in ms, or DELAY_FAILED. Only says the address answers. */
    fun tcpDelay(host: String, port: Int, timeoutMs: Int = 2_500): Int {
        val started = System.nanoTime()
        return try {
            Socket().use { it.connect(InetSocketAddress(host, port), timeoutMs) }
            ((System.nanoTime() - started) / 1_000_000).toInt().coerceAtLeast(1)
        } catch (_: Exception) {
            DELAY_FAILED
        }
    }
}
