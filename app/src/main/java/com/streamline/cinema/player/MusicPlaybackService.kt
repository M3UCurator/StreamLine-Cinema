package com.streamline.cinema.player

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.streamline.cinema.MainActivity
import com.streamline.cinema.StreamLineApp
import com.streamline.cinema.data.CinemaRepository
import com.streamline.cinema.data.Http
import com.streamline.cinema.data.MusicAudioCache
import com.streamline.cinema.data.MusicHistoryStore
import com.streamline.cinema.data.MusicTrack
import com.streamline.cinema.data.PlaybackOption
import com.streamline.cinema.data.ProfileStore
import com.streamline.cinema.data.SettingsStore
import com.streamline.cinema.data.SponsorBlock
import com.streamline.cinema.data.SponsorBlockClient
import com.streamline.cinema.data.SponsorSegment
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * Custom scheme for queue entries. A track's real URL only exists after the
 * owning addon answers `/stream/{type}/{id}.json`, so the queue stores the addon
 * coordinates and [MusicStreamResolver] resolves just before the first read.
 */
internal object MusicMedia {

    internal const val URI_SCHEME = "streamline-music"

    internal fun trackFromUri(uri: Uri): MusicTrack? {
        if (uri.scheme != URI_SCHEME) return null
        val addonUrl = uri.getQueryParameter("addon")
        val id = uri.getQueryParameter("id")
        if (addonUrl.isNullOrBlank() || id.isNullOrBlank()) return null
        val type = uri.getQueryParameter("type").orEmpty()
        return MusicTrack(
            id = id,
            title = uri.getQueryParameter("title").orEmpty(),
            addonName = uri.getQueryParameter("name").orEmpty(),
            addonUrl = addonUrl,
            type = type,
            streamType = uri.getQueryParameter("streamType").orEmpty().ifBlank { type },
            streamId = uri.getQueryParameter("streamId").orEmpty().ifBlank { id }
        )
    }
}

internal fun MusicTrack.toMediaItem(): MediaItem {
    val uri = Uri.Builder()
        .scheme(MusicMedia.URI_SCHEME)
        .authority("track")
        .appendQueryParameter("addon", addonUrl)
        .appendQueryParameter("type", type)
        .appendQueryParameter("id", id)
        .appendQueryParameter("streamType", streamType)
        .appendQueryParameter("streamId", streamId)
        .appendQueryParameter("title", title)
        .appendQueryParameter("name", addonName)
        .build()
    val metadata = MediaMetadata.Builder()
        .setTitle(title)
        .setIsPlayable(true)
        .setIsBrowsable(false)
        .apply {
            if (artist.isNotBlank()) setArtist(artist)
            if (album.isNotBlank()) setAlbumTitle(album)
            artwork?.let { setArtworkUri(Uri.parse(it)) }
        }
        .build()
    return MediaItem.Builder()
        .setUri(uri)
        .setMediaId(key)
        // Stable across re-resolutions, so the audio cache never keys on a URL
        // that carries an expiring token.
        .setCustomCacheKey(key)
        .setMediaMetadata(metadata)
        .build()
}

/**
 * Swaps a queued track's placeholder URI for the addon's stream URL and headers.
 * The resolver runs on ExoPlayer's loading thread and may block; only the item
 * that is about to play is ever resolved, so skipping a queue stays cheap.
 */
@OptIn(UnstableApi::class)
internal class MusicStreamResolver(
    private val repository: CinemaRepository
) : ResolvingDataSource.Resolver {

    private data class Entry(val option: PlaybackOption, val resolvedAt: Long)

    private val cache = ConcurrentHashMap<String, Entry>()

    override fun resolveDataSpec(dataSpec: DataSpec): DataSpec {
        val track = MusicMedia.trackFromUri(dataSpec.uri) ?: return dataSpec
        val option = option(track)
        // Addon headers win; a browser UA is only the fallback for CDNs that
        // reject clients without one.
        val headers = if (option.headers.keys.any { it.equals("User-Agent", true) }) {
            option.headers
        } else {
            option.headers + ("User-Agent" to Http.BROWSER_USER_AGENT)
        }
        return dataSpec.buildUpon()
            .setUri(option.url)
            .setHttpRequestHeaders(headers)
            .build()
    }

    private fun option(track: MusicTrack): PlaybackOption {
        val now = SystemClock.elapsedRealtime()
        cache[track.key]?.let { cached ->
            if (now - cached.resolvedAt < TTL_MS) return cached.option
        }
        val option = runBlocking { repository.musicStream(track) }
            ?: throw IOException(
                "No playable stream for ${track.title.ifBlank { track.id }}."
            )
        cache[track.key] = Entry(option, now)
        return option
    }

    private companion object {
        // Addon stream URLs can carry expiring tokens; 10 minutes keeps repeat
        // opens (seeks, retries) cheap without pinning a dead URL for the queue.
        const val TTL_MS = 10L * 60 * 1000
    }
}

/**
 * Background audio for Musicbox: one ExoPlayer playlist backs both the system
 * media session (notification, media keys, Bluetooth) and the queue's
 * next/previous. Video playback in [com.streamline.cinema.PlayerActivity]
 * requests audio focus, so opening a movie pauses the music instead of mixing.
 */
@OptIn(UnstableApi::class)
class MusicPlaybackService : MediaSessionService() {

    private var player: ExoPlayer? = null
    private var session: MediaSession? = null

    private lateinit var settingsStore: SettingsStore
    private lateinit var profileStore: ProfileStore
    private lateinit var musicHistory: MusicHistoryStore
    private var sponsorBlock: SponsorBlockClient? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    // One track's skip data, replaced on every media-item transition.
    private var sponsorVideoId: String? = null
    private var sponsorFetched = false
    private var sponsorSegments: List<SponsorSegment> = emptyList()
    private var sponsorPolling = false

    // The profile that started this queue owns its history, even if another
    // profile is picked while the audio still runs.
    private var sessionProfileId: String? = null
    private var lastCheckpointAt = 0L

    override fun onCreate() {
        super.onCreate()
        val container = (application as StreamLineApp).container
        settingsStore = container.settingsStore
        profileStore = container.profileStore
        musicHistory = container.musicHistory
        sponsorBlock = container.sponsorBlock
        val repository = container.repository
        // Streaming client: a track is read for its whole length, so the
        // call-timeout-bounded API client would abort it mid-song.
        val upstream = ResolvingDataSource.Factory(
            OkHttpDataSource.Factory(Http.streamingClient),
            MusicStreamResolver(repository)
        )
        val budget = settingsStore.settings.value.audioCacheBudget
        // The cache wraps the resolver, so entries are keyed by each track's
        // stable cache key and a hit never touches the expiring addon link.
        val dataSourceFactory: DataSource.Factory = if (budget.maxBytes > 0L) {
            CacheDataSource.Factory()
                .setCache(MusicAudioCache.open(this, budget.maxBytes))
                .setUpstreamDataSourceFactory(upstream)
                .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        } else {
            upstream
        }
        val exo = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus = */ true
            )
            .setHandleAudioBecomingNoisy(true)
            .build()
        // Keep the queue downloading with the screen off.
        exo.setWakeMode(C.WAKE_MODE_NETWORK)
        // Music catalogs are audio-first; a video track must never keep a
        // hardware decoder busy behind the session.
        exo.trackSelectionParameters = exo.trackSelectionParameters
            .buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true)
            .build()
        exo.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                onTrackChanged(mediaItem)
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                val current = player ?: return
                if (isPlaying) {
                    lastCheckpointAt = SystemClock.elapsedRealtime()
                    return
                }
                if (current.mediaItemCount > 0 &&
                    current.playbackState != Player.STATE_ENDED
                ) {
                    persistSession(current, current.currentPosition)
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                val current = player ?: return
                // A finished queue resumes from the top of its last track rather
                // than from the final second.
                if (playbackState == Player.STATE_ENDED) persistSession(current, 0L)
            }
        })
        player = exo
        session = MediaSession.Builder(this, exo)
            .setSessionActivity(contentIntent())
            .build()
        setMediaNotificationProvider(DefaultMediaNotificationProvider.Builder(this).build())
        startSponsorPolling()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    private fun onTrackChanged(mediaItem: MediaItem?) {
        sponsorSegments = emptyList()
        sponsorFetched = false
        sponsorVideoId = mediaItem?.mediaId
            ?.substringAfterLast('|')
            ?.let(SponsorBlock::videoIdOf)
        val exo = player ?: return
        val track = mediaItem?.localConfiguration?.uri?.let(MusicMedia::trackFromUri)
        if (track != null) musicHistory.recordPlayed(profileId(), track)
        lastCheckpointAt = SystemClock.elapsedRealtime()
        persistSession(exo, exo.currentPosition)
    }

    /** The queue's owner is fixed when its first track starts. */
    private fun profileId(): String =
        sessionProfileId ?: profileStore.active.value.id.also { sessionProfileId = it }

    private fun persistSession(exo: ExoPlayer, positionMs: Long) {
        val queue = ArrayList<MusicTrack>(exo.mediaItemCount)
        for (index in 0 until exo.mediaItemCount) {
            val uri = exo.getMediaItemAt(index).localConfiguration?.uri ?: continue
            queue += MusicMedia.trackFromUri(uri) ?: continue
        }
        if (queue.isEmpty()) return
        musicHistory.saveSession(
            profileId = profileId(),
            queue = queue,
            index = exo.currentMediaItemIndex,
            positionMs = positionMs
        )
    }

    private fun startSponsorPolling() {
        if (sponsorPolling) return
        sponsorPolling = true
        scope.launch {
            while (isActive) {
                delay(SPONSOR_POLL_MS)
                val exo = player ?: continue
                checkpointSession(exo)
                pollSponsorSegment(exo)
            }
        }
    }

    /** Saves the playhead every [SESSION_CHECKPOINT_MS] while audio is moving. */
    private fun checkpointSession(exo: ExoPlayer) {
        if (!exo.isPlaying || exo.mediaItemCount == 0) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastCheckpointAt < SESSION_CHECKPOINT_MS) return
        lastCheckpointAt = now
        persistSession(exo, exo.currentPosition)
    }

    private fun pollSponsorSegment(exo: ExoPlayer) {
        val videoId = sponsorVideoId ?: return
        if (!settingsStore.settings.value.sponsorBlock) return
        // Fetched lazily so enabling the setting mid-track works, and so a
        // queue that never starts a track costs no lookup.
        if (!sponsorFetched) {
            fetchSponsorSegments(videoId)
            return
        }
        if (!exo.isPlaying) return
        val position = exo.currentPosition
        val segment = sponsorSegments.firstOrNull { it.contains(position) } ?: return
        if (segment.endMs - position < MIN_SKIP_MS) return
        exo.seekTo(segment.endMs)
    }

    private fun fetchSponsorSegments(videoId: String) {
        val client = sponsorBlock ?: return
        sponsorFetched = true
        scope.launch {
            val segments = client.segments(videoId)
            // The transition may have moved on while the lookup was in flight.
            if (sponsorVideoId == videoId) sponsorSegments = segments
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        val exo = player
        // Swiping the task away should not cut music mid-album; a paused empty
        // queue has nothing to keep the service alive for.
        if (exo == null || !exo.playWhenReady || exo.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        player?.let { exo ->
            val position = if (exo.playbackState == Player.STATE_ENDED) 0L else exo.currentPosition
            persistSession(exo, position)
        }
        musicHistory.flush()
        session?.let {
            it.player.release()
            it.release()
        }
        session = null
        player = null
        sponsorBlock = null
        MusicAudioCache.close()
        super.onDestroy()
    }

    private fun contentIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private companion object {
        const val SPONSOR_POLL_MS = 500L
        // A segment about to end is not worth a visible seek.
        const val MIN_SKIP_MS = 1_000L
        const val SESSION_CHECKPOINT_MS = 15_000L
    }
}
