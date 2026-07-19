package com.suishouban.app.data.repository

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MascotPreferencesTest {
    @Test
    fun defaultsKeepMascotOverlayDisabledAtTheRightEdge() {
        val repository = AppSettingsRepository(InMemorySharedPreferences())

        val settings = repository.settings.value

        assertFalse(settings.mascotOverlayEnabled)
        assertEquals(0L, settings.mascotHiddenUntilMillis)
        assertEquals("right", settings.mascotDockSide)
        assertEquals(0.5f, settings.mascotVerticalFraction)
        assertFalse(settings.reduceMascotMotion)
    }

    @Test
    fun mascotPreferencesRoundTripThroughSharedPreferences() {
        val preferences = InMemorySharedPreferences()
        val repository = AppSettingsRepository(preferences)
        val saved = repository.settings.value.copy(
            mascotOverlayEnabled = true,
            mascotHiddenUntilMillis = 1_750_000_000_000L,
            mascotDockSide = "left",
            mascotVerticalFraction = 0.72f,
            reduceMascotMotion = true,
        )

        repository.update(saved)

        val reloaded = AppSettingsRepository(preferences).settings.value
        assertTrue(reloaded.mascotOverlayEnabled)
        assertEquals(1_750_000_000_000L, reloaded.mascotHiddenUntilMillis)
        assertEquals("left", reloaded.mascotDockSide)
        assertEquals(0.72f, reloaded.mascotVerticalFraction)
        assertTrue(reloaded.reduceMascotMotion)
    }

    @Test
    fun verticalPositionIsClampedWhenSavingAndLoading() {
        val preferences = InMemorySharedPreferences()
        val repository = AppSettingsRepository(preferences)

        repository.update(repository.settings.value.copy(mascotVerticalFraction = 1.4f))
        assertEquals(0.9f, repository.settings.value.mascotVerticalFraction)

        preferences.edit().putFloat("mascot_vertical_fraction", -2f).commit()
        val reloaded = AppSettingsRepository(preferences).settings.value
        assertEquals(0.1f, reloaded.mascotVerticalFraction)
    }

    @Test
    fun invalidDockSideFallsBackToRightWhenLoadingPreferences() {
        val preferences = InMemorySharedPreferences()
        preferences.edit().putString("mascot_dock_side", "top").commit()

        val settings = AppSettingsRepository(preferences).settings.value

        assertEquals("right", settings.mascotDockSide)
    }
}

/** Minimal in-memory implementation used to exercise the real repository serialization. */
private class InMemorySharedPreferences : SharedPreferences {
    private val values = mutableMapOf<String, Any?>()

    override fun getAll(): MutableMap<String, *> = values.toMutableMap()
    override fun getString(key: String, defValue: String?): String? = values[key] as? String ?: defValue
    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? =
        (values[key] as? Set<String>)?.toMutableSet() ?: defValues
    override fun getInt(key: String, defValue: Int): Int = values[key] as? Int ?: defValue
    override fun getLong(key: String, defValue: Long): Long = values[key] as? Long ?: defValue
    override fun getFloat(key: String, defValue: Float): Float = values[key] as? Float ?: defValue
    override fun getBoolean(key: String, defValue: Boolean): Boolean = values[key] as? Boolean ?: defValue
    override fun contains(key: String): Boolean = values.containsKey(key)
    override fun edit(): SharedPreferences.Editor = Editor(values)
    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) = Unit
    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) = Unit

    private class Editor(private val values: MutableMap<String, Any?>) : SharedPreferences.Editor {
        private val changes = mutableMapOf<String, Any?>()
        private var clearRequested = false

        override fun putString(key: String, value: String?): SharedPreferences.Editor = apply { changes[key] = value }
        override fun putStringSet(key: String, values: MutableSet<String>?): SharedPreferences.Editor =
            apply { changes[key] = values?.toSet() }
        override fun putInt(key: String, value: Int): SharedPreferences.Editor = apply { changes[key] = value }
        override fun putLong(key: String, value: Long): SharedPreferences.Editor = apply { changes[key] = value }
        override fun putFloat(key: String, value: Float): SharedPreferences.Editor = apply { changes[key] = value }
        override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor = apply { changes[key] = value }
        override fun remove(key: String): SharedPreferences.Editor = apply { changes[key] = Removed }
        override fun clear(): SharedPreferences.Editor = apply { clearRequested = true }
        override fun commit(): Boolean = applyChanges()
        override fun apply() {
            applyChanges()
        }

        private fun applyChanges(): Boolean {
            if (clearRequested) values.clear()
            changes.forEach { (key, value) ->
                if (value === Removed) values.remove(key) else values[key] = value
            }
            return true
        }

        private object Removed
    }
}
