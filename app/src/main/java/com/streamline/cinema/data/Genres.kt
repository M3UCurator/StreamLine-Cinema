package com.streamline.cinema.data

object Genres {
    val movie: Map<Int, String> = mapOf(
        28 to "Action", 12 to "Adventure", 16 to "Animation", 35 to "Comedy", 80 to "Crime",
        99 to "Documentary", 18 to "Drama", 10751 to "Family", 14 to "Fantasy", 36 to "History",
        27 to "Horror", 10402 to "Music", 9648 to "Mystery", 10749 to "Romance",
        878 to "Science Fiction", 10770 to "TV Movie", 53 to "Thriller", 10752 to "War", 37 to "Western"
    )

    val tv: Map<Int, String> = mapOf(
        10759 to "Action & Adventure", 16 to "Animation", 35 to "Comedy", 80 to "Crime",
        99 to "Documentary", 18 to "Drama", 10751 to "Family", 10762 to "Kids", 9648 to "Mystery",
        10763 to "News", 10764 to "Reality", 10765 to "Sci-Fi & Fantasy", 10766 to "Soap",
        10767 to "Talk", 10768 to "War & Politics", 37 to "Western"
    )

    private fun table(type: MediaType): Map<Int, String> =
        if (type == MediaType.MOVIE) movie else tv

    fun names(type: MediaType, ids: List<Int>): List<String> {
        val table = table(type)
        return ids.mapNotNull { table[it] }.distinct()
    }

    fun names(type: MediaType, array: org.json.JSONArray?): List<String> {
        if (array == null) return emptyList()
        val table = table(type)
        return (0 until array.length()).mapNotNull { i ->
            val obj = array.optJSONObject(i)
            if (obj != null) obj.optString("name").takeIf { it.isNotBlank() }
            else table[array.optInt(i)]
        }.distinct()
    }

    /** Reverse lookup so catalog entries (which store names) can join the shared genre map. */
    fun idsFor(type: MediaType, names: List<String>): List<Int> {
        if (names.isEmpty()) return emptyList()
        val lookup = table(type).entries.associate { (id, name) -> name.lowercase() to id }
        return names.mapNotNull { lookup[it.trim().lowercase()] }.distinct()
    }
}
