package com.streamline.cinema.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MdbListParseTest {

    private val html = """
        <html><body>
        <div class="list-row">
          <div class="poster">
            <a href="https://www.themoviedb.org/movie/155">
              <img src="https://image.tmdb.org/t/p/w300/abc123.jpg" alt="The Dark Knight">
            </a>
          </div>
          <div class="meta">
            <h3><a href="https://www.themoviedb.org/movie/155" title="The Dark Knight">The Dark Knight</a></h3>
            <p>2008 &middot; 8.5 &middot; Action</p>
          </div>
        </div>
        <div class="list-row">
          <div class="poster">
            <a href="https://www.themoviedb.org/tv/1399"><img src="/posters/got.jpg" alt="Game of Thrones"></a>
          </div>
          <div class="meta">
            <h3><a href="https://www.themoviedb.org/tv/1399" title="Game of Thrones">Game of Thrones</a></h3>
            <p>2011 &middot; 8.4</p>
          </div>
        </div>
        <a href="https://www.themoviedb.org/movie/155">Duplicate link</a>
        </body></html>
    """.trimIndent()

    @Test
    fun parsesMoviesAndShowsFromListMarkup() {
        val items = MdbListClient().parseHtml(html)

        assertEquals(2, items.size)
        val movie = items.first { it.tmdbId == 155 }
        assertEquals(MediaType.MOVIE, movie.type)
        assertEquals("The Dark Knight", movie.title)
        assertEquals("2008", movie.year)
        assertEquals(8.5, movie.rating ?: 0.0, 0.001)
        assertEquals("https://image.tmdb.org/t/p/w300/abc123.jpg", movie.posterUrl)

        val show = items.first { it.tmdbId == 1399 }
        assertEquals(MediaType.TV, show.type)
        assertEquals("Game of Thrones", show.title)
        assertEquals("2011", show.year)
        assertEquals("https://mdblist.com/posters/got.jpg", show.posterUrl)
    }

    @Test
    fun throwsWhenNoTmdbLinksArePresent() {
        val error = runCatching { MdbListClient().parseHtml("<html><body>empty</body></html>") }
            .exceptionOrNull()
        assertTrue(error is java.io.IOException)
    }

    @Test
    fun ignoresNonTmdbAnchors() {
        val error = runCatching {
            MdbListClient().parseHtml("""<a href="https://imdb.com/title/tt0111161">x</a>""")
        }.exceptionOrNull()
        assertTrue(error is java.io.IOException)
    }

    private val cardHtml = """
        <div class="card" data-media-id="983906" data-mediatype="movie" data-listid="14">
          <div class="poster-frame">
            <a href="/movie/l36q-the-hunger-games?cache=1"><img class="left floated ui rounded image poster-card poster-fixed" src="https://image.tmdb.org/t/p/w200/mBaXZ95R2OxueZhvQbcEWy2DqyO.jpg" width="200" alt="poster"></a>
            <div class="poster-provider-badge"><a href="https://click.justwatch.com/a?cx=eyJz"><img src="https://image.tmdb.org/t/p/original/qdEGArH3lKfFnAtYXMkSYk5wxuG.png" class="ui rounded image" alt="Apple TV Store"></a></div>
          </div>
          <div class="card-score-container"><div  class="idscore"  style="background-color: #4CAF50;"
              >
              7.0
            </div></div>
          <div class="ui grid"><div class="nine wide column idtext"><a href="https://www.imdb.com/title/tt10545296" target="_blank">IMDb</a></div><div class="three wide column idtext">6.6</div></div>
          <div class="ui grid"><div class="nine wide column idtext"><a href="https://www.themoviedb.org/movie/695721">TMDb</a></div><div class="three wide column idtext">69 </div></div>
          <div class="header movie-title" title="The Hunger Games: The Ballad of Songbirds &amp; Snakes (2023)">
          </div>
          <div class="description collapsible-description">64 years before he becomes the tyrannical president of Panem.</div>
        </div>
        <div class="card" data-media-id="67077" data-mediatype="movie">
          <div class="poster-frame"><a href="/movie/1fr9-knives-out"><img class="ui rounded image poster-card poster-fixed" src="https://image.tmdb.org/t/p/w200/pThyQovXQrw2m0s9x82twj48Jq4.jpg" alt="poster"></a></div>
          <div class="card-score-container"><div class="idscore">8.4</div></div>
          <div class="ui grid"><div class="nine wide column idtext"><a href="https://www.themoviedb.org/movie/546554">TMDb</a></div></div>
          <div class="header movie-title" title="Knives Out (2019)"></div>
        </div>
    """.trimIndent()

    @Test
    fun readsTitlesAndPostersFromCardLayout() {
        val items = MdbListClient().parseHtml(cardHtml)

        assertEquals(2, items.size)
        val hunger = items.first { it.tmdbId == 695721 }
        assertEquals("The Hunger Games: The Ballad of Songbirds & Snakes", hunger.title)
        assertEquals("2023", hunger.year)
        assertEquals(7.0, hunger.rating ?: 0.0, 0.001)
        assertEquals(
            "https://image.tmdb.org/t/p/w200/mBaXZ95R2OxueZhvQbcEWy2DqyO.jpg",
            hunger.posterUrl
        )
        assertEquals("64 years before he becomes the tyrannical president of Panem.", hunger.overview)
        // Card blocks are offset-based, so an IMDb link here can belong to the next
        // card. Imports must not carry it; the id is resolved from TMDB instead.
        assertEquals("", hunger.imdbId)

        val knives = items.first { it.tmdbId == 546554 }
        assertEquals("Knives Out", knives.title)
        assertEquals("2019", knives.year)
        assertEquals(8.4, knives.rating ?: 0.0, 0.001)
    }
}
