package com.streamline.cinema.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Rating ceiling for one profile. Unknown certifications stay visible. */
enum class MaturityLevel(val label: String, val detail: String, val maxRank: Int) {
    KIDS("Kids", "G, PG and TV-Y / TV-G", 0),
    TEEN("Teen", "Up to PG-13 and TV-14", 1),
    ADULT("Adult", "Everything allowed by app settings", 2);

    companion object {
        fun from(raw: String?): MaturityLevel = entries.firstOrNull { it.name == raw } ?: ADULT
    }
}

private fun certificationRank(certification: String?): Int? {
    val cert = certification?.trim()?.uppercase() ?: return null
    return when (cert) {
        "G", "TV-G", "TV-Y", "TV-Y7", "U", "U/A", "APPROVED", "ALL", "0" -> 0
        "PG", "TV-PG", "6", "7", "6+", "7+", "U/A 7+", "U/A 13+" -> 0
        "PG-13", "TV-14", "12", "12A", "13", "14", "15", "T" -> 1
        "R", "TV-MA", "M", "MA 15+", "16", "17", "18", "18+", "NC-17", "A", "X" -> 2
        else -> null
    }
}

fun CatalogItem.allowedFor(level: MaturityLevel): Boolean {
    if (level == MaturityLevel.ADULT) return true
    // Discover rows rarely carry ratings; those titles stay available rather
    // than blanking the kids shelf. Only a known higher rating is filtered.
    val rank = certificationRank(certification) ?: return true
    return rank <= level.maxRank
}

enum class AudioLanguage(val label: String, val code: String) {
    AUTO("Automatic", ""),
    ENGLISH("English", "en"),
    SPANISH("Spanish", "es"),
    FRENCH("French", "fr"),
    GERMAN("German", "de"),
    HINDI("Hindi", "hi"),
    NEPALI("Nepali", "ne"),
    JAPANESE("Japanese", "ja"),
    KOREAN("Korean", "ko"),
    PORTUGUESE("Portuguese", "pt"),
    ARABIC("Arabic", "ar"),
    RUSSIAN("Russian", "ru");

    companion object {
        fun from(code: String?): AudioLanguage = entries.firstOrNull { it.code == code } ?: AUTO
    }
}

enum class SubtitleLanguageOption(val label: String, val code: String) {
    OFF("Off", ""),
    MATCH_AUDIO("Match audio", "auto"),
    ENGLISH("English", "en"),
    SPANISH("Spanish", "es"),
    FRENCH("French", "fr"),
    GERMAN("German", "de"),
    HINDI("Hindi", "hi"),
    NEPALI("Nepali", "ne"),
    JAPANESE("Japanese", "ja"),
    KOREAN("Korean", "ko"),
    PORTUGUESE("Portuguese", "pt"),
    ARABIC("Arabic", "ar"),
    RUSSIAN("Russian", "ru");

    companion object {
        fun from(code: String?): SubtitleLanguageOption =
            entries.firstOrNull { it.code == code } ?: OFF
    }
}

data class CinemaProfile(
    val id: String,
    val name: String,
    val maturity: MaturityLevel = MaturityLevel.ADULT,
    val colorIndex: Int = 0,
    val audioLanguage: String = "",
    val subtitleLanguage: String = "",
    val createdAt: Long = System.currentTimeMillis()
) {
    val isKids: Boolean get() = maturity == MaturityLevel.KIDS

    val initial: String
        get() = name.trim().take(1).uppercase().ifBlank { "?" }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("maturity", maturity.name)
        put("colorIndex", colorIndex)
        put("audioLanguage", audioLanguage)
        put("subtitleLanguage", subtitleLanguage)
        put("createdAt", createdAt)
    }

    companion object {
        const val DEFAULT_ID = "default"

        fun fromJson(o: JSONObject): CinemaProfile = CinemaProfile(
            id = o.optString("id").takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString(),
            name = o.optString("name").takeIf { it.isNotBlank() } ?: "Profile",
            maturity = MaturityLevel.from(o.stringOrNull("maturity")),
            colorIndex = o.optInt("colorIndex"),
            audioLanguage = o.optString("audioLanguage"),
            subtitleLanguage = o.optString("subtitleLanguage"),
            createdAt = o.optLong("createdAt", System.currentTimeMillis())
        )

        fun default(): CinemaProfile = CinemaProfile(
            id = DEFAULT_ID,
            name = "You",
            maturity = MaturityLevel.ADULT
        )
    }
}

/**
 * One profile list shared by the whole device. Favorites, watch progress and
 * watched-episode marks live in [LibraryStore] keyed by profile id; the catalog
 * itself stays shared.
 */
class ProfileStore(context: Context) {

    private val prefs = context.getSharedPreferences("streamline_profiles", Context.MODE_PRIVATE)

    private val _profiles = MutableStateFlow<List<CinemaProfile>>(emptyList())
    val profiles: StateFlow<List<CinemaProfile>> = _profiles.asStateFlow()

    private val _active = MutableStateFlow(CinemaProfile.default())
    val active: StateFlow<CinemaProfile> = _active.asStateFlow()

    private var activeId: String = CinemaProfile.DEFAULT_ID

    init {
        load()
    }

    private fun load() {
        val stored = runCatching {
            prefs.getString(KEY_PROFILES, null)?.let(::JSONArray)
        }.getOrNull()
        val loaded = stored?.let { array ->
            (0 until array.length()).mapNotNull { index ->
                array.optJSONObject(index)?.let { runCatching { CinemaProfile.fromJson(it) }.getOrNull() }
            }
        }.orEmpty()
        val profiles = loaded.ifEmpty { listOf(CinemaProfile.default()) }
        _profiles.value = profiles
        activeId = prefs.getString(KEY_ACTIVE, null)
            ?.takeIf { id -> profiles.any { it.id == id } }
            ?: profiles.first().id
        _active.value = profiles.first { it.id == activeId }
        if (loaded.isEmpty()) persist()
    }

    fun profile(id: String?): CinemaProfile? =
        id?.let { wanted -> _profiles.value.firstOrNull { it.id == wanted } }

    fun select(id: String): CinemaProfile? {
        val target = _profiles.value.firstOrNull { it.id == id } ?: return null
        if (target.id != activeId) {
            activeId = target.id
            _active.value = target
            persist()
        }
        return target
    }

    /** Adds a new profile, or replaces the existing row with the same id. */
    fun upsert(profile: CinemaProfile): CinemaProfile {
        val clean = profile.copy(
            name = profile.name.trim().take(24).ifBlank { "Profile" },
            colorIndex = profile.colorIndex.coerceIn(0, AVATAR_COLORS - 1)
        )
        val existing = _profiles.value.indexOfFirst { it.id == clean.id }
        _profiles.value = if (existing >= 0) {
            _profiles.value.toMutableList().also { it[existing] = clean }
        } else {
            _profiles.value + clean
        }
        if (activeId == clean.id) _active.value = clean
        persist()
        return clean
    }

    fun add(name: String, maturity: MaturityLevel): CinemaProfile = upsert(
        CinemaProfile(
            id = UUID.randomUUID().toString(),
            name = name,
            maturity = maturity,
            colorIndex = _profiles.value.size % AVATAR_COLORS
        )
    )

    /** Removes a profile; the last remaining profile is never deleted. */
    fun remove(id: String): Boolean {
        if (_profiles.value.size <= 1) return false
        val remaining = _profiles.value.filterNot { it.id == id }
        if (remaining.size == _profiles.value.size) return false
        _profiles.value = remaining
        if (activeId == id) {
            activeId = remaining.first().id
            _active.value = remaining.first()
        }
        persist()
        return true
    }

    private fun persist() {
        runCatching {
            val root = JSONArray(_profiles.value.map { it.toJson() })
            prefs.edit()
                .putString(KEY_PROFILES, root.toString())
                .putString(KEY_ACTIVE, activeId)
                .apply()
        }
    }

    companion object {
        private const val KEY_PROFILES = "profiles"
        private const val KEY_ACTIVE = "activeProfile"
        const val AVATAR_COLORS = 6
    }
}
