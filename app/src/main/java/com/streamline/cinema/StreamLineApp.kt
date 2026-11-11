package com.streamline.cinema

import android.app.Application
import android.content.Context
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.memory.MemoryCache
import com.streamline.cinema.data.ChannelHealthStore
import com.streamline.cinema.data.CinemaRepository
import com.streamline.cinema.data.GuideIndexStore
import com.streamline.cinema.data.Http
import com.streamline.cinema.data.IntroDbClient
import com.streamline.cinema.data.IptvStore
import com.streamline.cinema.data.KidsModeManager
import com.streamline.cinema.data.LibraryStore
import com.streamline.cinema.data.LivePrefsStore
import com.streamline.cinema.data.ProfileStore
import com.streamline.cinema.data.SettingsStore
import com.streamline.cinema.data.StreamBadgeStore

class AppContainer(context: Context) {
    val settingsStore = SettingsStore(context)
    val profileStore = ProfileStore(context)
    val kidsMode = KidsModeManager(context, settingsStore, profileStore)
    val libraryStore = LibraryStore(context)
    val iptvStore = IptvStore(context)
    val guideIndexStore = GuideIndexStore(context)
    val channelHealth = ChannelHealthStore(context)
    val livePrefs = LivePrefsStore(context)
    val badges = StreamBadgeStore(context)
    val introDb = IntroDbClient(context.filesDir)
    val repository = CinemaRepository(context, settingsStore, libraryStore, iptvStore, guideIndexStore)
}

// Coil's default memory cache is 25% of the heap. Poster rails run alongside a
// video decoder on 1 GB boxes, so artwork gets a smaller slice and lets the
// player keep its buffers.
private const val ARTWORK_MEMORY_CACHE_FRACTION = 0.08

class StreamLineApp : Application(), ImageLoaderFactory {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }

    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        // Artwork goes through the app's HTTP stack so the configured DNS
        // resolver, UA and shared connection pool apply to posters too.
        .okHttpClient { Http.client }
        .memoryCache {
            MemoryCache.Builder(this)
                .maxSizePercent(ARTWORK_MEMORY_CACHE_FRACTION)
                .build()
        }
        .crossfade(true)
        .build()
}
