package io.github.kriakiku.holtdroid.tunnel

enum class State { STOPPED, STARTING, RUNNING, FAILED }

data class Traffic(
    val tcpActive: Long = 0,
    val tcpTotal: Long = 0,
    val udpActive: Long = 0,
    val udpTotal: Long = 0,
    val uplink: Long = 0,
    val downlink: Long = 0,
    val failures: Long = 0,
    val lastError: String? = null,
) {
    fun toJson(): String = Json.obj(
        "tcp_active" to tcpActive,
        "tcp_total" to tcpTotal,
        "udp_active" to udpActive,
        "udp_total" to udpTotal,
        "uplink" to uplink,
        "downlink" to downlink,
        "failures" to failures,
        "last_error" to lastError,
    )
}

data class Status(
    val state: State = State.STOPPED,
    val config: TunnelConfig? = null,
    val since: Long? = null,
    val error: String? = null,
    val traffic: Traffic? = null,
    val proxy: String? = null,
    val consent: Boolean? = null,
) {
    fun toJson(): String = Json.obj(
        "state" to state,
        "version" to VERSION,
        "consent" to consent,
        "since" to since,
        "error" to error,
        "proxy" to proxy,
        "config" to config?.let { Json.Raw(it.toJson()) },
        "traffic" to traffic?.let { Json.Raw(it.toJson()) },
    )

    companion object {
        val VERSION: String = BuildConfig.VERSION_NAME
    }
}
