package com.streamline.cinema.data

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager

/**
 * Keeps the CPU and the Wi-Fi radio awake for the lifetime of a playback session.
 * `FLAG_KEEP_SCREEN_ON` only stops the display from sleeping; live HLS segments
 * still arrive over a radio that the OS is free to power down on idle.
 */
class PlaybackLocks private constructor(
    private val wakeLock: PowerManager.WakeLock?,
    private val wifiLock: WifiManager.WifiLock?
) {

    fun release() {
        runCatching { if (wakeLock?.isHeld == true) wakeLock.release() }
        runCatching { if (wifiLock?.isHeld == true) wifiLock.release() }
    }

    companion object {
        private const val TAG = "StreamLine:playback"

        fun acquire(context: Context): PlaybackLocks {
            val power = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            val wake = runCatching {
                power?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG)?.apply {
                    setReferenceCounted(false)
                    acquire()
                }
            }.getOrNull()

            val wifiManager = context.applicationContext
                .getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                WifiManager.WIFI_MODE_FULL_LOW_LATENCY
            } else {
                @Suppress("DEPRECATION")
                WifiManager.WIFI_MODE_FULL_HIGH_PERF
            }
            val wifi = runCatching {
                wifiManager?.createWifiLock(mode, TAG)?.apply {
                    setReferenceCounted(false)
                    acquire()
                }
            }.getOrNull()

            return PlaybackLocks(wake, wifi)
        }
    }
}
