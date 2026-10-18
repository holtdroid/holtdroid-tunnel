package io.github.kriakiku.holtdroid.tunnel

import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket

object SocksProbe {
    const val OK = "ok"

    fun check(host: String, port: Int, timeoutMs: Int = 2000): String = try {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(host, port), timeoutMs)
            socket.soTimeout = timeoutMs
            socket.getOutputStream().apply {
                write(byteArrayOf(5, 1, 0))
                flush()
            }
            val input = socket.getInputStream()
            val version = input.read()
            val method = input.read()
            when {
                version < 0 || method < 0 -> "closed by peer: nothing listens behind $host:$port"
                version != 5 -> "not a SOCKS5 proxy (version $version)"
                method != 0 -> "proxy requires authentication (method $method)"
                else -> OK
            }
        }
    } catch (e: IOException) {
        "unreachable: ${e.message ?: e.javaClass.simpleName}"
    }
}
