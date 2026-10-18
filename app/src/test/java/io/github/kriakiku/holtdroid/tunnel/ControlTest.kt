package io.github.kriakiku.holtdroid.tunnel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ControlTest {
    private class FakePlatform : Control.Platform {
        override val packageName = Control.PACKAGE
        var consent = true
        var outcome: (TunnelConfig) -> Status = { Status(State.RUNNING, it, since = 1) }
        var started: TunnelConfig? = null
        var stopped = 0
        var saved: TunnelConfig? = null
        override fun hasConsent() = consent
        override fun start(config: TunnelConfig) {
            started = config
            saved = config
            Tunnel.update(outcome(config))
        }
        override fun stop() {
            stopped++
            Tunnel.update(Status(State.STOPPED, Tunnel.status.config))
        }
        override fun lastConfig() = saved
        override fun probe(config: TunnelConfig) = "ok"
    }

    private val platform = FakePlatform()
    private val control = Control(platform, timeoutMs = 200)

    @Before
    fun reset() {
        Tunnel.update(Status())
        Tunnel.traffic = null
    }

    @Test
    fun startRunsWithParsedConfig() {
        val reply = control.handle(Control.ACTION_START, mapOf("port" to 7890, "deny" to "com.example.a"))
        assertEquals(Control.RESULT_OK, reply.code)
        assertEquals(7890, platform.started?.port)
        assertEquals(listOf("com.example.a"), platform.started?.deny)
        assertTrue(reply.json, reply.json.startsWith("""{"state":"running","version":"""))
        assertTrue(reply.json, reply.json.contains(""""consent":true,"since":1,"error":null,"proxy":"ok""""))
        assertTrue(reply.json, reply.json.contains(""""port":7890"""))
    }

    @Test
    fun startRejectsBadExtrasWithoutStarting() {
        val reply = control.handle(Control.ACTION_START, mapOf("port" to "x"))
        assertEquals(Control.RESULT_ERROR, reply.code)
        assertNull(platform.started)
        assertTrue(reply.json, reply.json.contains(""""error":"port must be an integer: x""""))
    }

    @Test
    fun startWithoutConsent() {
        platform.consent = false
        val reply = control.handle(Control.ACTION_START, emptyMap())
        assertEquals(Control.RESULT_CONSENT, reply.code)
        assertNull(platform.started)
        assertTrue(reply.json, reply.json.contains(""""consent":false"""))
        assertTrue(reply.json, reply.json.contains("appops set ${Control.PACKAGE} ACTIVATE_VPN allow"))
    }

    @Test
    fun startFailureIsReported() {
        platform.outcome = { Status(State.FAILED, it, error = "boom") }
        val reply = control.handle(Control.ACTION_START, emptyMap())
        assertEquals(Control.RESULT_ERROR, reply.code)
        assertTrue(reply.json, reply.json.contains(""""state":"failed""""))
        assertTrue(reply.json, reply.json.contains(""""error":"boom""""))
    }

    @Test
    fun startThatThrowsIsReported() {
        val throwing = object : Control.Platform by platform {
            override fun start(config: TunnelConfig) = throw IllegalStateException("not allowed")
        }
        val reply = Control(throwing, 200).handle(Control.ACTION_START, emptyMap())
        assertEquals(Control.RESULT_ERROR, reply.code)
        assertEquals(State.FAILED, Tunnel.status.state)
        assertTrue(reply.json, reply.json.contains(""""error":"not allowed""""))
    }

    @Test
    fun startTimesOutWhileStarting() {
        platform.outcome = { Status(State.STARTING, it) }
        val reply = control.handle(Control.ACTION_START, emptyMap())
        assertEquals(Control.RESULT_ERROR, reply.code)
        assertTrue(reply.json, reply.json.contains(""""state":"starting""""))
    }

    @Test
    fun stop() {
        control.handle(Control.ACTION_START, emptyMap())
        val reply = control.handle(Control.ACTION_STOP, emptyMap())
        assertEquals(Control.RESULT_OK, reply.code)
        assertEquals(1, platform.stopped)
        assertTrue(reply.json, reply.json.contains(""""state":"stopped""""))
    }

    @Test
    fun stopWhenStoppedIsNoop() {
        val reply = control.handle(Control.ACTION_STOP, emptyMap())
        assertEquals(Control.RESULT_OK, reply.code)
        assertEquals(0, platform.stopped)
    }

    @Test
    fun statusIncludesTraffic() {
        control.handle(Control.ACTION_START, emptyMap())
        Tunnel.traffic = { Traffic(tcpActive = 2, tcpTotal = 5, uplink = 10, downlink = 20) }
        val reply = control.handle(Control.ACTION_STATUS, emptyMap())
        assertEquals(Control.RESULT_OK, reply.code)
        assertTrue(reply.json, reply.json.contains(""""traffic":{"tcp_active":2,"tcp_total":5,"udp_active":0,"udp_total":0,"uplink":10,"downlink":20,"failures":0,"last_error":null}"""))
    }

    @Test
    fun statusWhenNeverStarted() {
        val reply = control.handle(Control.ACTION_STATUS, emptyMap())
        assertEquals(Control.RESULT_OK, reply.code)
        assertTrue(reply.json, reply.json.startsWith("""{"state":"stopped""""))
        assertTrue(reply.json, reply.json.contains(""""proxy":null,"config":null,"traffic":null"""))
    }

    @Test
    fun unknownAction() {
        val reply = control.handle("${Control.PACKAGE}.RESTART", emptyMap())
        assertEquals(Control.RESULT_ERROR, reply.code)
        assertTrue(reply.json, reply.json.contains("unknown action"))
    }

    @Test
    fun actionNames() {
        assertEquals("io.github.kriakiku.holtdroid.tunnel.START", Control.ACTION_START)
        assertEquals("io.github.kriakiku.holtdroid.tunnel.STOP", Control.ACTION_STOP)
        assertEquals("io.github.kriakiku.holtdroid.tunnel.STATUS", Control.ACTION_STATUS)
        assertEquals(BuildConfig.APPLICATION_ID, Control.PACKAGE)
    }
}
