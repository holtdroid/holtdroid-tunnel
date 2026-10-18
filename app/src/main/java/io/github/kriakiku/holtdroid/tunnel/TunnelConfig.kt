package io.github.kriakiku.holtdroid.tunnel

data class TunnelConfig(
    val host: String = DEFAULT_HOST,
    val port: Int = DEFAULT_PORT,
    val mtu: Int = DEFAULT_MTU,
    val ipv6: Boolean = true,
    val udp: Boolean = true,
    val dns: List<String> = emptyList(),
    val allow: List<String> = emptyList(),
    val deny: List<String> = emptyList(),
) {
    val dnsServers: List<String>
        get() = dns.ifEmpty { listOfNotNull(DNS4, DNS6.takeIf { ipv6 }) }

    fun toExtras(): Map<String, String> = buildMap {
        put(KEY_HOST, host)
        put(KEY_PORT, port.toString())
        put(KEY_MTU, mtu.toString())
        put(KEY_IPV6, ipv6.toString())
        put(KEY_UDP, udp.toString())
        if (dns.isNotEmpty()) put(KEY_DNS, dns.joinToString(","))
        if (allow.isNotEmpty()) put(KEY_ALLOW, allow.joinToString(","))
        if (deny.isNotEmpty()) put(KEY_DENY, deny.joinToString(","))
    }

    fun toJson(): String = Json.obj(
        KEY_HOST to host,
        KEY_PORT to port,
        KEY_MTU to mtu,
        KEY_IPV6 to ipv6,
        KEY_UDP to udp,
        KEY_DNS to dnsServers,
        KEY_ALLOW to allow,
        KEY_DENY to deny,
    )

    class Invalid(message: String) : IllegalArgumentException(message)

    companion object {
        const val KEY_HOST = "host"
        const val KEY_PORT = "port"
        const val KEY_MTU = "mtu"
        const val KEY_IPV6 = "ipv6"
        const val KEY_UDP = "udp"
        const val KEY_DNS = "dns"
        const val KEY_ALLOW = "allow"
        const val KEY_DENY = "deny"
        val KEYS = setOf(KEY_HOST, KEY_PORT, KEY_MTU, KEY_IPV6, KEY_UDP, KEY_DNS, KEY_ALLOW, KEY_DENY)

        const val DEFAULT_HOST = "127.0.0.1"
        const val DEFAULT_PORT = 1080
        const val DEFAULT_MTU = 9000
        const val INET4 = "172.19.0.1"
        const val INET4_PREFIX = 30
        const val DNS4 = "172.19.0.2"
        const val INET6 = "fdfe:dcba:9876::1"
        const val INET6_PREFIX = 126
        const val DNS6 = "fdfe:dcba:9876::2"

        private val ipv4 = Regex("""(25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)(\.(25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)){3}""")
        private val ipv6Chars = Regex("""[0-9A-Fa-f:.]+""")
        private val packageName = Regex("""[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z][A-Za-z0-9_]*)+""")

        fun parse(extras: Map<String, Any?>, self: String): TunnelConfig {
            val unknown = extras.keys - KEYS
            if (unknown.isNotEmpty()) throw Invalid("unknown extra: ${unknown.sorted().joinToString(", ")}")
            val host = string(extras, KEY_HOST)?.removeSurrounding("[", "]") ?: DEFAULT_HOST
            if (!isIp(host)) throw Invalid("host must be an IP literal: $host")
            val port = int(extras, KEY_PORT) ?: DEFAULT_PORT
            if (port !in 1..65535) throw Invalid("port out of range: $port")
            val mtu = int(extras, KEY_MTU) ?: DEFAULT_MTU
            if (mtu !in 1280..65535) throw Invalid("mtu out of range: $mtu")
            val dns = list(extras, KEY_DNS)
            dns.firstOrNull { !isIp(it) }?.let { throw Invalid("dns must be IP literals: $it") }
            val allow = list(extras, KEY_ALLOW)
            val deny = list(extras, KEY_DENY)
            (allow + deny).firstOrNull { !packageName.matches(it) }?.let { throw Invalid("not a package name: $it") }
            if (allow.isNotEmpty() && deny.isNotEmpty()) throw Invalid("allow and deny cannot be combined")
            if (self in allow) throw Invalid("allow must not include $self")
            return TunnelConfig(
                host = host,
                port = port,
                mtu = mtu,
                ipv6 = bool(extras, KEY_IPV6) ?: true,
                udp = bool(extras, KEY_UDP) ?: true,
                dns = dns,
                allow = allow.distinct(),
                deny = (deny - self).distinct(),
            )
        }

        fun isIp(value: String): Boolean =
            ipv4.matches(value) || (value.contains(':') && ipv6Chars.matches(value) && value.count { it == ':' } in 2..7)

        private fun string(extras: Map<String, Any?>, key: String): String? = when (val v = extras[key]) {
            null -> null
            is String -> v.trim().ifEmpty { null }
            is Number, is Boolean -> v.toString()
            else -> throw Invalid("$key must be a string")
        }

        private fun int(extras: Map<String, Any?>, key: String): Int? = when (val v = extras[key]) {
            null -> null
            is Int -> v
            is Long -> v.toInt().takeIf { it.toLong() == v } ?: throw Invalid("$key out of range: $v")
            is String -> v.trim().toIntOrNull() ?: throw Invalid("$key must be an integer: $v")
            else -> throw Invalid("$key must be an integer")
        }

        private fun bool(extras: Map<String, Any?>, key: String): Boolean? = when (val v = extras[key]) {
            null -> null
            is Boolean -> v
            is String -> when (v.trim().lowercase()) {
                "true", "1", "yes", "on" -> true
                "false", "0", "no", "off" -> false
                else -> throw Invalid("$key must be a boolean: $v")
            }
            else -> throw Invalid("$key must be a boolean")
        }

        private fun list(extras: Map<String, Any?>, key: String): List<String> {
            val items = when (val v = extras[key]) {
                null -> return emptyList()
                is String -> v.split(',', ' ', ';')
                is Array<*> -> v.map { it?.toString() ?: "" }
                is Collection<*> -> v.map { it?.toString() ?: "" }
                else -> throw Invalid("$key must be a list")
            }
            return items.map { it.trim() }.filter { it.isNotEmpty() }
        }
    }
}
