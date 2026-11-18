package com.streamline.cinema.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MusicCatalogTest {

    private fun manifest(json: String) = AddonManifest.fromJson(JSONObject(json))

    @Test
    fun musicCatalogsAreDetectedByDeclaredTypeOnly() {
        val addon = manifest(
            """{"id":"music.example","name":"Music","types":["music"],
                "resources":["catalog","stream"],
                "catalogs":[
                  {"type":"music","id":"explore","name":"Explore"},
                  {"type":"music","id":"charts","name":"Charts","extra":[{"name":"country","options":["US","GB"]}]},
                  {"type":"tv","id":"live","name":"Live"},
                  {"type":"movie","id":"films","name":"Films"}
                ]}"""
        )
        assertEquals(listOf("explore", "charts"), addon.musicCatalogs.map { it.id })
        assertTrue(addon.catalogs.first { it.id == "charts" }.isMusicCatalog)
        assertFalse(addon.catalogs.first { it.id == "live" }.isMusicCatalog)
        assertEquals(
            listOf("country"),
            addon.catalogs.first { it.id == "charts" }.extras.map { it.name }
        )
    }

    @Test
    fun aLiveCatalogNamedMusicIsNotAMusicCatalog() {
        val addon = manifest(
            """{"id":"tv.example","name":"TV","types":["tv"],"resources":["catalog"],
                "catalogs":[{"type":"tv","id":"music","name":"Music"}]}"""
        )
        assertTrue(addon.musicCatalogs.isEmpty())
    }

    @Test
    fun metaCarriesArtistAlbumArtworkAndDuration() {
        val track = parse(
            """{"metas":[{"id":"yt:dQw4w9WgXcQ","name":"Never Gonna Give You Up",
                 "artist":"Rick Astley","album":"Whenever You Need Somebody",
                 "duration":213,"poster":"https://img.example/p.jpg"}]}"""
        ).single()
        assertEquals("Never Gonna Give You Up", track.title)
        assertEquals("Rick Astley", track.artist)
        assertEquals("Whenever You Need Somebody", track.album)
        assertEquals(213_000L, track.durationMs)
        assertEquals("https://img.example/p.jpg", track.artwork)
        assertEquals("$ADDON|yt:dQw4w9WgXcQ", track.key)
        assertEquals("Music", track.addonName)
        assertEquals("music", track.type)
    }

    @Test
    fun artistArrayAndBackgroundFallbackAreRead() {
        val track = parse(
            """{"metas":[{"id":"a","name":"Track","artists":["A","B"],
                 "background":"https://img.example/b.jpg"}]}"""
        ).single()
        assertEquals("A, B", track.artist)
        assertEquals("https://img.example/b.jpg", track.artwork)
        assertEquals(0L, track.durationMs)
    }

    @Test
    fun nonHttpArtworkIsIgnored() {
        val track = parse(
            """{"metas":[{"id":"a","name":"Track","poster":"youtube://thumb"}]}"""
        ).single()
        assertNull(track.artwork)
    }

    @Test
    fun durationIsSecondsWhileBareRuntimeIsMinutes() {
        assertEquals(225_000L, MusicMeta.durationMs(JSONObject("""{"duration":225}""")))
        assertEquals(225_000L, MusicMeta.durationMs(JSONObject("""{"duration":"3:45"}""")))
        assertEquals(240_000L, MusicMeta.durationMs(JSONObject("""{"runtime":"4 min"}""")))
        assertEquals(240_000L, MusicMeta.durationMs(JSONObject("""{"runtime":4}""")))
        assertEquals(3_723_000L, MusicMeta.durationMs(JSONObject("""{"runtime":"1:02:03"}""")))
        assertEquals(0L, MusicMeta.durationMs(JSONObject("""{"duration":"soon"}""")))
        assertEquals(0L, MusicMeta.durationMs(JSONObject("""{}""")))
    }

    @Test
    fun entriesWithoutIdOrNameAreDroppedAndIdsDoNotRepeat() {
        val tracks = parse(
            """{"metas":[
                {"id":"a","name":"One"},
                {"id":"a","name":"One again"},
                {"name":"No id"},
                {"id":"b"},
                {"id":"c","title":"Title only"}
            ]}"""
        )
        assertEquals(listOf("a", "c"), tracks.map { it.id })
        assertEquals("Title only", tracks.last().title)
    }

    @Test
    fun malformedCatalogBodyIsEmpty() {
        assertTrue(MusicMeta.parseCatalog("not json", ADDON, "Music", "music").isEmpty())
        assertTrue(MusicMeta.parseCatalog("""{"metas":"nope"}""", ADDON, "Music", "music").isEmpty())
    }

    @Test
    fun catalogSearchSupportComesFromTheDeclaredExtra() {
        val catalog = MusicCatalog(
            addonName = "Music",
            addonUrl = ADDON,
            id = "explore",
            name = "Explore",
            type = "music",
            extras = listOf(
                CatalogExtra(name = "search"),
                CatalogExtra(name = "genre", options = listOf("Jazz", "Rock")),
                CatalogExtra(name = "skip")
            )
        )
        assertTrue(catalog.supportsSearch)
        assertEquals(listOf("genre"), catalog.selectableExtras.map { it.name })
        assertEquals("$ADDON|music|explore", catalog.key)
        assertFalse(
            catalog.copy(extras = listOf(CatalogExtra("genre", listOf("Jazz")))).supportsSearch
        )
    }

    @Test
    fun aMusicManifestContributesItsOwnCatalogTypes() {
        val addon = manifest(
            """{"id":"pm","name":"PencariMusic","contentType":"music",
                "types":["track","album","artist","playlist","movie","series"],
                "resources":["search","stream","catalog","meta"],
                "idPrefixes":["pm_"],
                "catalogs":[
                  {"type":"artist","id":"artists","name":"Artists"},
                  {"type":"playlist","id":"sponsor","name":"Sponsor"},
                  {"type":"movie","id":"search","name":"Search","extra":[{"name":"search","isRequired":true}]},
                  {"type":"series","id":"pm_music_artists","name":"Artists"},
                  {"type":"movie","id":"promo","name":"Promo"}
                ]}"""
        )
        assertEquals(listOf("artists", "sponsor", "search"), addon.musicCatalogs.map { it.id })
    }

    @Test
    fun aVideoManifestIsNotWidenedByMusicTypesOrSearch() {
        val addon = manifest(
            """{"id":"tv","name":"TV","types":["artist","movie"],"resources":["catalog"],
                "catalogs":[
                  {"type":"artist","id":"a","name":"Artists"},
                  {"type":"movie","id":"m","name":"Films","extra":[{"name":"search"}]}
                ]}"""
        )
        assertTrue(addon.musicCatalogs.isEmpty())
    }

    @Test
    fun itemShapedTracksStreamThroughTheMovieNamespace() {
        val track = MusicMeta.parseCatalog(
            """{"items":[{"id":"nIU2gC","title":"Trancemaster 02","artist":"Various Artists",
                 "durationMs":4289000,"artworkURL":"https://img.example/a.png","type":"track"}]}""",
            ADDON,
            "PencariMusic",
            "artist",
            listOf("pm_")
        ).single()
        assertEquals("Trancemaster 02", track.title)
        assertEquals("Various Artists", track.artist)
        assertEquals(4_289_000L, track.durationMs)
        assertEquals("https://img.example/a.png", track.artwork)
        assertEquals("track", track.type)
        assertEquals("movie", track.streamType)
        assertEquals("pm_nIU2gC", track.streamId)
    }

    @Test
    fun anIdThatAlreadyCarriesThePrefixIsNotPrefixedAgain() {
        val track = MusicMeta.parseCatalog(
            """{"metas":[{"id":"pm_XI6jJj","name":"Popular","type":"movie"}]}""",
            ADDON,
            "PencariMusic",
            "movie",
            listOf("pm_")
        ).single()
        assertEquals("movie", track.streamType)
        assertEquals("pm_XI6jJj", track.streamId)
    }

    @Test
    fun aDeclaredMusicCatalogKeepsItsOwnStreamCoordinates() {
        val track = parse(
            """{"metas":[{"id":"yt:dQw4w9WgXcQ","name":"Never Gonna Give You Up"}]}"""
        ).single()
        assertEquals("music", track.streamType)
        assertEquals("yt:dQw4w9WgXcQ", track.streamId)
    }

    @Test
    fun streamCoordinatesSurviveJson() {
        val track = MusicMeta.parseCatalog(
            """{"items":[{"id":"nIU2gC","title":"Trancemaster 02","type":"track"}]}""",
            ADDON,
            "PencariMusic",
            "artist",
            listOf("pm_")
        ).single()
        val restored = MusicTrack.fromJson(track.toJson())!!
        assertEquals("movie", restored.streamType)
        assertEquals("pm_nIU2gC", restored.streamId)
    }

    private fun parse(body: String) = MusicMeta.parseCatalog(body, ADDON, "Music", "music")

    companion object {
        private const val ADDON = "https://music.example/manifest.json"
    }
}
