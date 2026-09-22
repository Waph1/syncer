package io.github.waph1.syncer.util

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import io.github.waph1.syncer.settings.AppSettings
import io.github.waph1.syncer.settings.SyncType
import java.security.MessageDigest

object AppInfo {

    /** SHA-1 of the signing certificate, formatted as Google Cloud Console expects (AA:BB:...). */
    fun signingSha1(context: Context): String? = runCatching {
        val pm = context.packageManager
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                .signingInfo?.apkContentsSigners
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES).signatures
        }
        val cert = signatures?.firstOrNull()?.toByteArray() ?: return null
        MessageDigest.getInstance("SHA-1").digest(cert).joinToString(":") { "%02X".format(it) }
    }.getOrNull()

    fun isIgnoringBatteryOptimizations(context: Context): Boolean =
        context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)

    fun batteryOptimizationSettings(): Intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
}

object Permissions {
    /** Runtime permissions needed by the enabled data types (plus notifications). */
    fun required(settings: AppSettings): List<String> = buildList {
        if (settings.calendar.enabled) add(Manifest.permission.READ_CALENDAR)
        if (settings.contacts.enabled) add(Manifest.permission.READ_CONTACTS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
    }

    fun forType(type: SyncType): String? = when (type) {
        SyncType.CALENDAR -> Manifest.permission.READ_CALENDAR
        SyncType.CONTACTS -> Manifest.permission.READ_CONTACTS
        else -> null
    }

    fun granted(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    fun missing(context: Context, settings: AppSettings): List<String> =
        required(settings).filterNot { granted(context, it) }
}
