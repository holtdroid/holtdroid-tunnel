package io.github.kriakiku.holtdroid.tunnel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TunnelConfigTest {
    private val self = Control.PACKAGE

    private fun parse(vararg extras: Pair<String, Any?>) = TunnelConfig.parse(mapOf(*extras), self)

    private fun invalid(vararg extras: Pair<String, Any?>): String =
        assertThrows(TunnelConfig.Invalid::class.java) { parse(*extras) }.message!!

    @Test
    fun defaults() {
        val c = parse()
        assertEquals("127.0.0.1", c.host)
        assertEquals(1080, c.port)
        assertEquals(9000, c.mtu)
        assertTrue(c.ipv6)
        assertTrue(c.udp)
        assertEquals(listOf(TunnelConfig.DNS4, TunnelConfig.DNS6), c.dnsServers)
        assertTrue(c.allow.isEmpty())
        assertTrue(c.deny.isEmpty())
    }

    @Test
    fun typedExtrasFromAm() {
        val c = parse("port" to 7890, "mtu" to 1500, "ipv6" to false, "udp" to false, "host" to "::1")
        assertEquals(7890, c.port)
        assertEquals(1500, c.mtu)
        assertFalse(c.ipv6)
        assertFalse(c.udp)
        assertEquals("::1", c.host)
        assertEquals(listOf(TunnelConfig.DNS4), c.dnsServers)
    }

    @Test
    fun stringExtrasAreCoerced() {
        val c = parse("port" to " 7890 ", "mtu" to "1400", "ipv6" to "off", "udp" to "1", "host" to "[::1]")
        assertEquals(7890, c.port)
        assertEquals(1400, c.mtu)
        assertFalse(c.ipv6)
        assertTrue(c.udp)
        assertEquals("::1", c.host)
    }

    @Test
    fun longPortFitsInt() {
        assertEquals(1081, parse("port" to 1081L).port)
        assertTrue(invalid("port" to Long.MAX_VALUE).startsWith("port"))
    }

    @Test
    fun lists() {
        assertEquals(listOf("com.example.a", "com.example.b"), parse("allow" to "com.example.a, com.example.b,,").allow)
        assertEquals(listOf("com.example.a", "com.example.b"), parse("allow" to arrayOf("com.example.a", "com.example.b")).allow)
        assertEquals(listOf("com.example.a"), parse("deny" to "com.example.a com.example.a").deny)
        assertEquals(listOf("10.0.0.53", "fd00::53"), parse("dns" to "10.0.0.53;fd00::53").dnsServers)
    }

    @Test
    fun selfIsDroppedFromDeny() {
        assertEquals(listOf("com.example.a"), parse("deny" to "$self,com.example.a").deny)
    }

    @Test
    fun rejectsBadInput() {
        assertTrue(invalid("prot" to 1080).startsWith("unknown extra: prot"))
        assertTrue(invalid("port" to 0).startsWith("port out of range"))
        assertTrue(invalid("port" to 65536).startsWith("port out of range"))
        assertTrue(invalid("port" to "abc").startsWith("port must be an integer"))
        assertTrue(invalid("mtu" to 576).startsWith("mtu out of range"))
        assertTrue(invalid("host" to "localhost").startsWith("host must be an IP literal"))
        assertTrue(invalid("host" to "256.0.0.1").startsWith("host must be an IP literal"))
        assertTrue(invalid("ipv6" to "maybe").startsWith("ipv6 must be a boolean"))
        assertTrue(invalid("dns" to "dns.example").startsWith("dns must be IP literals"))
        assertTrue(invalid("allow" to "not a package").startsWith("not a package name"))
        assertTrue(invalid("allow" to "com.example.a", "deny" to "com.example.b").startsWith("allow and deny"))
        assertTrue(invalid("allow" to self).startsWith("allow must not include"))
        assertTrue(invalid("port" to listOf(1)).startsWith("port must be an integer"))
    }

    @Test
    fun extrasRoundTrip() {
        val c = TunnelConfig(host = "::1", port = 2000, mtu = 1500, ipv6 = false, udp = false, dns = listOf("10.0.0.53"), deny = listOf("com.example.a"))
        assertEquals(c, TunnelConfig.parse(c.toExtras(), self))
        val d = TunnelConfig(allow = listOf("com.example.a", "com.example.b"))
        assertEquals(d, TunnelConfig.parse(d.toExtras(), self))
    }

    @Test
    fun ipLiterals() {
        listOf("0.0.0.0", "127.0.0.1", "255.255.255.255", "::", "::1", "fdfe:dcba:9876::2", "2001:db8::1", "::ffff:192.0.2.1").forEach {
            assertTrue(it, TunnelConfig.isIp(it))
        }
        listOf("", "1.2.3", "1.2.3.4.5", "01.2.3.4x", "host", "1:2", "g::1", "1:2:3:4:5:6:7:8:9").forEach {
            assertFalse(it, TunnelConfig.isIp(it))
        }
    }

    @Test
    fun json() {
        val json = TunnelConfig(port = 1081, allow = listOf("com.example.a")).toJson()
        assertEquals(
            """{"host":"127.0.0.1","port":1081,"mtu":9000,"ipv6":true,"udp":true,"dns":["172.19.0.2","fdfe:dcba:9876::2"],"allow":["com.example.a"],"deny":[]}""",
            json,
        )
    }
}
