package com.epaflix.fytorium.platform

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// Mirrors Android: an edit reaches memory before commit() tries the disk, and a failed write is not rolled back.
private class MemoryPrefs(var diskWorks: Boolean) : SharedPreferences {
    val values = mutableMapOf<String, Any?>()
    override fun getAll() = values.toMap()
    override fun getString(key: String, default: String?) = values[key] as String? ?: default
    override fun getStringSet(key: String, default: MutableSet<String>?) = default
    override fun getInt(key: String, default: Int) = default
    override fun getLong(key: String, default: Long) = default
    override fun getFloat(key: String, default: Float) = default
    override fun getBoolean(key: String, default: Boolean) = values[key] as Boolean? ?: default
    override fun contains(key: String) = key in values
    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) = Unit
    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) = Unit
    override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
        val pending = mutableMapOf<String, Any?>()
        override fun putString(key: String, value: String?) = apply { pending[key] = value }
        override fun putStringSet(key: String, value: MutableSet<String>?) = apply { pending[key] = value }
        override fun putInt(key: String, value: Int) = apply { pending[key] = value }
        override fun putLong(key: String, value: Long) = apply { pending[key] = value }
        override fun putFloat(key: String, value: Float) = apply { pending[key] = value }
        override fun putBoolean(key: String, value: Boolean) = apply { pending[key] = value }
        override fun remove(key: String) = apply { pending[key] = null }
        override fun clear() = apply { values.clear() }
        override fun commit(): Boolean { values.putAll(pending); return diskWorks }
        override fun apply() { values.putAll(pending) }
    }
}

private class PrefsContext(val prefs: MemoryPrefs) : ContextWrapper(null) {
    override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = prefs
}

class FirstRunTest {
    @Test fun aMarkerThatNeverReachedDiskDoesNotCountAsDone() {
        val prefs = MemoryPrefs(diskWorks = false)
        val context: Context = PrefsContext(prefs)
        assertFalse(Flags.set(context, Flags.FIRST_RUN_DONE, true))
        assertTrue(Flags.get(context, Flags.FIRST_RUN_DONE))
        assertFalse(FirstRun.run(context))
        prefs.diskWorks = true
        assertTrue(FirstRun.run(context))
    }
}
