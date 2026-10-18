package io.github.kriakiku.holtdroid.tunnel

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.util.Log

class ConsentActivity : Activity() {
    private var config: TunnelConfig? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        config = try {
            TunnelConfig.parse(intent.extraMap(), packageName)
        } catch (e: TunnelConfig.Invalid) {
            Log.w(Tunnel.TAG, "consent: ${e.message}")
            Tunnel.update(Status(State.FAILED, error = e.message))
            finish()
            return
        }
        val prompt = VpnService.prepare(this)
        if (prompt == null) {
            launchAndFinish()
        } else if (savedInstanceState == null) {
            @Suppress("DEPRECATION")
            startActivityForResult(prompt, REQUEST)
        }
    }

    @Deprecated("Activity result API needs androidx")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST) return
        if (resultCode == RESULT_OK) {
            launchAndFinish()
        } else {
            Log.w(Tunnel.TAG, "consent: denied")
            Tunnel.update(Status(State.FAILED, config, consent = false, error = "vpn consent denied"))
            finish()
        }
    }

    private fun launchAndFinish() {
        val config = config ?: return finish()
        Log.i(Tunnel.TAG, "consent: granted")
        Tunnel.update(Status(State.STARTING, config))
        TunnelService.start(this, config)
        finish()
    }

    private companion object {
        const val REQUEST = 1
    }
}
