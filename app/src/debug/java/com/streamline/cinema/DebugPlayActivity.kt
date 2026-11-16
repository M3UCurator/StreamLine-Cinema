package com.streamline.cinema

import android.app.Activity
import android.os.Bundle
import com.streamline.cinema.data.AddonSource
import com.streamline.cinema.data.CatalogItem
import com.streamline.cinema.data.DrmConfig
import org.json.JSONObject

class DebugPlayActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Fixture harness: register the local addon without walking Settings.
        // Write through the container's store: a second SettingsStore instance
        // would persist the file but leave the app's live state untouched.
        intent.getStringExtra("addon")?.takeIf { it.isNotBlank() }?.let { url ->
            val addon = AddonSource(url = url, name = "Parse Fixture")
            (application as StreamLineApp).container.settingsStore.update { settings ->
                val withAddon =
                    if (settings.addons.any { it.id == addon.id }) settings
                    else settings.copy(addons = settings.addons + addon)
                // `--ez picker true` keeps the source picker up instead of
                // playing the first discovered source.
                if (intent.getBooleanExtra("picker", false)) {
                    withAddon.copy(autoPlaySearchedStream = false)
                } else {
                    withAddon
                }
            }
        }
        val itemJson = intent.getStringExtra("item")
        if (!itemJson.isNullOrBlank()) {
            val item = CatalogItem.fromJson(JSONObject(itemJson))
            PlayerActivity.launch(
                this,
                item,
                season = intent.getIntExtra("season", 0).takeIf { it > 0 },
                episode = intent.getIntExtra("episode", 0).takeIf { it > 0 },
                startPositionMs = intent.getLongExtra("start_ms", -1L).takeIf { it >= 0 },
                url = intent.getStringExtra("url")
            )
            finish()
            return
        }
        val url = intent.getStringExtra("url") ?: intent.dataString
        if (url.isNullOrBlank()) {
            finish()
            return
        }
        val title = intent.getStringExtra("title") ?: "Debug Stream"
        val drm = intent.getStringExtra("drm")?.let { raw ->
            DrmConfig.fromJson(JSONObject(raw))
        }
        PlayerActivity.launchUrl(this, url, title, drm = drm)
        finish()
    }
}
