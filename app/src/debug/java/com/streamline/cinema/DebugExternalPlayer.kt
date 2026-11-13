package com.streamline.cinema

import android.app.Activity
import android.os.Bundle
import android.util.Log

/**
 * Debug-only stand-in for an external video player. It declares ACTION_VIEW
 * handlers for the stream MIME types the app hands off, so a fixture run can
 * read back the exact data URI, MIME type and `headers` extra the chooser got.
 */
class DebugExternalPlayer : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val extras = intent.extras ?: Bundle.EMPTY
        val headers = extras.getStringArray("headers")?.toList() ?: emptyList()
        Log.i(
            "StreamLine:ext",
            "dat=${intent.dataString} typ=${intent.type} action=${intent.action} " +
                "headers=${headers.joinToString(",")}"
        )
        finish()
    }
}
