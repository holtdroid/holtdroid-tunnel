package io.github.kriakiku.holtdroid.tunnel

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import io.github.kriakiku.holtdroid.tunnel.engine.Engine
import io.github.kriakiku.holtdroid.tunnel.engine.Logger
import io.github.kriakiku.holtdroid.tunnel.engine.Options
import io.github.kriakiku.holtdroid.tunnel.engine.Session
import java.io.FileDescriptor
import java.io.PrintWriter
import java.util.concurrent.Executors

class TunnelService : VpnService() {
    private val worker = Executors.newSingleThreadExecutor()
    private var session: Session? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == Control.ACTION_STOP) {
            worker.execute {
                shutdown(null)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            return START_NOT_STICKY
        }
        val config = try {
            if (intent?.action == Control.ACTION_START) TunnelConfig.parse(intent.extraMap(), packageName) else Store.load(this)
        } catch (e: TunnelConfig.Invalid) {
            Tunnel.update(Status(State.FAILED, error = e.message))
            stopSelf()
            return START_NOT_STICKY
        }
        promote(config)
        Store.save(this, config)
        worker.execute { launch(config) }
        return START_STICKY
    }

    override fun onRevoke() {
        worker.execute {
            shutdown("revoked by the system or another VPN app")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    override fun onDestroy() {
        worker.execute { shutdown(null) }
        worker.shutdown()
        super.onDestroy()
    }

    override fun dump(fd: FileDescriptor?, writer: PrintWriter, args: Array<out String>?) {
        writer.println(Tunnel.snapshot().toJson())
    }

    private fun launch(config: TunnelConfig) {
        closeSession()
        Tunnel.update(Status(State.STARTING, config))
        try {
            if (prepare(this) != null) throw IllegalStateException(Control.CONSENT_MISSING)
            val fd = establish(config)
            val options = Options().apply {
                this.fd = fd
                mtu = config.mtu
                inet4 = "${TunnelConfig.INET4}/${TunnelConfig.INET4_PREFIX}"
                inet6 = if (config.ipv6) "${TunnelConfig.INET6}/${TunnelConfig.INET6_PREFIX}" else ""
                proxyHost = config.host
                proxyPort = config.port
                udpOverTcp = config.udp
            }
            val started = try {
                Engine.start(options, EngineLog)
            } catch (e: Exception) {
                ParcelFileDescriptor.adoptFd(fd).close()
                throw e
            }
            session = started
            Tunnel.traffic = { started.traffic() }
            watchOwnNetwork()
            Tunnel.update(Status(State.RUNNING, config, since = System.currentTimeMillis()))
            Log.i(Tunnel.TAG, "running: ${config.toJson()}")
        } catch (e: Exception) {
            Log.e(Tunnel.TAG, "start failed", e)
            closeSession()
            Tunnel.update(Status(State.FAILED, config, error = e.message ?: e.javaClass.simpleName))
        }
    }

    private fun establish(config: TunnelConfig): Int {
        val builder = Builder()
            .setSession(getString(R.string.app_name))
            .setMtu(config.mtu)
            .addAddress(TunnelConfig.INET4, TunnelConfig.INET4_PREFIX)
            .addRoute("0.0.0.0", 0)
            .setConfigureIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
        if (config.ipv6) builder.addAddress(TunnelConfig.INET6, TunnelConfig.INET6_PREFIX).addRoute("::", 0)
        config.dnsServers.forEach { builder.addDnsServer(it) }
        if (config.allow.isNotEmpty()) {
            config.allow.forEach {
                if (!installed(it)) throw IllegalArgumentException("allow: package not installed: $it")
                builder.addAllowedApplication(it)
            }
        } else {
            builder.addDisallowedApplication(packageName)
            config.deny.forEach {
                if (installed(it)) builder.addDisallowedApplication(it) else Log.w(Tunnel.TAG, "deny: package not installed: $it")
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) builder.setMetered(false)
        val pfd = builder.establish() ?: throw IllegalStateException("establish returned null: VPN consent was revoked")
        return pfd.detachFd()
    }

    private fun installed(name: String) = try {
        packageManager.getApplicationInfo(name, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    private fun watchOwnNetwork() {
        val connectivity = getSystemService(ConnectivityManager::class.java)
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_VPN)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                setUnderlyingNetworks(arrayOf(network))
            }
        }
        connectivity.registerNetworkCallback(request, callback)
        networkCallback = callback
    }

    private fun closeSession() {
        networkCallback?.let {
            runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(it) }
            networkCallback = null
        }
        Tunnel.traffic = null
        session?.let {
            runCatching { it.close() }.onFailure { e -> Log.w(Tunnel.TAG, "close", e) }
            session = null
        }
    }

    private fun shutdown(error: String?) {
        val current = Tunnel.status
        closeSession()
        if (current.state == State.STOPPED && error == null) return
        Tunnel.update(Status(State.STOPPED, current.config, error = error))
    }

    private fun promote(config: TunnelConfig) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.channel_name), NotificationManager.IMPORTANCE_MIN).apply { setShowBadge(false) },
        )
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.notification_text, config.host, config.port))
            .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
            .setCategory(Notification.CATEGORY_SERVICE)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private object EngineLog : Logger {
        override fun log(level: Int, message: String) {
            Log.println(level, Tunnel.TAG, message)
        }
    }

    companion object {
        private const val CHANNEL = "tunnel"
        private const val NOTIFICATION_ID = 1

        fun start(context: Context, config: TunnelConfig) {
            val intent = Intent(context, TunnelService::class.java).setAction(Control.ACTION_START)
            config.toExtras().forEach { (k, v) -> intent.putExtra(k, v) }
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, TunnelService::class.java).setAction(Control.ACTION_STOP))
        }
    }
}

private fun Session.traffic(): Traffic = stats().let {
    Traffic(it.tcpActive, it.tcpTotal, it.udpActive, it.udpTotal, it.uplink, it.downlink, it.failures, it.lastError.ifEmpty { null })
}

@Suppress("DEPRECATION")
fun Intent.extraMap(): Map<String, Any?> = extras?.let { b -> b.keySet().associateWith { b.get(it) } } ?: emptyMap()
