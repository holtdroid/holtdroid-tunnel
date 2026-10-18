package io.github.kriakiku.holtdroid.tunnel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.concurrent.thread

class ProbeAndJsonTest {
    private fun serve(answer: ByteArray?): Int {
        val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        thread(isDaemon = true) {
            server.use {
                it.accept().use { s ->
                    val greeting = ByteArray(3)
                    s.getInputStream().read(greeting)
                    if (answer != null) s.getOutputStream().write(answer)
                }
            }
        }
        return server.localPort
    }

    @Test
    fun socks5Ok() {
        assertEquals(SocksProbe.OK, SocksProbe.check("127.0.0.1", serve(byteArrayOf(5, 0))))
    }

    @Test
    fun closedLikeAdbReverseWithoutHost() {
        assertTrue(SocksProbe.check("127.0.0.1", serve(null)).startsWith("closed by peer"))
    }

    @Test
    fun authRequired() {
        assertTrue(SocksProbe.check("127.0.0.1", serve(byteArrayOf(5, 2))).startsWith("proxy requires authentication"))
    }

    @Test
    fun notSocks() {
        assertTrue(SocksProbe.check("127.0.0.1", serve("HT".toByteArray())).startsWith("not a SOCKS5 proxy"))
    }

    @Test
    fun unreachable() {
        val port = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }
        assertTrue(SocksProbe.check("127.0.0.1", port).startsWith("unreachable"))
    }

    @Test
    fun jsonEscapes() {
        assertEquals("""{"a":"q\"b\\s\n\u0001","n":null,"l":[1,true,"x"],"e":"running"}""", Json.obj("a" to "q\"b\\s\n\u0001", "n" to null, "l" to listOf(1, true, "x"), "e" to State.RUNNING))
    }
}
