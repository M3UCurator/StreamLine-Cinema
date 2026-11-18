package com.streamline.cinema.ui

import android.content.ComponentName
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.streamline.cinema.data.MusicTrack
import com.streamline.cinema.player.MusicPlaybackService
import com.streamline.cinema.player.toMediaItem

/**
 * UI-side handle on [MusicPlaybackService]. The connection only lives as long as
 * the Musicbox composable is on screen; the service owns the actual queue, so
 * playback survives leaving the tab and the controller simply reattaches to the
 * running session on return.
 */
@Stable
class MusicPlaybackConnection internal constructor(private val context: Context) {

    var currentKey: String? by mutableStateOf(null)
        private set
    var isPlaying: Boolean by mutableStateOf(false)
        private set
    var error: String? by mutableStateOf(null)
        private set

    private var controller: MediaController? = null
    private var connecting: ListenableFuture<MediaController>? = null
    private var released = false
    private var pending: QueuedStart? = null

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            currentKey = player.currentMediaItem?.mediaId
            isPlaying = player.isPlaying
        }

        override fun onPlayerError(playbackError: PlaybackException) {
            error = playbackError.message ?: "Playback failed."
        }
    }

    // A profile switch stops the service on purpose; without this the header
    // would keep showing the old profile's PLAY button over a dead session.
    private val sessionListener = object : MediaController.Listener {
        override fun onDisconnected(controller: MediaController) {
            if (controller !== this@MusicPlaybackConnection.controller) return
            controller.removeListener(listener)
            this@MusicPlaybackConnection.controller = null
            currentKey = null
            isPlaying = false
        }
    }

    fun connect() {
        if (released || controller != null || connecting != null) return
        val token = SessionToken(
            context,
            ComponentName(context, MusicPlaybackService::class.java)
        )
        val future = MediaController.Builder(context, token)
            .setListener(sessionListener)
            .buildAsync()
        connecting = future
        future.addListener(
            {
                connecting = null
                val built = runCatching { future.get() }.getOrNull()
                when {
                    built == null -> if (!released) {
                        error = "Music playback is unavailable."
                    }
                    released -> built.release()
                    else -> attach(built)
                }
            },
            ContextCompat.getMainExecutor(context)
        )
    }

    /**
     * Replaces the queue with [queue] and starts at [startIndex], optionally at
     * [startPositionMs] for a resumed session. A row tap is also "play this
     * album from here", which is what the session next/previous keys then walk
     * through.
     */
    fun play(queue: List<MusicTrack>, startIndex: Int, startPositionMs: Long = 0L) {
        if (queue.isEmpty()) return
        error = null
        val current = usableController()
        if (current == null) {
            pending = QueuedStart(queue, startIndex, startPositionMs)
            connect()
            return
        }
        start(current, queue, startIndex, startPositionMs)
    }

    fun toggle() {
        val current = usableController() ?: return
        when {
            current.isPlaying -> current.pause()
            current.mediaItemCount == 0 -> Unit
            current.playbackState == Player.STATE_IDLE -> {
                current.prepare()
                current.play()
            }
            else -> current.play()
        }
    }

    fun release() {
        released = true
        pending = null
        val current = controller
        controller = null
        current?.let {
            it.removeListener(listener)
            it.release()
        }
        connecting?.let { MediaController.releaseFuture(it) }
        connecting = null
    }

    /**
     * The system can stop an idle session service; the old controller then
     * survives as a disconnected handle, so drop it and let the next action
     * reopen the session instead of throwing.
     */
    private fun usableController(): MediaController? {
        val current = controller ?: return null
        if (current.isConnected) return current
        current.removeListener(listener)
        controller = null
        return null
    }

    private fun attach(controller: MediaController) {
        this.controller = controller
        controller.addListener(listener)
        currentKey = controller.currentMediaItem?.mediaId
        isPlaying = controller.isPlaying
        pending?.let { queued ->
            pending = null
            start(controller, queued.queue, queued.index, queued.positionMs)
        }
    }

    private fun start(
        controller: MediaController,
        queue: List<MusicTrack>,
        startIndex: Int,
        startPositionMs: Long
    ) {
        val items = queue.map { it.toMediaItem() }
        if (items.isEmpty()) return
        error = null
        controller.setMediaItems(
            items,
            startIndex.coerceIn(0, items.lastIndex),
            startPositionMs.coerceAtLeast(0L)
        )
        controller.prepare()
        controller.play()
    }

    private data class QueuedStart(
        val queue: List<MusicTrack>,
        val index: Int,
        val positionMs: Long
    )
}

@Composable
fun rememberMusicPlayback(): MusicPlaybackConnection {
    val context = LocalContext.current.applicationContext
    val connection = remember(context) { MusicPlaybackConnection(context) }
    DisposableEffect(connection) {
        connection.connect()
        onDispose { connection.release() }
    }
    return connection
}
