package io.github.kriakiku.holtdroid.tunnel

class OwnerControl(private val platform: Platform) {
    interface Platform {
        fun isOwner(): Boolean
        fun lockdown(config: OwnerConfig.Lockdown): List<String>
        fun release(): List<String>
        fun clearPassword(): Boolean
        fun startSession(session: OwnerConfig.Session): List<String>
        fun endSession()
        fun wallpaper(rows: List<Pair<String, String>>)
        fun statusJson(): String
    }

    fun handle(action: String?, extras: Map<String, Any?>): Control.Reply? = when (action) {
        ACTION_LOCKDOWN -> run(extras, owner = true) { platform.lockdown(OwnerConfig.parseLockdown(it)) }
        ACTION_RELEASE -> run(extras, owner = true, keys = emptySet()) { platform.release() }
        ACTION_CLEAR_PASSWORD -> run(extras, owner = true, keys = emptySet()) {
            if (platform.clearPassword()) emptyList() else throw IllegalStateException("the password was not reset")
        }
        ACTION_SESSION_START -> run(extras, owner = true) {
            val allowlist = platform.startSession(OwnerConfig.parseSession(it))
            listOf("${allowlist.size} packages allowed in lock task")
        }
        ACTION_SESSION_END -> run(extras, owner = true, keys = emptySet()) {
            platform.endSession()
            emptyList()
        }
        ACTION_WALLPAPER -> run(extras, owner = false) {
            platform.wallpaper(OwnerConfig.parseWallpaper(it))
            emptyList()
        }
        else -> null
    }

    private fun run(
        extras: Map<String, Any?>,
        owner: Boolean,
        keys: Set<String>? = null,
        block: (Map<String, Any?>) -> List<String>,
    ): Control.Reply {
        if (keys != null) {
            try {
                Extras.requireOnly(extras, keys)
            } catch (e: OwnerConfig.Invalid) {
                return reply(Control.RESULT_ERROR, e.message, emptyList())
            }
        }
        if (owner && !platform.isOwner()) return reply(RESULT_NOT_OWNER, NOT_OWNER, emptyList())
        return try {
            reply(Control.RESULT_OK, null, block(extras))
        } catch (e: Exception) {
            reply(Control.RESULT_ERROR, e.message ?: e.javaClass.simpleName, emptyList())
        }
    }

    private fun reply(code: Int, error: String?, notes: List<String>) = Control.Reply(
        code,
        Json.obj("error" to error, "notes" to notes, "owner" to Json.Raw(runCatching { platform.statusJson() }.getOrElse { "null" })),
    )

    companion object {
        const val ACTION_LOCKDOWN = "${Control.PACKAGE}.LOCKDOWN"
        const val ACTION_RELEASE = "${Control.PACKAGE}.RELEASE"
        const val ACTION_CLEAR_PASSWORD = "${Control.PACKAGE}.CLEAR_PASSWORD"
        const val ACTION_SESSION_START = "${Control.PACKAGE}.SESSION_START"
        const val ACTION_SESSION_END = "${Control.PACKAGE}.SESSION_END"
        const val ACTION_WALLPAPER = "${Control.PACKAGE}.WALLPAPER"
        const val RESULT_NOT_OWNER = 3
        const val NOT_OWNER = "not the device owner: run `dpm set-device-owner ${Control.PACKAGE}/.AdminReceiver`"
    }
}
