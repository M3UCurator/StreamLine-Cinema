package com.streamline.cinema.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException

/** One stream badge from a badge document (a PNG pill plus the text pattern it matches). */
data class StreamBadge(
    val id: String,
    val groupId: String,
    val name: String,
    val imageUrl: String,
    val pattern: String
) {
    // Patterns come from user-supplied documents; a malformed one must not take
    // the source picker down, so compilation failures fall through to "no match".
    val regex: Regex? by lazy { runCatching { Regex(pattern) }.getOrNull() }
}

data class StreamBadgeGroup(val id: String, val name: String)

/**
 * Badge set shared by the player source cards and the Settings preview. Badge
 * documents (FR-Badges and compatible layouts) are `{filters:[{pattern,
 * imageURL, isEnabled, groupId, name}], groups:[{id, name}]}`.
 */
data class StreamBadgeCatalog(
    val badges: List<StreamBadge> = emptyList(),
    val groups: List<StreamBadgeGroup> = emptyList(),
    val sourceUrl: String = "",
    val updatedAt: Long = 0L
) {
    val isEmpty: Boolean get() = badges.isEmpty()

    /** Groups in document order, each with its badges; empty groups are dropped. */
    fun sections(): List<Pair<StreamBadgeGroup, List<StreamBadge>>> {
        if (badges.isEmpty()) return emptyList()
        val known = groups.associateBy { it.id }
        val order = groups.map { it.id } + badges.map { it.groupId }.distinct()
        return order.distinct()
            .mapNotNull { id ->
                val group = known[id] ?: StreamBadgeGroup(id, id.replaceFirstChar { it.uppercase() })
                val entries = badges.filter { it.groupId == id }
                if (entries.isEmpty()) null else group to entries
            }
    }

    /** Badges that match [text], in document order, capped for a single card. */
    fun matches(text: String, limit: Int = BADGES_PER_CARD): List<StreamBadge> {
        if (text.isBlank()) return emptyList()
        return badges.asSequence()
            .filter { it.regex?.containsMatchIn(text) == true }
            .take(limit)
            .toList()
    }

    companion object {
        const val BADGES_PER_CARD = 5

        val EMPTY = StreamBadgeCatalog()

        fun parse(
            filters: JSONArray?,
            groups: JSONArray?,
            sourceUrl: String,
            updatedAt: Long
        ): StreamBadgeCatalog {
            val badges = ArrayList<StreamBadge>(filters?.length() ?: 0)
            if (filters != null) {
                for (index in 0 until filters.length()) {
                    val entry = filters.optJSONObject(index) ?: continue
                    if (entry.optString("type").let { it.isNotBlank() && it != "filter" }) continue
                    if (!entry.optBoolean("isEnabled", true)) continue
                    val imageUrl = entry.stringOrNull("imageURL") ?: continue
                    val pattern = entry.stringOrNull("pattern") ?: continue
                    if (!imageUrl.startsWith("http")) continue
                    // A pathological pattern would be retried on every card; keep
                    // the document to plausible sizes.
                    if (pattern.length > 400) continue
                    badges += StreamBadge(
                        id = entry.stringOrNull("id") ?: "badge-$index",
                        groupId = entry.stringOrNull("groupId") ?: "other",
                        name = entry.stringOrNull("name") ?: entry.optString("id"),
                        imageUrl = imageUrl,
                        pattern = pattern
                    )
                }
            }
            val parsedGroups = ArrayList<StreamBadgeGroup>(groups?.length() ?: 0)
            if (groups != null) {
                for (index in 0 until groups.length()) {
                    val entry = groups.optJSONObject(index) ?: continue
                    val id = entry.stringOrNull("id") ?: continue
                    parsedGroups += StreamBadgeGroup(
                        id = id,
                        name = entry.stringOrNull("name") ?: id
                    )
                }
            }
            return StreamBadgeCatalog(
                badges = badges.distinctBy { it.id },
                groups = parsedGroups.distinctBy { it.id },
                sourceUrl = sourceUrl,
                updatedAt = updatedAt
            )
        }
    }
}

data class StreamBadgeState(
    val busy: Boolean = false,
    val message: String? = null,
    val failed: Boolean = false,
    val lastUpdated: Long = 0L
)

/**
 * Persists the last good badge document so the source picker has badges on cold
 * start, then refreshes it from the user's link on demand or once a day.
 */
class StreamBadgeStore(context: Context) {

    private val file = File(context.filesDir, "stream_badges.json")
    private val lock = Any()
    private val _catalog = MutableStateFlow(load())
    val catalog: StateFlow<StreamBadgeCatalog> = _catalog.asStateFlow()
    private val _state = MutableStateFlow(StreamBadgeState(lastUpdated = _catalog.value.updatedAt))
    val state: StateFlow<StreamBadgeState> = _state.asStateFlow()

    private fun load(): StreamBadgeCatalog {
        val raw = AtomicStore.readWithBackup(file) ?: return StreamBadgeCatalog.EMPTY
        return runCatching {
            val document = JSONObject(raw)
            StreamBadgeCatalog.parse(
                filters = document.optJSONArray("filters"),
                groups = document.optJSONArray("groups"),
                sourceUrl = document.optString("sourceUrl"),
                updatedAt = document.optLong("updatedAt")
            )
        }.getOrDefault(StreamBadgeCatalog.EMPTY)
    }

    /**
     * Fetches [url] and stores the document. Returns the badge count when the
     * link is ready to use — either freshly fetched or already cached within
     * [CACHE_TTL_MS]; failure keeps the previous document in place.
     */
    suspend fun refresh(url: String, force: Boolean = false): Result<Int> =
        withContext(Dispatchers.IO) {
            val target = url.trim()
            if (target.isEmpty()) {
                synchronized(lock) {
                    _catalog.value = StreamBadgeCatalog.EMPTY
                    _state.value = StreamBadgeState()
                    runCatching { file.delete() }
                }
                return@withContext Result.success(0)
            }
            val current = _catalog.value
            if (!force &&
                current.sourceUrl.equals(target, ignoreCase = true) &&
                !current.isEmpty &&
                System.currentTimeMillis() - current.updatedAt < CACHE_TTL_MS
            ) {
                _state.value = StreamBadgeState(
                    message = "${current.badges.size} badges ready",
                    lastUpdated = current.updatedAt
                )
                return@withContext Result.success(current.badges.size)
            }
            _state.value = StreamBadgeState(busy = true, lastUpdated = current.updatedAt)
            val outcome = runCatching {
                // withTimeoutOrNull returns null when the transfer is still open.
                withTimeoutOrNull(20_000L) { fetch(target) }
                    ?: throw IOException("Badge source did not answer in time")
            }
            outcome.fold(
                onSuccess = { fetched ->
                    synchronized(lock) {
                        _catalog.value = fetched
                        runCatching {
                            AtomicStore.write(file, documentFor(fetched), "stream badges")
                        }
                        _state.value = StreamBadgeState(
                            message = "${fetched.badges.size} badges loaded",
                            lastUpdated = fetched.updatedAt
                        )
                    }
                    Result.success(fetched.badges.size)
                },
                onFailure = { error ->
                    val message = error.message ?: "Could not load the badge source"
                    _state.value = StreamBadgeState(
                        message = message,
                        failed = true,
                        lastUpdated = current.updatedAt
                    )
                    Result.failure(error)
                }
            )
        }

    private fun documentFor(catalog: StreamBadgeCatalog): String {
        val filters = JSONArray()
        catalog.badges.forEach { badge ->
            filters.put(
                JSONObject().apply {
                    put("id", badge.id)
                    put("groupId", badge.groupId)
                    put("name", badge.name)
                    put("pattern", badge.pattern)
                    put("imageURL", badge.imageUrl)
                    put("isEnabled", true)
                }
            )
        }
        val groups = JSONArray()
        catalog.groups.forEach { group ->
            groups.put(JSONObject().apply {
                put("id", group.id)
                put("name", group.name)
            })
        }
        return JSONObject().apply {
            put("sourceUrl", catalog.sourceUrl)
            put("updatedAt", catalog.updatedAt)
            put("filters", filters)
            put("groups", groups)
        }.toString()
    }

    private suspend fun fetch(url: String): StreamBadgeCatalog {
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("User-Agent", Http.BROWSER_USER_AGENT)
            .build()
        Http.client.await(request).use { response ->
            if (!response.isSuccessful) {
                throw IOException("Badge source answered HTTP ${response.code}")
            }
            val text = response.bodyStringCapped(2 * 1024 * 1024)
            val document = runCatching { JSONObject(text) }.getOrElse {
                throw IOException("Badge source is not a JSON document")
            }
            val catalog = StreamBadgeCatalog.parse(
                filters = document.optJSONArray("filters"),
                groups = document.optJSONArray("groups"),
                sourceUrl = url,
                updatedAt = System.currentTimeMillis()
            )
            if (catalog.isEmpty) throw IOException("No enabled badges in that document")
            return catalog
        }
    }

    companion object {
        private const val CACHE_TTL_MS = 24 * 60 * 60 * 1000L
    }
}

/** Accepts only http(s) badge documents; blank clears the setting. */
fun normalizeBadgeSource(raw: String): String? {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return ""
    val parsed = trimmed.toHttpUrlOrNull() ?: return null
    if (parsed.host.isBlank()) return null
    return trimmed
}
