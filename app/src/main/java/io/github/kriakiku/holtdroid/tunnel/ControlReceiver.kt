package io.github.kriakiku.holtdroid.tunnel

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.util.Log

class ControlReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        val pending = goAsync()
        Thread {
            val reply = try {
                Control(AndroidPlatform(app), owner = OwnerControl(AndroidOwner(app))).handle(intent.action, intent.extraMap())
            } catch (e: Exception) {
                Log.e(Tunnel.TAG, "control", e)
                Control.Reply(Control.RESULT_ERROR, Status(error = e.message ?: e.javaClass.simpleName).toJson())
            }
            Log.println(if (reply.code == Control.RESULT_OK) Log.INFO else Log.WARN, Tunnel.TAG, "${intent.action}: ${reply.json}")
            pending.resultCode = reply.code
            pending.resultData = reply.json
            pending.finish()
        }.start()
    }
}

class AndroidPlatform(private val context: Context) : Control.Platform {
    override val packageName: String = context.packageName
    override fun hasConsent() = VpnService.prepare(context) == null
    override fun start(config: TunnelConfig) = TunnelService.start(context, config)
    override fun stop() = TunnelService.stop(context)
    override fun lastConfig() = Store.loadOrNull(context)
    override fun probe(config: TunnelConfig) = SocksProbe.check(config.host, config.port)
    override fun ownerJson(): String? = runCatching { Owner(context).statusJson() }.getOrNull()
}

class AndroidOwner(private val context: Context) : OwnerControl.Platform {
    private val owner = Owner(context)
    override fun isOwner() = owner.isOwner
    override fun lockdown(config: OwnerConfig.Lockdown) = owner.lockdown(config)
    override fun release() = owner.release()
    override fun clearPassword() = owner.clearPassword()
    override fun startSession(session: OwnerConfig.Session) = owner.startSession(session)
    override fun endSession() = owner.endSession()
    override fun wallpaper(rows: List<Pair<String, String>>) = Wallpaper.set(context, rows)
    override fun statusJson() = owner.statusJson()
}
