package io.github.kriakiku.holtdroid.tunnel

import android.content.Context

object Store {
    private const val NAME = "tunnel"

    fun save(context: Context, config: TunnelConfig) {
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit().apply {
            clear()
            config.toExtras().forEach { (k, v) -> putString(k, v) }
        }.apply()
    }

    fun loadOrNull(context: Context): TunnelConfig? {
        val values = context.getSharedPreferences(NAME, Context.MODE_PRIVATE).all
        if (values.isEmpty()) return null
        return runCatching { TunnelConfig.parse(values, context.packageName) }.getOrNull()
    }

    fun load(context: Context): TunnelConfig = loadOrNull(context) ?: TunnelConfig()
}
