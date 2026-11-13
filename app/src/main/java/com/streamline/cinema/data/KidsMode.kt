package com.streamline.cinema.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray

/**
 * Kids Mode is linked to Kids profiles: selecting a profile whose maturity is
 * [MaturityLevel.KIDS] activates it, and the active profile is persisted by
 * [ProfileStore] so the mode survives restarts. The set of approved titles and
 * live channels is device-wide, so every Kids profile sees the same safe list.
 */
class KidsModeManager(
    context: Context,
    private val settingsStore: SettingsStore,
    private val profileStore: ProfileStore
) {
    private val prefs = context.getSharedPreferences("streamline_kids_mode", Context.MODE_PRIVATE)

    private val _approved = MutableStateFlow(loadApproved())
    val approved: StateFlow<Set<String>> = _approved.asStateFlow()

    /** True while the active profile is a Kids profile. */
    fun isActive(): Boolean = profileStore.active.value.isKids

    fun isApproved(key: String): Boolean = key in _approved.value

    fun setApproved(key: String, approved: Boolean) {
        if (key.isBlank()) return
        _approved.value = if (approved) _approved.value + key else _approved.value - key
        persist()
    }

    fun clearApproved() {
        if (_approved.value.isEmpty()) return
        _approved.value = emptySet()
        persist()
    }

    /**
     * Switches to the first Kids profile, creating one when the household has
     * none yet. Returns the profile that is now active.
     */
    fun enable(): CinemaProfile {
        val kids = profileStore.profiles.value.firstOrNull { it.isKids }
            ?: profileStore.add("Kids", MaturityLevel.KIDS)
        profileStore.select(kids.id)
        return kids
    }

    /** Switches back to the first non-Kids profile, when one exists. */
    fun disable(): Boolean {
        val adult = profileStore.profiles.value.firstOrNull { !it.isKids } ?: return false
        profileStore.select(adult.id)
        return true
    }

    /** Without a configured PIN the documented default (1234) is accepted. */
    fun verifyPin(pin: String): Boolean {
        val stored = settingsStore.settings.value.parentalPinHash
        return ParentalPin.matches(pin, stored.ifBlank { DEFAULT_PIN_HASH })
    }

    val isDefaultPin: Boolean get() = !settingsStore.settings.value.hasParentalPin

    fun setPin(pin: String) {
        if (!ParentalPin.isValid(pin)) return
        settingsStore.update { it.copy(parentalPinHash = ParentalPin.hash(pin)) }
    }

    /** Removes a custom PIN; Kids Mode falls back to the default 1234. */
    fun clearPin() {
        settingsStore.update { it.copy(parentalPinHash = "") }
    }

    private fun loadApproved(): Set<String> = runCatching {
        prefs.getString(KEY_APPROVED, null)?.let(::JSONArray)?.let { array ->
            (0 until array.length()).mapNotNull { index ->
                array.optString(index).takeIf { it.isNotBlank() }
            }.toSet()
        }
    }.getOrNull().orEmpty()

    private fun persist() {
        runCatching {
            prefs.edit()
                .putString(KEY_APPROVED, JSONArray(_approved.value.toList()).toString())
                .apply()
        }
    }

    companion object {
        const val DEFAULT_PIN = "1234"
        val DEFAULT_PIN_HASH: String = ParentalPin.hash(DEFAULT_PIN)
        private const val KEY_APPROVED = "approved_ids"
    }
}
