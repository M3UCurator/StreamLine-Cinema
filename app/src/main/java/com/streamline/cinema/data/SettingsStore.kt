package com.streamline.cinema.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.util.Locale

class SettingsStore(context: Context) {

    private val prefs = context.getSharedPreferences("streamline_settings", Context.MODE_PRIVATE)
    private val lock = Any()
    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    init {
        CinemaDns.configure(_settings.value)
    }

    private fun load(): AppSettings {
        val raw = prefs.getString(KEY_SETTINGS, null)
        val base = if (raw.isNullOrBlank()) {
            AppSettings()
        } else {
            runCatching { AppSettings.fromJson(JSONObject(raw)) }
                .getOrDefault(AppSettings())
        }
        val includeAdult = prefs.getBoolean("tmdb_include_adult", base.includeAdult)
        return base.copy(includeAdult = includeAdult).normalized()
    }

    fun update(transform: (AppSettings) -> AppSettings): AppSettings {
        synchronized(lock) {
            val next = transform(_settings.value).normalized()
            _settings.value = next
            CinemaDns.configure(next)
            persist(next)
            return next
        }
    }

    private fun persist(settings: AppSettings) {
        prefs.edit()
            .putString(KEY_SETTINGS, settings.toJson().toString())
            // Literal key kept for parity with the web playroom (localStorage tmdb_include_adult).
            .putBoolean("tmdb_include_adult", settings.includeAdult)
            .apply()
    }

    /** Last scheduled refresh per source type, used to honour 12/24-hour cadences. */
    fun lastRefresh(target: SyncTarget): Long = prefs.getLong(refreshKey(target), 0L)

    fun recordRefresh(target: SyncTarget, at: Long = System.currentTimeMillis()) {
        prefs.edit().putLong(refreshKey(target), at).apply()
    }

    private fun refreshKey(target: SyncTarget) = "sync_last_${target.name.lowercase(Locale.US)}"

    companion object {
        private const val KEY_SETTINGS = "settings_json"
    }
}
