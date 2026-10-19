package io.github.kriakiku.holtdroid.tunnel

class Control(private val platform: Platform, private val timeoutMs: Long = 8000, private val owner: OwnerControl? = null) {
    interface Platform {
        val packageName: String
        fun hasConsent(): Boolean
        fun start(config: TunnelConfig)
        fun stop()
        fun lastConfig(): TunnelConfig?
        fun probe(config: TunnelConfig): String
        fun ownerJson(): String? = null
    }

    data class Reply(val code: Int, val json: String)

    fun handle(action: String?, extras: Map<String, Any?>): Reply = when (action) {
        ACTION_START -> start(extras)
        ACTION_STOP -> stop()
        ACTION_STATUS -> status()
        else -> owner?.handle(action, extras) ?: Reply(RESULT_ERROR, Status(error = "unknown action: $action").toJson())
    }

    private fun start(extras: Map<String, Any?>): Reply {
        val config = try {
            TunnelConfig.parse(extras, platform.packageName)
        } catch (e: TunnelConfig.Invalid) {
            return Reply(RESULT_ERROR, Tunnel.snapshot().copy(error = e.message).toJson())
        }
        if (!platform.hasConsent()) {
            return Reply(RESULT_CONSENT, Tunnel.snapshot().copy(consent = false, error = CONSENT_MISSING).toJson())
        }
        Tunnel.update(Status(State.STARTING, config))
        try {
            platform.start(config)
        } catch (e: Exception) {
            Tunnel.update(Status(State.FAILED, config, error = e.message ?: e.javaClass.simpleName))
            return Reply(RESULT_ERROR, Tunnel.snapshot().toJson())
        }
        val settled = Tunnel.await(timeoutMs) { it.state != State.STARTING }
        val ok = settled.state == State.RUNNING
        return Reply(if (ok) RESULT_OK else RESULT_ERROR, settled.withProxy(config).toJson())
    }

    private fun stop(): Reply {
        if (Tunnel.status.state != State.STOPPED) {
            platform.stop()
            Tunnel.await(timeoutMs) { it.state == State.STOPPED }
        }
        val settled = Tunnel.snapshot()
        return Reply(if (settled.state == State.STOPPED) RESULT_OK else RESULT_ERROR, settled.copy(consent = platform.hasConsent()).toJson())
    }

    private fun status(): Reply {
        val current = Tunnel.snapshot()
        val config = current.config ?: platform.lastConfig()
        val reply = config?.let { current.withProxy(it) } ?: current.copy(consent = platform.hasConsent())
        return Reply(RESULT_OK, reply.copy(owner = platform.ownerJson()).toJson())
    }

    private fun Status.withProxy(config: TunnelConfig) = copy(consent = platform.hasConsent(), proxy = platform.probe(config))

    companion object {
        const val PACKAGE = "io.github.kriakiku.holtdroid.tunnel"
        const val ACTION_START = "$PACKAGE.START"
        const val ACTION_STOP = "$PACKAGE.STOP"
        const val ACTION_STATUS = "$PACKAGE.STATUS"
        const val RESULT_OK = -1
        const val RESULT_ERROR = 1
        const val RESULT_CONSENT = 2
        const val CONSENT_MISSING = "vpn consent missing: run `appops set $PACKAGE ACTIVATE_VPN allow` or start $PACKAGE/.ConsentActivity"
    }
}
