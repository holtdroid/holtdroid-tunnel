package io.github.kriakiku.holtdroid.tunnel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class OwnerConfigTest {
    private fun invalid(block: () -> Unit): String = assertThrows(OwnerConfig.Invalid::class.java) { block() }.message!!

    @Test
    fun lockdownDefaults() {
        val c = OwnerConfig.parseLockdown(emptyMap())
        assertEquals(OwnerConfig.RESTRICTIONS.map { it.key }, c.restrictions)
        assertTrue("no_config_wifi" in c.restrictions)
        assertTrue("disallow_microphone_toggle" in c.restrictions)
        assertFalse("no_debugging_features" in c.restrictions)
        assertEquals(OwnerConfig.DEFAULT_HIDDEN, c.hide)
        assertEquals(OwnerConfig.Vpn.LOCKDOWN, c.vpn)
        assertEquals(OwnerConfig.Updates.POSTPONE, c.updates)
        assertTrue(c.camera)
        assertTrue(c.keyguard)
    }

    @Test
    fun lockdownSkipAndOverride() {
        val skipped = OwnerConfig.parseLockdown(mapOf("skip" to "disallow_microphone_toggle,disallow_camera_toggle"))
        assertFalse("disallow_microphone_toggle" in skipped.restrictions)
        assertFalse("disallow_camera_toggle" in skipped.restrictions)
        assertEquals(OwnerConfig.RESTRICTIONS.size - 2, skipped.restrictions.size)
        val chosen = OwnerConfig.parseLockdown(mapOf("restrictions" to arrayOf("no_sms", "no_sms"), "hide" to "", "vpn" to "ON", "updates" to "none", "camera" to false, "keyguard" to "0"))
        assertEquals(listOf("no_sms"), chosen.restrictions)
        assertTrue(chosen.hide.isEmpty())
        assertEquals(OwnerConfig.Vpn.ON, chosen.vpn)
        assertEquals(OwnerConfig.Updates.NONE, chosen.updates)
        assertFalse(chosen.camera)
        assertFalse(chosen.keyguard)
    }

    @Test
    fun lockdownRejects() {
        assertTrue(invalid { OwnerConfig.parseLockdown(mapOf("restrictions" to "no_debugging_features")) }.contains("turns adb off"))
        assertTrue(invalid { OwnerConfig.parseLockdown(mapOf("skip" to "no_such_thing")) }.startsWith("unknown restriction"))
        assertTrue(invalid { OwnerConfig.parseLockdown(mapOf("restrictions" to "no_sms", "skip" to "no_sms")) }.startsWith("restrictions and skip"))
        assertTrue(invalid { OwnerConfig.parseLockdown(mapOf("hide" to "com.samsung.android.emergency")) }.contains("emergency"))
        assertTrue(invalid { OwnerConfig.parseLockdown(mapOf("hide" to "com.google.android.dialer")) }.contains("emergency"))
        assertTrue(invalid { OwnerConfig.parseLockdown(mapOf("vpn" to "maybe")) }.startsWith("vpn must be one of lockdown|on|off"))
        assertTrue(invalid { OwnerConfig.parseLockdown(mapOf("hid" to "x.y")) }.startsWith("unknown extra"))
    }

    @Test
    fun lockdownRoundTripAndSdkFilter() {
        val c = OwnerConfig.parseLockdown(mapOf("skip" to "no_sms", "hide" to "com.example.a", "vpn" to "off", "camera" to false))
        assertEquals(c, OwnerConfig.parseLockdown(c.toExtras()))
        val old = c.restrictionsFor(28)
        assertFalse("disallow_config_private_dns" in old)
        assertFalse("disallow_microphone_toggle" in old)
        assertTrue("no_factory_reset" in old)
        assertEquals(c.restrictions, c.restrictionsFor(36))
    }

    @Test
    fun sessionParsing() {
        val s = OwnerConfig.parseSession(mapOf("package" to "com.example.app", "allow" to "com.example.helper", "deny" to "com.example.browser"))
        assertEquals("com.example.app", s.target)
        assertEquals(OwnerConfig.DEFAULT_FEATURES, s.features)
        assertEquals(1 + 2 + 4 + 8 + 16, s.featureFlags)
        assertEquals(4, OwnerConfig.parseSession(mapOf("package" to "com.example.app", "features" to "HOME")).featureFlags)
        assertEquals(0, OwnerConfig.parseSession(mapOf("package" to "com.example.app", "features" to "none")).featureFlags)
        assertTrue(invalid { OwnerConfig.parseSession(emptyMap()) }.startsWith("package is required"))
        assertTrue(invalid { OwnerConfig.parseSession(mapOf("package" to "x")) }.startsWith("not a package name"))
        assertTrue(invalid { OwnerConfig.parseSession(mapOf("package" to "com.example.app", "deny" to "com.example.app")) }.startsWith("deny must not include"))
        assertTrue(invalid { OwnerConfig.parseSession(mapOf("package" to "com.example.app", "deny" to "com.android.phone")) }.contains("emergency"))
        assertTrue(invalid { OwnerConfig.parseSession(mapOf("package" to "com.example.app", "features" to "overview")) }.startsWith("overview needs home"))
        assertTrue(invalid { OwnerConfig.parseSession(mapOf("package" to "com.example.app", "features" to "power")) }.startsWith("unknown lock task feature"))
    }

    @Test
    fun allowlist() {
        val launcher = listOf("com.example.browser", "com.android.settings", "com.sec.android.app.samsungapps", "com.samsung.android.dialer", "com.example.notes")
        val installed = setOf("com.android.phone", "com.samsung.android.emergency", "com.samsung.android.dialer", "com.google.android.permissioncontroller")
        val s = OwnerConfig.Session("com.example.app", allow = listOf("com.example.hidden"), deny = listOf("com.example.notes"))
        val list = s.allowlist(launcher, installed, "com.sec.android.app.launcher", Control.PACKAGE)
        assertEquals(
            listOf(
                "com.example.browser",
                "com.samsung.android.dialer",
                "com.example.hidden",
                "com.android.phone",
                "com.samsung.android.emergency",
                "com.google.android.permissioncontroller",
                "com.example.app",
                "com.sec.android.app.launcher",
                Control.PACKAGE,
            ),
            list,
        )
    }

    @Test
    fun protectedPackagesAreNeverDeniedByDefault() {
        assertTrue((OwnerConfig.LOCK_TASK_DENY intersect OwnerConfig.PROTECTED).isEmpty())
        assertTrue((OwnerConfig.DEFAULT_HIDDEN intersect OwnerConfig.PROTECTED).isEmpty())
    }

    @Test
    fun wallpaperRows() {
        val wrapped = """{"rows":[["session","s-1"],["app","Example · com.example.app"],["note","q\"\\ é"]]}"""
        val rows = OwnerConfig.parseWallpaper(mapOf("rows" to wrapped))
        assertEquals(listOf("session" to "s-1", "app" to "Example · com.example.app", "note" to "q\"\\ é"), rows)
        assertEquals(listOf("a" to "b"), OwnerConfig.parseWallpaper(mapOf("rows" to """[["a","b"]]""")))
        val encoded = Base64.getEncoder().encodeToString(wrapped.toByteArray())
        assertEquals(rows, OwnerConfig.parseWallpaper(mapOf("rows_b64" to encoded)))
        assertEquals(emptyList<Pair<String, String>>(), OwnerConfig.parseWallpaper(emptyMap()))
        assertEquals(emptyList<Pair<String, String>>(), OwnerConfig.parseWallpaper(mapOf("rows" to """{"rows":null}""")))
        assertTrue(invalid { OwnerConfig.parseWallpaper(mapOf("rows" to """[["a"]]""")) }.contains("[label, value]"))
        assertTrue(invalid { OwnerConfig.parseWallpaper(mapOf("rows" to """[["a",1]]""")) }.contains("[label, value]"))
        assertTrue(invalid { OwnerConfig.parseWallpaper(mapOf("rows" to """[["a","b"]""")) }.startsWith("rows:"))
        assertTrue(invalid { OwnerConfig.parseWallpaper(mapOf("rows_b64" to "%%%")) }.startsWith("rows_b64"))
        assertTrue(invalid { OwnerConfig.parseWallpaper(mapOf("rows" to "[]", "rows_b64" to "W10=")) }.startsWith("rows and rows_b64"))
    }

    @Test
    fun fitKeepsBothEnds() {
        assertEquals("short", Wallpaper.fit("short", 8))
        assertEquals("abc…xyz", Wallpaper.fit("abcdefghijklmnopqrstuvwxyz", 7))
    }
}
