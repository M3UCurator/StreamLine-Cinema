package com.streamline.cinema.data

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.streamline.cinema.MainActivity
import com.streamline.cinema.R

/**
 * Keeps this process out of the cached state while another app reads the
 * loopback relay from [NativeStreamProxy]. A hand-off pauses PlayerActivity
 * rather than hiding the stream behind a live service, so without this the OS
 * is free to freeze or reclaim the process the moment the external player
 * covers the screen; the relay then stops answering and VLC/mpv stall mid-play.
 * Started when a hand-off needs the relay, stopped when PlayerActivity returns
 * to the front (or the task is removed).
 */
class RelayService : Service() {

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "External player relay",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Shown while another player streams through this app"
                }
            )
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("Streaming in another player")
            .setContentText("Keeping the on-device relay running. It stops when you return to StreamLine.")
            .setContentIntent(open)
            .setOngoing(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            } else {
                0
            }
        )
        // The relay lives in this process and holds no state worth restoring:
        // a restart would find no registered streams.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL_ID = "stream-relay"
        private const val NOTIFICATION_ID = 1067

        /**
         * Called from a visible activity right before the hand-off launches, so
         * the foreground-service start window is always satisfied.
         */
        fun start(context: Context) {
            runCatching {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, RelayService::class.java)
                )
            }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, RelayService::class.java)) }
        }
    }
}
