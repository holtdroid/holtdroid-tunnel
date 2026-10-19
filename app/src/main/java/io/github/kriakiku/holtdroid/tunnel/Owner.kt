package io.github.kriakiku.holtdroid.tunnel

import android.app.ActivityManager
import android.app.ActivityOptions
import android.app.admin.DevicePolicyManager
import android.app.admin.SystemUpdatePolicy
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import java.security.SecureRandom
import java.util.Base64

class Owner(context: Context) {
    private val context = context.applicationContext
    private val dpm = context.getSystemService(DevicePolicyManager::class.java)
    private val admin = ComponentName(context, AdminReceiver::class.java)
    private val prefs = context.createDeviceProtectedStorageContext().getSharedPreferences("owner", Context.MODE_PRIVATE)
    private val packageName = context.packageName

    val isOwner: Boolean get() = dpm.isDeviceOwnerApp(packageName)

    fun requireOwner() {
        if (!isOwner) throw IllegalStateException("not the device owner: run `dpm set-device-owner $packageName/.AdminReceiver`")
    }

    fun lockdown(config: OwnerConfig.Lockdown): List<String> {
        requireOwner()
        val notes = mutableListOf<String>()
        val wanted = config.restrictionsFor(Build.VERSION.SDK_INT).toSet()
        val managed = OwnerConfig.RESTRICTIONS.map { it.key }
        val active = dpm.getUserRestrictions(admin)
        managed.filter { it !in wanted && active.getBoolean(it) }.forEach { dpm.clearUserRestriction(admin, it) }
        wanted.forEach { dpm.addUserRestriction(admin, it) }

        val previous = prefs.getStringSet(KEY_HIDDEN, emptySet()).orEmpty()
        (previous - config.hide.toSet()).forEach { setHidden(it, false) }
        val hidden = config.hide.filter { setHidden(it, true) }
        prefs.edit().putStringSet(KEY_HIDDEN, hidden.toSet()).apply()

        dpm.setCameraDisabled(admin, config.camera)
        dpm.setSystemUpdatePolicy(admin, if (config.updates == OwnerConfig.Updates.POSTPONE) SystemUpdatePolicy.createPostponeInstallPolicy() else null)

        ensureToken()?.let { notes += it }
        if (!dpm.setKeyguardDisabled(admin, config.keyguard) && config.keyguard) {
            notes += "keyguard stays on while a lock screen password is set; run CLEAR_PASSWORD"
        }

        when (config.vpn) {
            OwnerConfig.Vpn.LOCKDOWN -> dpm.setAlwaysOnVpnPackage(admin, packageName, true)
            OwnerConfig.Vpn.ON -> dpm.setAlwaysOnVpnPackage(admin, packageName, false)
            OwnerConfig.Vpn.OFF -> if (dpm.getAlwaysOnVpnPackage(admin) == packageName) dpm.setAlwaysOnVpnPackage(admin, null, false)
        }

        saveLockdown(config)
        Log.i(Tunnel.TAG, "lockdown: ${config.toJson()}")
        return notes
    }

    fun reassert() {
        if (!isOwner) return
        runCatching { lockdown(loadLockdown() ?: OwnerConfig.Lockdown()) }
            .onFailure { Log.e(Tunnel.TAG, "reassert lockdown", it) }
    }

    fun release(): List<String> {
        requireOwner()
        val notes = mutableListOf<String>()
        fun step(name: String, block: () -> Unit) {
            runCatching(block).onFailure { notes += "$name: ${it.message}" }
        }
        step("lock task") { dpm.setLockTaskPackages(admin, emptyArray()) }
        step("restrictions") {
            val active = dpm.getUserRestrictions(admin)
            active.keySet().filter { active.getBoolean(it) }.forEach { dpm.clearUserRestriction(admin, it) }
        }
        prefs.getStringSet(KEY_HIDDEN, emptySet()).orEmpty().forEach { name -> step("unhide $name") { dpm.setApplicationHidden(admin, name, false) } }
        step("camera") { dpm.setCameraDisabled(admin, false) }
        step("system updates") { dpm.setSystemUpdatePolicy(admin, null) }
        step("keyguard") { dpm.setKeyguardDisabled(admin, false) }
        step("always-on vpn") { if (dpm.getAlwaysOnVpnPackage(admin) == packageName) dpm.setAlwaysOnVpnPackage(admin, null, false) }
        step("password token") { dpm.clearResetPasswordToken(admin) }
        prefs.edit().clear().apply()
        dpm.clearDeviceOwnerApp(packageName)
        Log.i(Tunnel.TAG, "released device owner ${notes.joinToString("; ")}")
        return notes
    }

    fun clearPassword(): Boolean {
        requireOwner()
        val token = token() ?: throw IllegalStateException("no reset password token registered; run LOCKDOWN first")
        if (!dpm.isResetPasswordTokenActive(admin)) {
            throw IllegalStateException("the reset password token is not active yet: unlock the phone once with its current PIN, then retry")
        }
        val done = dpm.resetPasswordWithToken(admin, null, token, 0)
        if (done) dpm.setKeyguardDisabled(admin, loadLockdown()?.keyguard ?: true)
        return done
    }

    fun startSession(session: OwnerConfig.Session): List<String> {
        requireOwner()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) throw IllegalStateException("lock task sessions need Android 9 or newer")
        val pm = context.packageManager
        val launch = pm.getLaunchIntentForPackage(session.target)
            ?: throw IllegalArgumentException("${session.target} is not installed or has no launcher activity")
        val launcher = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .map { it.activityInfo.packageName }
        val installed = pm.getInstalledApplications(0).map { it.packageName }.toSet()
        val home = pm.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY)
            ?.activityInfo?.packageName?.takeIf { it != "android" }
        val allowlist = session.allowlist(launcher, installed, home, packageName)
        dpm.setLockTaskPackages(admin, allowlist.toTypedArray())
        dpm.setLockTaskFeatures(admin, session.featureFlags)
        val options = ActivityOptions.makeBasic().setLockTaskEnabled(true)
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        context.startActivity(launch, options.toBundle())
        prefs.edit().putString(KEY_SESSION, session.target).apply()
        Log.i(Tunnel.TAG, "session ${session.target}: ${allowlist.size} apps, features ${session.features}")
        return allowlist
    }

    fun endSession() {
        if (!isOwner) return
        dpm.setLockTaskPackages(admin, emptyArray())
        prefs.edit().remove(KEY_SESSION).apply()
    }

    fun statusJson(): String {
        val owner = isOwner
        val am = context.getSystemService(ActivityManager::class.java)
        val lockTask = when (am.lockTaskModeState) {
            ActivityManager.LOCK_TASK_MODE_LOCKED -> "locked"
            ActivityManager.LOCK_TASK_MODE_PINNED -> "pinned"
            else -> "none"
        }
        if (!owner) return Json.obj("device_owner" to false, "lock_task" to lockTask)
        val active = dpm.getUserRestrictions(admin)
        val features = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) dpm.getLockTaskFeatures(admin) else 0
        return Json.obj(
            "device_owner" to true,
            "restrictions" to active.keySet().filter { active.getBoolean(it) }.sorted(),
            "hidden" to prefs.getStringSet(KEY_HIDDEN, emptySet()).orEmpty().filter { runCatching { dpm.isApplicationHidden(admin, it) }.getOrDefault(false) }.sorted(),
            "camera_disabled" to dpm.getCameraDisabled(admin),
            "keyguard_disabled" to (loadLockdown()?.keyguard == true && !passwordSet()),
            "password_set" to passwordSet(),
            "password_token_active" to (token() != null && dpm.isResetPasswordTokenActive(admin)),
            "always_on_vpn" to dpm.getAlwaysOnVpnPackage(admin),
            "vpn_lockdown" to (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && dpm.isAlwaysOnVpnLockdownEnabled(admin)),
            "system_update_policy" to when (dpm.systemUpdatePolicy?.policyType) {
                SystemUpdatePolicy.TYPE_POSTPONE -> "postpone"
                SystemUpdatePolicy.TYPE_INSTALL_AUTOMATIC -> "automatic"
                SystemUpdatePolicy.TYPE_INSTALL_WINDOWED -> "windowed"
                else -> null
            },
            "lock_task" to lockTask,
            "session" to prefs.getString(KEY_SESSION, null),
            "lock_task_packages" to dpm.getLockTaskPackages(admin).sorted(),
            "lock_task_features" to OwnerConfig.FEATURES.filter { features and it.value != 0 }.keys.toList(),
        )
    }

    fun loadLockdown(): OwnerConfig.Lockdown? {
        val raw = prefs.getString(KEY_LOCKDOWN, null) ?: return null
        return runCatching {
            OwnerConfig.parseLockdown(raw.split('\n').filter { it.isNotEmpty() }.associate { it.substringBefore('=') to it.substringAfter('=') })
        }.getOrNull()
    }

    private fun saveLockdown(config: OwnerConfig.Lockdown) {
        prefs.edit().putString(KEY_LOCKDOWN, config.toExtras().entries.joinToString("\n") { "${it.key}=${it.value}" }).apply()
    }

    private fun passwordSet(): Boolean = context.getSystemService(android.app.KeyguardManager::class.java).isDeviceSecure

    private fun setHidden(name: String, hidden: Boolean): Boolean = try {
        dpm.setApplicationHidden(admin, name, hidden)
    } catch (e: Exception) {
        Log.w(Tunnel.TAG, "hide $name: ${e.message}")
        false
    }

    private fun token(): ByteArray? = prefs.getString(KEY_TOKEN, null)?.let { Base64.getDecoder().decode(it) }

    private fun ensureToken(): String? {
        if (token() != null && runCatching { dpm.isResetPasswordTokenActive(admin) }.getOrDefault(false)) return null
        val fresh = ByteArray(32).also { SecureRandom().nextBytes(it) }
        return try {
            if (dpm.setResetPasswordToken(admin, fresh)) {
                prefs.edit().putString(KEY_TOKEN, Base64.getEncoder().encodeToString(fresh)).apply()
                if (dpm.isResetPasswordTokenActive(admin)) null else "reset password token registered; it becomes active after the next unlock with the current PIN"
            } else {
                "reset password token was refused"
            }
        } catch (e: SecurityException) {
            "reset password token: ${e.message}"
        }
    }

    companion object {
        private const val KEY_HIDDEN = "hidden"
        private const val KEY_TOKEN = "token"
        private const val KEY_LOCKDOWN = "lockdown"
        private const val KEY_SESSION = "session"
    }
}
