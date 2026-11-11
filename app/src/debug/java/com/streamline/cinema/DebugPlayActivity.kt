package com.streamline.cinema

import android.app.Activity
import android.os.Bundle
import com.streamline.cinema.data.CatalogItem
import org.json.JSONObject

class DebugPlayActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
        PlayerActivity.launchUrl(this, url, title)
        finish()
    }
}
