package io.github.kriakiku.holtdroid.tunnel

import android.app.Activity
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.format.Formatter
import android.util.TypedValue
import android.widget.ScrollView
import android.widget.TextView

class MainActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var text: TextView
    private val listener: (Status) -> Unit = { main.post(::render) }
    private val tick = object : Runnable {
        override fun run() {
            render()
            main.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 24f, resources.displayMetrics).toInt()
        text = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            setPadding(pad, pad, pad, pad)
        }
        setContentView(ScrollView(this).apply {
            fitsSystemWindows = true
            addView(text)
        })
    }

    override fun onStart() {
        super.onStart()
        Tunnel.listen(listener)
        main.post(tick)
    }

    override fun onStop() {
        Tunnel.unlisten(listener)
        main.removeCallbacks(tick)
        super.onStop()
    }

    private fun render() {
        val s = Tunnel.snapshot()
        text.text = buildString {
            appendLine(getString(R.string.app_name) + " " + Status.VERSION)
            appendLine()
            appendLine("state   ${s.state.name.lowercase()}")
            s.error?.let { appendLine("error   $it") }
            s.config?.let { c ->
                appendLine("proxy   ${c.host}:${c.port}")
                appendLine("dns     ${c.dnsServers.joinToString(", ")}")
                appendLine("ipv6    ${c.ipv6}   udp ${c.udp}   mtu ${c.mtu}")
                if (c.allow.isNotEmpty()) appendLine("allow   ${c.allow.joinToString(", ")}")
                if (c.deny.isNotEmpty()) appendLine("deny    ${c.deny.joinToString(", ")}")
            }
            s.traffic?.let { t ->
                appendLine()
                appendLine("tcp     ${t.tcpActive} open, ${t.tcpTotal} total")
                appendLine("udp     ${t.udpActive} open, ${t.udpTotal} total")
                appendLine("up      ${Formatter.formatShortFileSize(this@MainActivity, t.uplink)}")
                appendLine("down    ${Formatter.formatShortFileSize(this@MainActivity, t.downlink)}")
                appendLine("failed  ${t.failures}")
                t.lastError?.let { appendLine("last    $it") }
            }
            appendLine()
            append(getString(R.string.hint))
        }
    }
}
