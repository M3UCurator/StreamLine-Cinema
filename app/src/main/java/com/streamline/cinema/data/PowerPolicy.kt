package com.streamline.cinema.data

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.util.Log

/**
 * Doze can drop the sockets and buffers a paused or backgrounded player still
 * holds, so the exemption is asked for from the app and read back from the
 * system instead of being mirrored in settings.
 */
object PowerPolicy {
    private const val TAG = "StreamLine:power"

    fun isExempt(context: Context): Boolean = runCatching {
        context.getSystemService(PowerManager::class.java)
            ?.isIgnoringBatteryOptimizations(context.packageName) == true
    }.getOrDefault(false)

    /** Opens the system grant panel; falls back to the exemption list. */
    fun requestExemption(context: Context): Boolean {
        val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            .setData(Uri.parse("package:${context.packageName}"))
        return launch(context, direct) || openList(context)
    }

    /**
     * Android offers no API to revoke the exemption, so the off path sends the
     * viewer to the same system list where it can be undone.
     */
    fun openList(context: Context): Boolean =
        launch(context, Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) ||
            launch(
                context,
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(Uri.parse("package:${context.packageName}"))
            )

    private fun launch(context: Context, intent: Intent): Boolean {
        if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val opened = runCatching { context.startActivity(intent) }.isSuccess
        if (!opened) Log.w(TAG, "No system panel for ${intent.action}")
        return opened
    }
}
