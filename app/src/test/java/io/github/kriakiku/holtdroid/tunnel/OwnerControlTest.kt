package io.github.kriakiku.holtdroid.tunnel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OwnerControlTest {
    private class FakeOwner : OwnerControl.Platform {
        var owner = true
        var lockdown: OwnerConfig.Lockdown? = null
        var session: OwnerConfig.Session? = null
        var ended = 0
        var released = 0
        var rows: List<Pair<String, String>>? = null
        var passwordReset = true
        override fun isOwner() = owner
        override fun lockdown(config: OwnerConfig.Lockdown): List<String> {
            lockdown = config
            return listOf("note")
        }
        override fun release(): List<String> {
            released++
            return emptyList()
        }
        override fun clearPassword() = passwordReset
        override fun startSession(session: OwnerConfig.Session): List<String> {
            this.session = session
            return listOf(session.target, "com.example.launcher")
        }
        override fun endSession() {
            ended++
        }
        override fun wallpaper(rows: List<Pair<String, String>>) {
            this.rows = rows
        }
        override fun statusJson() = """{"device_owner":$owner}"""
    }

    private val fake = FakeOwner()
    private val control = OwnerControl(fake)

    @Test
    fun lockdown() {
        val reply = control.handle(OwnerControl.ACTION_LOCKDOWN, mapOf("skip" to "no_sms"))!!
        assertEquals(Control.RESULT_OK, reply.code)
        assertEquals("""{"error":null,"notes":["note"],"owner":{"device_owner":true}}""", reply.json)
        assertTrue("no_sms" !in fake.lockdown!!.restrictions)
    }

    @Test
    fun badExtrasDoNotTouchPolicy() {
        val reply = control.handle(OwnerControl.ACTION_LOCKDOWN, mapOf("restrictions" to "no_debugging_features"))!!
        assertEquals(Control.RESULT_ERROR, reply.code)
        assertNull(fake.lockdown)
        assertTrue(reply.json, reply.json.contains("turns adb off"))
    }

    @Test
    fun notOwner() {
        fake.owner = false
        for (action in listOf(OwnerControl.ACTION_LOCKDOWN, OwnerControl.ACTION_RELEASE, OwnerControl.ACTION_CLEAR_PASSWORD, OwnerControl.ACTION_SESSION_START, OwnerControl.ACTION_SESSION_END)) {
            val reply = control.handle(action, if (action == OwnerControl.ACTION_SESSION_START) mapOf("package" to "com.example.app") else emptyMap())!!
            assertEquals(action, OwnerControl.RESULT_NOT_OWNER, reply.code)
            assertTrue(reply.json, reply.json.contains("dpm set-device-owner ${Control.PACKAGE}/.AdminReceiver"))
        }
        assertEquals(0, fake.released)
    }

    @Test
    fun wallpaperWorksWithoutOwner() {
        fake.owner = false
        val reply = control.handle(OwnerControl.ACTION_WALLPAPER, mapOf("rows" to """[["session","s-1"]]"""))!!
        assertEquals(Control.RESULT_OK, reply.code)
        assertEquals(listOf("session" to "s-1"), fake.rows)
    }

    @Test
    fun session() {
        val reply = control.handle(OwnerControl.ACTION_SESSION_START, mapOf("package" to "com.example.app", "features" to "home,overview"))!!
        assertEquals(Control.RESULT_OK, reply.code)
        assertTrue(reply.json, reply.json.contains("2 packages allowed in lock task"))
        assertEquals(12, fake.session!!.featureFlags)
        assertEquals(Control.RESULT_OK, control.handle(OwnerControl.ACTION_SESSION_END, emptyMap())!!.code)
        assertEquals(1, fake.ended)
    }

    @Test
    fun actionsWithoutExtrasRejectExtras() {
        val reply = control.handle(OwnerControl.ACTION_RELEASE, mapOf("force" to true))!!
        assertEquals(Control.RESULT_ERROR, reply.code)
        assertEquals(0, fake.released)
    }

    @Test
    fun clearPasswordFailure() {
        fake.passwordReset = false
        val reply = control.handle(OwnerControl.ACTION_CLEAR_PASSWORD, emptyMap())!!
        assertEquals(Control.RESULT_ERROR, reply.code)
        assertTrue(reply.json, reply.json.contains("the password was not reset"))
    }

    @Test
    fun unknownActionFallsThrough() {
        assertNull(control.handle("${Control.PACKAGE}.REBOOT", emptyMap()))
    }

    @Test
    fun actionNames() {
        assertEquals("io.github.kriakiku.holtdroid.tunnel.LOCKDOWN", OwnerControl.ACTION_LOCKDOWN)
        assertEquals("io.github.kriakiku.holtdroid.tunnel.RELEASE", OwnerControl.ACTION_RELEASE)
        assertEquals("io.github.kriakiku.holtdroid.tunnel.CLEAR_PASSWORD", OwnerControl.ACTION_CLEAR_PASSWORD)
        assertEquals("io.github.kriakiku.holtdroid.tunnel.SESSION_START", OwnerControl.ACTION_SESSION_START)
        assertEquals("io.github.kriakiku.holtdroid.tunnel.SESSION_END", OwnerControl.ACTION_SESSION_END)
        assertEquals("io.github.kriakiku.holtdroid.tunnel.WALLPAPER", OwnerControl.ACTION_WALLPAPER)
    }
}
