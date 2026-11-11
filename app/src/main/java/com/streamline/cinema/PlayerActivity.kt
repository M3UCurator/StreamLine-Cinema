package com.streamline.cinema

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.streamline.cinema.data.CatalogItem
import com.streamline.cinema.data.DrmConfig
import com.streamline.cinema.data.LiveChannel
import com.streamline.cinema.data.PlaybackHandoff
import com.streamline.cinema.data.StreamMeta
import com.streamline.cinema.data.StreamUrls
import com.streamline.cinema.ui.CinemaTheme
import com.streamline.cinema.ui.player.PlayerScreen
import org.json.JSONObject

class PlayerActivity : CinemaActivity() {

    companion object {
        private const val EXTRA_ITEM = "streamline.item"
        private const val EXTRA_SEASON = "streamline.season"
        private const val EXTRA_EPISODE = "streamline.episode"
        private const val EXTRA_EPISODE_TITLE = "streamline.episode_title"
        private const val EXTRA_START_MS = "streamline.start_ms"
        private const val EXTRA_URL = "streamline.url"
        private const val EXTRA_TITLE = "streamline.title"
        private const val EXTRA_HEADERS = "streamline.headers"
        private const val EXTRA_DRM = "streamline.drm"
        private const val EXTRA_SOURCE_META = "streamline.source_meta"
        private const val EXTRA_CHANNELS_TOKEN = "streamline.channels_token"
        private const val EXTRA_CHANNEL_INDEX = "streamline.channel_index"

        fun launch(
            context: Context,
            item: CatalogItem,
            season: Int? = null,
            episode: Int? = null,
            episodeTitle: String? = null,
            startPositionMs: Long? = null,
            url: String? = null
        ) {
            val intent = Intent(context, PlayerActivity::class.java).apply {
                putExtra(EXTRA_ITEM, item.toJson().toString())
                season?.let { putExtra(EXTRA_SEASON, it) }
                episode?.let { putExtra(EXTRA_EPISODE, it) }
                episodeTitle?.let { putExtra(EXTRA_EPISODE_TITLE, it) }
                startPositionMs?.let { putExtra(EXTRA_START_MS, it) }
                url?.let { putExtra(EXTRA_URL, it) }
            }
            context.startActivity(intent)
        }

        fun launchUrl(
            context: Context,
            url: String,
            title: String,
            headers: Map<String, String> = emptyMap(),
            channels: List<LiveChannel> = emptyList(),
            channelIndex: Int = -1,
            drm: DrmConfig? = null,
            meta: StreamMeta = StreamMeta()
        ) {
            // `url|Header=value&Header2=value` carries token/CDN headers in one string.
            val parsed = StreamUrls.parse(url)
            val intent = Intent(context, PlayerActivity::class.java).apply {
                putExtra(EXTRA_URL, parsed.url)
                putExtra(EXTRA_TITLE, title)
                drm?.let { putExtra(EXTRA_DRM, it.toJson().toString()) }
                putExtra(EXTRA_SOURCE_META, meta.toJson().toString())
                val merged = StreamUrls.mergeHeaders(headers, parsed.headers)
                if (merged.isNotEmpty()) putExtra(EXTRA_HEADERS, JSONObject(merged).toString())
                if (channels.isNotEmpty()) {
                    putExtra(EXTRA_CHANNELS_TOKEN, PlaybackHandoff.store(channels))
                    putExtra(EXTRA_CHANNEL_INDEX, channelIndex)
                }
            }
            context.startActivity(intent)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        enterImmersive()

        val item = intent.getStringExtra(EXTRA_ITEM)?.let { raw ->
            runCatching { CatalogItem.fromJson(JSONObject(raw)) }.getOrNull()
        }
        val directUrl = intent.getStringExtra(EXTRA_URL)
        val directHeaders = intent.getStringExtra(EXTRA_HEADERS)
            ?.let { raw -> runCatching { JSONObject(raw) }.getOrNull() }
            ?.let { json -> json.keys().asSequence().associateWith { json.optString(it) } }
            .orEmpty()
        val directDrm = intent.getStringExtra(EXTRA_DRM)?.let { raw ->
            runCatching { DrmConfig.fromJson(JSONObject(raw)) }.getOrNull()
        }
        val directMeta = StreamMeta.fromJson(
            intent.getStringExtra(EXTRA_SOURCE_META)?.let { raw ->
                runCatching { JSONObject(raw) }.getOrNull()
            }
        )
        val fallbackTitle = intent.getStringExtra(EXTRA_TITLE) ?: item?.title ?: "StreamLine Cinema"
        val season = intent.getIntExtra(EXTRA_SEASON, 0).takeIf { it > 0 }
        val episode = intent.getIntExtra(EXTRA_EPISODE, 0).takeIf { it > 0 }
        val episodeTitle = intent.getStringExtra(EXTRA_EPISODE_TITLE)
        val startPositionMs = intent.getLongExtra(EXTRA_START_MS, -1L).takeIf { it >= 0 }
        val channels = PlaybackHandoff.lookup(intent.getStringExtra(EXTRA_CHANNELS_TOKEN))
        val channelIndex = intent.getIntExtra(EXTRA_CHANNEL_INDEX, -1)

        setContent {
            CinemaTheme {
                PlayerScreen(
                    item = item,
                    season = season,
                    episode = episode,
                    episodeTitle = episodeTitle,
                    startPositionOverrideMs = startPositionMs,
                    directUrl = directUrl,
                    directHeaders = directHeaders,
                    directDrm = directDrm,
                    directMeta = directMeta,
                    fallbackTitle = fallbackTitle,
                    channels = channels,
                    channelIndex = channelIndex,
                    onExit = { finish() }
                )
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enterImmersive()
    }

    private fun enterImmersive() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }
}
