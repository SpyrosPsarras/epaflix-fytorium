package com.epaflix.fytorium.platform

import android.content.Context
import android.content.Intent
import androidx.work.WorkManager
import java.io.File
import java.security.KeyStore

object FirstRun {
    val legacyPreferences = listOf("credentials", "diagnostics", "notification-permission")
    val legacyWork = listOf("care-cache-refresh", "notification-cache-refresh")

    // Every step treats an already-absent item as done. A failed step leaves first_run_done unset, so the next start retries.
    fun run(context: Context): Boolean {
        // SharedPreferences keeps a failed write in memory, so the marker is committed again before it counts.
        if (Flags.get(context, Flags.FIRST_RUN_DONE)) return Flags.set(context, Flags.FIRST_RUN_DONE, true)
        val asked = context.getSharedPreferences("notification-permission", Context.MODE_PRIVATE).getBoolean("asked", false)
        // The legacy prefs are deleted only after the copied values are on disk.
        val carried = mapOf(Flags.PUSH_RESET_PENDING to true) + (if (asked) mapOf(Flags.NOTIFICATIONS_ASKED to true) else emptyMap())
        if (!Flags.setAll(context, carried)) return false
        val steps = listOf(
            { legacyPreferences.all { context.deleteSharedPreferences(it) } },
            { KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.run { deleteEntry("fytorium.credentials"); !containsAlias("fytorium.credentials") } },
            { context.deleteDatabase("care-cache.db"); !context.getDatabasePath("care-cache.db").exists() },
            { listOf(File(context.cacheDir, "thumbnails"), File(context.cacheDir, "captures"), File(context.noBackupFilesDir, "photo-drafts"))
                .all { it.deleteRecursively() && !it.exists() } },
            { context.contentResolver.persistedUriPermissions.forEach {
                context.contentResolver.releasePersistableUriPermission(it.uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }; context.contentResolver.persistedUriPermissions.isEmpty() },
            { WorkManager.getInstance(context).let { work -> legacyWork.forEach { work.cancelUniqueWork(it).result.get() } }; true },
        )
        val done = steps.map { step -> runCatching(step).getOrDefault(false) }.all { it }
        return done && Flags.set(context, Flags.FIRST_RUN_DONE, true)
    }
}
