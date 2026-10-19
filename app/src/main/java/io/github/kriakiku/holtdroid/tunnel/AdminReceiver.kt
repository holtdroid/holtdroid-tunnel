package io.github.kriakiku.holtdroid.tunnel

import android.app.admin.DeviceAdminReceiver
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class AdminReceiver : DeviceAdminReceiver() {
    override fun onEnabled(context: Context, intent: Intent) {
        val owner = Owner(context)
        if (!owner.isOwner) return
        val pending = goAsync()
        Thread {
            runCatching { owner.lockdown(owner.loadLockdown() ?: OwnerConfig.Lockdown()) }
                .onSuccess { notes -> Log.i(Tunnel.TAG, "device owner: lockdown applied ${notes.joinToString("; ")}") }
                .onFailure { Log.e(Tunnel.TAG, "device owner: lockdown failed", it) }
            pending.finish()
        }.start()
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_LOCKED_BOOT_COMPLETED) return
        val pending = goAsync()
        Thread {
            Owner(context).reassert()
            pending.finish()
        }.start()
    }
}
