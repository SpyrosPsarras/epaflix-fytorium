package com.epaflix.fytorium.platform

import android.content.Context
import android.content.Intent
import androidx.work.WorkManager
import java.io.File
import java.security.KeyStore

object FirstRun {
    val legacyPreferences = listOf("credentials", "diagnostics", "notification-permission")
    val legacyWork = listOf("care-cache-refresh", "notification-cache-refresh")

    fun run(context: Context): Boolean {
        if (Flags.get(context, Flags.FIRST_RUN_DONE)) return false
        Flags.set(context, Flags.PUSH_RESET_PENDING, true)
        if (context.getSharedPreferences("notification-permission", Context.MODE_PRIVATE).getBoolean("asked", false)) {
            Flags.set(context, Flags.NOTIFICATIONS_ASKED, true)
        }
        legacyPreferences.forEach { context.deleteSharedPreferences(it) }
        runCatching { KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry("fytorium.credentials") }
        context.deleteDatabase("care-cache.db")
        File(context.cacheDir, "thumbnails").deleteRecursively()
        File(context.cacheDir, "captures").deleteRecursively()
        File(context.noBackupFilesDir, "photo-drafts").deleteRecursively()
        context.contentResolver.persistedUriPermissions.forEach { permission ->
            runCatching { context.contentResolver.releasePersistableUriPermission(permission.uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        }
        WorkManager.getInstance(context).let { work -> legacyWork.forEach(work::cancelUniqueWork) }
        Flags.set(context, Flags.FIRST_RUN_DONE, true)
        return true
    }
}
