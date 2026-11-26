package com.streamline.cinema.ui.player

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log

/**
 * The Wi-Fi driver drops Ethernet group-addressed frames unless a Wi-Fi
 * multicast lock is held, so a UDP channel that works on a wired box receives
 * nothing on Wi-Fi without this. The lock is acquired per tune and released by
 * the same effect, and a device without Wi-Fi (or an OEM that refuses the
 * call) just logs: wired playback never needed the lock.
 */
internal class MulticastLock(context: Context) {

    private val lock: WifiManager.MulticastLock? = runCatching {
        val wifi = context.applicationContext
            .getSystemService(Context.WIFI_SERVICE) as? WifiManager
        wifi?.createMulticastLock("StreamLine:multicast")
            ?.apply { setReferenceCounted(false) }
    }.onFailure {
        Log.w("StreamLine:player", "multicast lock unavailable", it)
    }.getOrNull()

    fun acquire() {
        val target = lock ?: return
        runCatching { target.acquire() }
            .onFailure { Log.w("StreamLine:player", "multicast lock not acquired", it) }
    }

    fun release() {
        val target = lock ?: return
        runCatching { target.release() }
            .onFailure { Log.w("StreamLine:player", "multicast lock not released", it) }
    }
}
