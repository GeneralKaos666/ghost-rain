package org.ghostrain

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import androidx.datastore.preferences.SharedPreferencesMigration
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Phase 3 (Task 9) upgrade-path test: seeds a legacy `matrix`
 * SharedPreferences with representative values and runs the REAL
 * `SharedPreferencesMigration` (the same vehicle production declares) into
 * a temp-file DataStore, asserting every key/value survives with its type.
 *
 * No Robolectric/Mockito (not allowed here): [FakeContext] extends
 * [ContextWrapper] with a null base — only `getSharedPreferences`,
 * `getApplicationContext`, `getApplicationInfo` (for the post-migration
 * file cleanup), and `deleteSharedPreferences` are overridden; the real
 * migration never touches anything else — and
 * [FakeSharedPreferences]/[FakeEditor] back the legacy store with a map.
 */
class DataStoreMigrationTest {

    @get:Rule
    val tmpDir = TemporaryFolder()

    @Test fun realMigrationPreservesEveryKeyAndValue() {
        val seed = linkedMapOf<String, Any?>(
            "hud" to true,
            "hudLock" to false,
            "hudX" to 50,
            "hudPosLock" to 25,
            "hudScale" to 100,
            "hudScaleLock" to 150,
            "hudDynamic" to true,
            "hudDynamicLock" to false,
            "el_title" to true,
            "el_ram" to true,
            "el_netLock" to false,
            "redactIp" to true,
            "redactIpLock" to false,
            "title" to "KEEP//HUD",
            "titleLock" to "LOCKED",
            "order" to "net,title,ram,disk,bat,cpu,up",
            "layouts" to """[{"name":"Night","hud":true,"hudX":30,"title":"N"}]""",
            "rainSpeed" to 100,
            "rainHue" to 120,
            "rainFontSize" to 100,
            "glyphKatakana" to true,
            "glyphDigits" to false,
            "glyphLatin" to true,
            "glyphSymbols" to false,
            "rainMinLen" to 6,
            "rainMaxLen" to 32,
            "rainFps" to 30,
            "shimmer" to 60,
            "ui_open_screen" to true,
            "ui_open_hud" to true,
            "ui_open_rain" to false,
            "lastAppliedVersion" to 23,
            "updatePromptDismissed" to -1,
            "someLong" to 42L,
            "someFloat" to 1.5f,
            "someSet" to setOf("a", "b")
        )
        val ctx = FakeContext(
            prefsByName = mapOf("matrix" to FakeSharedPreferences(seed.toMutableMap())),
            dataDir = tmpDir.root
        )
        val file = File(tmpDir.root, "matrix-migration-test.preferences_pb")
        file.delete()
        val store = PreferenceDataStoreFactory.create(
            produceFile = { file },
            migrations = listOf(SharedPreferencesMigration(ctx, "matrix"))
        )
        val data = runBlocking { store.data.first() }
        for ((k, v) in seed) {
            when (v) {
                is Boolean -> assertEquals("bool $k", v, data[booleanPreferencesKey(k)])
                is Int -> assertEquals("int $k", v, data[intPreferencesKey(k)])
                is Long -> assertEquals("long $k", v, data[longPreferencesKey(k)])
                is Float -> assertEquals("float $k", v, data[floatPreferencesKey(k)])
                is String -> assertEquals("string $k", v, data[stringPreferencesKey(k)])
                is Set<*> -> {
                    @Suppress("UNCHECKED_CAST")
                    assertEquals("set $k", v as Set<String>, data[stringSetPreferencesKey(k)])
                }
                else -> fail("unexpected seed type for $k")
            }
        }
        // The migration runs once: a second read is stable and complete.
        val again = runBlocking { store.data.first() }
        assertEquals(seed.size, again.asMap().size)
    }
}

/** Legacy store backed by a plain map; full Editor semantics. */
private class FakeSharedPreferences(
    private val data: MutableMap<String, Any?>
) : SharedPreferences {

    override fun getAll(): Map<String, *> = data.toMap()

    override fun getString(key: String, defValue: String?): String? =
        if (data[key] is String) data[key] as String else defValue

    override fun getStringSet(key: String, defValues: Set<String>?): Set<String>? =
        if (data[key] is Set<*>) {
            (data[key] as Set<*>).filterIsInstance<String>().toSet()
        } else {
            defValues
        }

    override fun getInt(key: String, defValue: Int): Int =
        if (data[key] is Int) data[key] as Int else defValue

    override fun getLong(key: String, defValue: Long): Long =
        if (data[key] is Long) data[key] as Long else defValue

    override fun getFloat(key: String, defValue: Float): Float =
        if (data[key] is Float) data[key] as Float else defValue

    override fun getBoolean(key: String, defValue: Boolean): Boolean =
        if (data[key] is Boolean) data[key] as Boolean else defValue

    override fun contains(key: String): Boolean = data.containsKey(key)

    override fun edit(): SharedPreferences.Editor = FakeEditor(data)

    override fun registerOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?
    ) {
    }

    override fun unregisterOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?
    ) {
    }
}

private class FakeEditor(
    private val data: MutableMap<String, Any?>
) : SharedPreferences.Editor {

    override fun putString(key: String, value: String?): SharedPreferences.Editor {
        if (value == null) data.remove(key) else data[key] = value
        return this
    }

    override fun putStringSet(key: String, values: Set<String>?): SharedPreferences.Editor {
        if (values == null) data.remove(key) else data[key] = values.toSet()
        return this
    }

    override fun putInt(key: String, value: Int): SharedPreferences.Editor {
        data[key] = value
        return this
    }

    override fun putLong(key: String, value: Long): SharedPreferences.Editor {
        data[key] = value
        return this
    }

    override fun putFloat(key: String, value: Float): SharedPreferences.Editor {
        data[key] = value
        return this
    }

    override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor {
        data[key] = value
        return this
    }

    override fun remove(key: String): SharedPreferences.Editor {
        data.remove(key)
        return this
    }

    override fun clear(): SharedPreferences.Editor {
        data.clear()
        return this
    }

    override fun commit(): Boolean = true

    override fun apply() {
    }
}

/**
 * Minimal [Context]: only the members the real migration touches do real
 * work (`getSharedPreferences`, `getApplicationContext`,
 * `getApplicationInfo` for the post-migration file cleanup,
 * `deleteSharedPreferences`); everything else delegates to the null base
 * and is never called.
 */
private class FakeContext(
    private val prefsByName: Map<String, SharedPreferences>,
    private val dataDir: File
) : ContextWrapper(null) {

    override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
        prefsByName[name] ?: throw IllegalArgumentException("unknown prefs $name")

    override fun deleteSharedPreferences(name: String): Boolean = true

    override fun getApplicationContext(): Context = this

    override fun getApplicationInfo(): ApplicationInfo =
        ApplicationInfo().apply { dataDir = this@FakeContext.dataDir.absolutePath }
}
