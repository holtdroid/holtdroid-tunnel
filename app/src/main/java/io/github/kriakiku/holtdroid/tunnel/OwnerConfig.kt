package io.github.kriakiku.holtdroid.tunnel

object OwnerConfig {
    class Invalid(message: String) : IllegalArgumentException(message)

    data class Restriction(val key: String, val minSdk: Int)

    val RESTRICTIONS = listOf(
        Restriction("no_modify_accounts", 18),
        Restriction("no_factory_reset", 21),
        Restriction("no_safe_boot", 23),
        Restriction("no_network_reset", 23),
        Restriction("no_add_user", 18),
        Restriction("no_remove_user", 18),
        Restriction("no_add_managed_profile", 26),
        Restriction("no_outgoing_calls", 21),
        Restriction("no_sms", 21),
        Restriction("no_config_bluetooth", 18),
        Restriction("no_config_tethering", 21),
        Restriction("no_config_vpn", 21),
        Restriction("disallow_config_private_dns", 29),
        Restriction("no_usb_file_transfer", 21),
        Restriction("no_physical_media", 21),
        Restriction("disallow_microphone_toggle", 31),
        Restriction("disallow_camera_toggle", 31),
        Restriction("no_config_wifi", 18),
        Restriction("no_config_mobile_networks", 21),
        Restriction("no_bluetooth", 26),
        Restriction("no_install_unknown_sources_globally", 29),
    )

    val FORBIDDEN = mapOf(
        "no_debugging_features" to "turns adb off",
    )

    val UPDATERS = listOf(
        "com.wssyncmldm",
        "com.sec.android.soagent",
        "com.samsung.android.app.updatecenter",
    )

    val MESSAGES = listOf(
        "com.samsung.android.messaging",
        "com.google.android.apps.messaging",
        "com.android.messaging",
    )

    val DEFAULT_HIDDEN = UPDATERS + MESSAGES

    val PROTECTED = setOf(
        "com.android.phone",
        "com.android.server.telecom",
        "com.android.emergency",
        "com.google.android.apps.safetyhub",
        "com.sec.android.emergencylauncher",
        "com.samsung.android.emergency",
        "com.android.dialer",
        "com.google.android.dialer",
        "com.samsung.android.dialer",
        "com.samsung.android.incallui",
        "com.android.permissioncontroller",
        "com.google.android.permissioncontroller",
    )

    val LOCK_TASK_DENY = setOf(
        "com.android.settings",
        "com.android.contacts",
        "com.samsung.android.app.contacts",
        "com.google.android.contacts",
        "com.android.vending",
        "com.sec.android.app.samsungapps",
        "com.osp.app.signin",
        "com.google.android.gms",
        "com.sec.android.easyMover",
        "com.sec.android.app.camera",
        "com.android.camera2",
        "com.google.android.GoogleCamera",
    ) + DEFAULT_HIDDEN

    val FEATURES = linkedMapOf(
        "system_info" to 1,
        "notifications" to 2,
        "home" to 4,
        "overview" to 8,
        "global_actions" to 16,
        "keyguard" to 32,
    )

    val DEFAULT_FEATURES = listOf("system_info", "notifications", "home", "overview", "global_actions")

    enum class Vpn { LOCKDOWN, ON, OFF }
    enum class Updates { POSTPONE, NONE }

    data class Lockdown(
        val restrictions: List<String> = RESTRICTIONS.map { it.key },
        val hide: List<String> = DEFAULT_HIDDEN,
        val vpn: Vpn = Vpn.LOCKDOWN,
        val updates: Updates = Updates.POSTPONE,
        val camera: Boolean = true,
        val keyguard: Boolean = true,
    ) {
        fun restrictionsFor(sdk: Int): List<String> {
            val minSdk = RESTRICTIONS.associate { it.key to it.minSdk }
            return restrictions.filter { (minSdk[it] ?: 0) <= sdk }
        }

        fun toExtras(): Map<String, String> = mapOf(
            "restrictions" to restrictions.joinToString(","),
            "hide" to hide.joinToString(","),
            "vpn" to vpn.name.lowercase(),
            "updates" to updates.name.lowercase(),
            "camera" to camera.toString(),
            "keyguard" to keyguard.toString(),
        )

        fun toJson(): String = Json.obj(
            "restrictions" to restrictions,
            "hide" to hide,
            "vpn" to vpn,
            "updates" to updates,
            "camera" to camera,
            "keyguard" to keyguard,
        )
    }

    data class Session(
        val target: String,
        val allow: List<String> = emptyList(),
        val deny: List<String> = emptyList(),
        val features: List<String> = DEFAULT_FEATURES,
    ) {
        val featureFlags: Int get() = features.sumOf { FEATURES.getValue(it) }

        fun allowlist(launcher: Collection<String>, installed: Set<String>, home: String?, self: String): List<String> {
            val result = linkedSetOf<String>()
            result += launcher
            result -= LOCK_TASK_DENY
            result += allow
            result -= deny.toSet()
            result += PROTECTED.filter { it in installed }
            result += listOfNotNull(target, home, self)
            return result.toList()
        }
    }

    private val known = RESTRICTIONS.map { it.key }.toSet()

    fun parseLockdown(extras: Map<String, Any?>): Lockdown {
        Extras.requireOnly(extras, setOf("restrictions", "skip", "hide", "vpn", "updates", "camera", "keyguard"))
        val chosen = Extras.listOrNull(extras, "restrictions")
        val skip = Extras.list(extras, "skip")
        if (chosen != null && skip.isNotEmpty()) throw Invalid("restrictions and skip cannot be combined")
        (chosen.orEmpty() + skip).forEach { key ->
            FORBIDDEN[key]?.let { throw Invalid("restriction $key is not allowed: $it") }
            if (key !in known) throw Invalid("unknown restriction: $key")
        }
        val restrictions = (chosen ?: RESTRICTIONS.map { it.key }) - skip.toSet()
        val hide = Extras.listOrNull(extras, "hide") ?: DEFAULT_HIDDEN
        hide.forEach { Extras.requirePackage(it) }
        hide.firstOrNull { it in PROTECTED }?.let { throw Invalid("$it is needed for emergency calls and cannot be hidden") }
        return Lockdown(
            restrictions = restrictions.distinct(),
            hide = hide.distinct(),
            vpn = Extras.enum(extras, "vpn", Vpn.LOCKDOWN),
            updates = Extras.enum(extras, "updates", Updates.POSTPONE),
            camera = Extras.bool(extras, "camera") ?: true,
            keyguard = Extras.bool(extras, "keyguard") ?: true,
        )
    }

    fun parseSession(extras: Map<String, Any?>): Session {
        Extras.requireOnly(extras, setOf("package", "allow", "deny", "features"))
        val target = Extras.string(extras, "package") ?: throw Invalid("package is required")
        Extras.requirePackage(target)
        val allow = Extras.list(extras, "allow").onEach { Extras.requirePackage(it) }
        val deny = Extras.list(extras, "deny").onEach { Extras.requirePackage(it) }
        if (target in deny) throw Invalid("deny must not include the session package $target")
        deny.firstOrNull { it in PROTECTED }?.let { throw Invalid("$it is needed for emergency calls and cannot be denied") }
        val features = Extras.listOrNull(extras, "features")?.map { it.lowercase() } ?: DEFAULT_FEATURES
        features.firstOrNull { it !in FEATURES && it != "none" }?.let { throw Invalid("unknown lock task feature: $it") }
        val flags = features.filter { it != "none" }.distinct()
        if ("overview" in flags && "home" !in flags) throw Invalid("overview needs home")
        return Session(target, allow.distinct(), deny.distinct(), flags)
    }

    fun parseWallpaper(extras: Map<String, Any?>): List<Pair<String, String>> {
        Extras.requireOnly(extras, setOf("rows", "rows_b64"))
        val raw = Extras.string(extras, "rows")
        val encoded = Extras.string(extras, "rows_b64")
        if (raw != null && encoded != null) throw Invalid("rows and rows_b64 cannot be combined")
        val json = raw ?: encoded?.let {
            try {
                String(java.util.Base64.getDecoder().decode(it), Charsets.UTF_8)
            } catch (_: IllegalArgumentException) {
                throw Invalid("rows_b64 is not base64")
            }
        } ?: return emptyList()
        return Rows.parse(json)
    }
}

object Extras {
    private val packageName = Regex("""[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z][A-Za-z0-9_]*)+""")

    fun requireOnly(extras: Map<String, Any?>, keys: Set<String>) {
        val unknown = extras.keys - keys
        if (unknown.isNotEmpty()) throw OwnerConfig.Invalid("unknown extra: ${unknown.sorted().joinToString(", ")}")
    }

    fun requirePackage(name: String) {
        if (!packageName.matches(name)) throw OwnerConfig.Invalid("not a package name: $name")
    }

    fun string(extras: Map<String, Any?>, key: String): String? = when (val v = extras[key]) {
        null -> null
        is String -> v.trim().ifEmpty { null }
        else -> throw OwnerConfig.Invalid("$key must be a string")
    }

    fun bool(extras: Map<String, Any?>, key: String): Boolean? = when (val v = extras[key]) {
        null -> null
        is Boolean -> v
        is String -> when (v.trim().lowercase()) {
            "true", "1", "yes", "on" -> true
            "false", "0", "no", "off" -> false
            else -> throw OwnerConfig.Invalid("$key must be a boolean: $v")
        }
        else -> throw OwnerConfig.Invalid("$key must be a boolean")
    }

    inline fun <reified E : Enum<E>> enum(extras: Map<String, Any?>, key: String, default: E): E {
        val v = string(extras, key) ?: return default
        return enumValues<E>().firstOrNull { it.name.equals(v, ignoreCase = true) }
            ?: throw OwnerConfig.Invalid("$key must be one of ${enumValues<E>().joinToString("|") { it.name.lowercase() }}: $v")
    }

    fun listOrNull(extras: Map<String, Any?>, key: String): List<String>? {
        val items = when (val v = extras[key]) {
            null -> return null
            is String -> v.split(',', ' ', ';')
            is Array<*> -> v.map { it?.toString() ?: "" }
            is Collection<*> -> v.map { it?.toString() ?: "" }
            else -> throw OwnerConfig.Invalid("$key must be a list")
        }
        return items.map { it.trim() }.filter { it.isNotEmpty() }
    }

    fun list(extras: Map<String, Any?>, key: String): List<String> = listOrNull(extras, key).orEmpty()
}

object Rows {
    fun parse(json: String): List<Pair<String, String>> {
        val value = try {
            Parser(json).document()
        } catch (e: IllegalStateException) {
            throw OwnerConfig.Invalid("rows: ${e.message}")
        }
        val rows = when (value) {
            is List<*> -> value
            is Map<*, *> -> value["rows"] as? List<*> ?: if (value["rows"] == null) emptyList<Any>() else throw OwnerConfig.Invalid("rows: rows must be an array")
            else -> throw OwnerConfig.Invalid("rows: expected an array or {\"rows\": [...]}")
        }
        return rows.map { row ->
            val pair = row as? List<*>
            if (pair == null || pair.size != 2 || pair.any { it !is String }) throw OwnerConfig.Invalid("rows: every row must be [label, value]")
            pair[0] as String to pair[1] as String
        }
    }

    private class Parser(private val s: String) {
        private var i = 0

        fun document(): Any? {
            val v = value()
            space()
            check(i == s.length) { "trailing data at $i" }
            return v
        }

        private fun space() {
            while (i < s.length && s[i].isWhitespace()) i++
        }

        private fun value(): Any? {
            space()
            check(i < s.length) { "unexpected end" }
            return when (s[i]) {
                '[' -> array()
                '{' -> obj()
                '"' -> string()
                else -> literal()
            }
        }

        private fun array(): List<Any?> {
            i++
            val out = mutableListOf<Any?>()
            space()
            if (s.getOrNull(i) == ']') return out.also { i++ }
            while (true) {
                out += value()
                space()
                when (s.getOrNull(i)) {
                    ',' -> i++
                    ']' -> return out.also { i++ }
                    else -> error("expected , or ] at $i")
                }
            }
        }

        private fun obj(): Map<String, Any?> {
            i++
            val out = linkedMapOf<String, Any?>()
            space()
            if (s.getOrNull(i) == '}') return out.also { i++ }
            while (true) {
                space()
                check(s.getOrNull(i) == '"') { "expected a key at $i" }
                val key = string()
                space()
                check(s.getOrNull(i) == ':') { "expected : at $i" }
                i++
                out[key] = value()
                space()
                when (s.getOrNull(i)) {
                    ',' -> i++
                    '}' -> return out.also { i++ }
                    else -> error("expected , or } at $i")
                }
            }
        }

        private fun string(): String {
            i++
            val out = StringBuilder()
            while (true) {
                check(i < s.length) { "unterminated string" }
                when (val c = s[i++]) {
                    '"' -> return out.toString()
                    '\\' -> {
                        check(i < s.length) { "unterminated string" }
                        when (val e = s[i++]) {
                            '"', '\\', '/' -> out.append(e)
                            'b' -> out.append('\b')
                            'f' -> out.append('\u000c')
                            'n' -> out.append('\n')
                            'r' -> out.append('\r')
                            't' -> out.append('\t')
                            'u' -> {
                                check(i + 4 <= s.length) { "bad escape" }
                                out.append(s.substring(i, i + 4).toIntOrNull(16)?.toChar() ?: error("bad escape"))
                                i += 4
                            }
                            else -> error("bad escape \\$e")
                        }
                    }
                    else -> out.append(c)
                }
            }
        }

        private fun literal(): Any? {
            val start = i
            while (i < s.length && s[i] !in ",]} \t\r\n") i++
            return when (val word = s.substring(start, i)) {
                "null" -> null
                "true" -> true
                "false" -> false
                else -> word.toDoubleOrNull() ?: error("unexpected '$word' at $start")
            }
        }
    }
}
