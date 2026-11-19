package com.example.freetv.launcher

import android.app.role.RoleManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.os.Build
import android.provider.Settings
import com.example.freetv.MainActivity
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

enum class LauncherStatus {
    FREE_TV_IS_DEFAULT,
    ANOTHER_LAUNCHER_IS_DEFAULT,
    DEFAULT_NOT_SELECTED,
    STATUS_UNKNOWN,
}

/** An installed activity that can act as the HOME screen. */
data class HomeActivity(val label: String, val packageName: String, val component: String, val isFreeTv: Boolean)

data class LauncherInfo(
    val status: LauncherStatus,
    /** Human-readable explanation of how the status was determined. */
    val detail: String,
    val homeRegistered: Boolean,
    val leanbackRegistered: Boolean,
    /** Current default HOME as reported by the system, when it reports one. */
    val currentDefault: HomeActivity?,
    val homeActivities: List<HomeActivity>,
    /** FreeTV's own HOME component for ADB, e.g. "com.example.freetv/com.example.freetv.MainActivity". */
    val ownComponent: String,
    val canRequestRole: Boolean,
)

/** What the system said about the default HOME (input to [DefaultLauncherManager.classify]). */
data class ResolvedHome(val packageName: String, val className: String)

/**
 * Detects whether FreeTV is the default HOME app using public APIs only
 * (RoleManager on Android 10+, PackageManager.resolveActivity otherwise).
 * It never changes the default itself – the user must choose.
 */
@Singleton
class DefaultLauncherManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val pm: PackageManager get() = context.packageManager
    private val ownPackage: String get() = context.packageName
    val ownComponent: ComponentName get() = ComponentName(context, MainActivity::class.java)

    fun inspect(): LauncherInfo {
        val homeActivities = queryHomeActivities()
        val homeRegistered = homeActivities.any { it.isFreeTv }
        val leanbackRegistered = isLeanbackRegistered()
        val roleHeld = roleHeld()
        val resolved = resolveDefaultHome()
        val status = classify(ownPackage, roleHeld, resolved)
        val currentDefault = resolved?.takeUnless { isChooser(it) }?.let { r ->
            homeActivities.firstOrNull { it.packageName == r.packageName }
                ?: HomeActivity(r.packageName, r.packageName, ComponentName(r.packageName, r.className).flattenToShortString(), r.packageName == ownPackage)
        }
        val detail = when (status) {
            LauncherStatus.FREE_TV_IS_DEFAULT ->
                if (roleHeld == true) "Android reports that FreeTV holds the Home role." else "Android resolves the HOME action to FreeTV."
            LauncherStatus.ANOTHER_LAUNCHER_IS_DEFAULT ->
                "Android resolves the HOME action to ${currentDefault?.label ?: resolved?.packageName}."
            LauncherStatus.DEFAULT_NOT_SELECTED ->
                "Several Home apps are installed and none is set as default; Android would ask which one to use."
            LauncherStatus.STATUS_UNKNOWN ->
                "This TV does not report its default Home app to other apps, so FreeTV cannot tell."
        }
        return LauncherInfo(
            status = status,
            detail = detail,
            homeRegistered = homeRegistered,
            leanbackRegistered = leanbackRegistered,
            currentDefault = currentDefault,
            homeActivities = homeActivities,
            ownComponent = ownComponent.flattenToShortString(),
            canRequestRole = canRequestHomeRole(),
        )
    }

    private fun homeIntent() = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)

    private fun queryHomeActivities(): List<HomeActivity> = try {
        @Suppress("DEPRECATION")
        val infos: List<ResolveInfo> = if (Build.VERSION.SDK_INT >= 33) {
            pm.queryIntentActivities(homeIntent(), PackageManager.ResolveInfoFlags.of(0))
        } else {
            pm.queryIntentActivities(homeIntent(), 0)
        }
        infos.mapNotNull { info ->
            val ai = info.activityInfo ?: return@mapNotNull null
            if (isChooser(ResolvedHome(ai.packageName, ai.name))) return@mapNotNull null
            HomeActivity(
                label = info.loadLabel(pm).toString().ifBlank { ai.packageName },
                packageName = ai.packageName,
                component = ComponentName(ai.packageName, ai.name).flattenToShortString(),
                isFreeTv = ai.packageName == ownPackage,
            )
        }.distinctBy { it.component }
    } catch (_: Exception) {
        emptyList()
    }

    private fun isLeanbackRegistered(): Boolean = try {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER).setPackage(ownPackage)
        @Suppress("DEPRECATION")
        val list = if (Build.VERSION.SDK_INT >= 33) {
            pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
        } else {
            pm.queryIntentActivities(intent, 0)
        }
        list.isNotEmpty()
    } catch (_: Exception) {
        false
    }

    /** True/false when RoleManager can answer; null when the Home role is unavailable (older Android or not supported). */
    private fun roleHeld(): Boolean? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        return try {
            val roleManager = context.getSystemService(RoleManager::class.java) ?: return null
            if (!roleManager.isRoleAvailable(RoleManager.ROLE_HOME)) null else roleManager.isRoleHeld(RoleManager.ROLE_HOME)
        } catch (_: Exception) {
            null
        }
    }

    private fun resolveDefaultHome(): ResolvedHome? = try {
        @Suppress("DEPRECATION")
        val info = if (Build.VERSION.SDK_INT >= 33) {
            pm.resolveActivity(homeIntent(), PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong()))
        } else {
            pm.resolveActivity(homeIntent(), PackageManager.MATCH_DEFAULT_ONLY)
        }
        info?.activityInfo?.let { ResolvedHome(it.packageName, it.name) }
    } catch (_: Exception) {
        null
    }

    fun canRequestHomeRole(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        return try {
            val rm = context.getSystemService(RoleManager::class.java)
            rm != null && rm.isRoleAvailable(RoleManager.ROLE_HOME) && !rm.isRoleHeld(RoleManager.ROLE_HOME)
        } catch (_: Exception) {
            false
        }
    }

    /** System dialog asking the user to make FreeTV the Home app (Android 10+), or null if unsupported. */
    fun createRequestHomeRoleIntent(): Intent? {
        if (!canRequestHomeRole() || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        return try {
            context.getSystemService(RoleManager::class.java)?.createRequestRoleIntent(RoleManager.ROLE_HOME)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Opens the best available system screen for choosing the Home app.
     * Returns the name of the screen opened, or null when none exists (show manual steps).
     */
    fun openHomeSettings(activityContext: Context): String? {
        val candidates = buildList {
            add("Home app settings" to Intent(Settings.ACTION_HOME_SETTINGS))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                add("Default apps settings" to Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))
            }
            add("Settings" to Intent(Settings.ACTION_SETTINGS))
        }
        for ((name, intent) in candidates) {
            if (intent.resolveActivity(pm) == null) continue
            try {
                activityContext.startActivity(intent)
                return name
            } catch (_: ActivityNotFoundException) {
            } catch (_: SecurityException) {
            }
        }
        return null
    }

    companion object {
        /** System choosers that appear when no default HOME is set. */
        fun isChooser(r: ResolvedHome): Boolean =
            r.packageName == "android" || r.className.contains("ResolverActivity") || r.className.contains("ChooserActivity")

        /**
         * Status rules. The manifest declaration alone never counts as "default":
         * only RoleManager or the system's own HOME resolution can say so.
         */
        fun classify(ownPackage: String, roleHeld: Boolean?, resolved: ResolvedHome?): LauncherStatus = when {
            roleHeld == true -> LauncherStatus.FREE_TV_IS_DEFAULT
            resolved == null -> LauncherStatus.STATUS_UNKNOWN
            isChooser(resolved) -> LauncherStatus.DEFAULT_NOT_SELECTED
            resolved.packageName == ownPackage -> if (roleHeld == false) LauncherStatus.STATUS_UNKNOWN else LauncherStatus.FREE_TV_IS_DEFAULT
            else -> LauncherStatus.ANOTHER_LAUNCHER_IS_DEFAULT
        }
    }
}
