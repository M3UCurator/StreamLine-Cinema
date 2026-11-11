package com.streamline.cinema

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log
import android.widget.Toast

/**
 * Receives PackageInstaller session status. The system asks for the install
 * confirmation through [PackageInstaller.STATUS_PENDING_USER_ACTION]; the
 * embedded intent must be launched for the update to proceed.
 */
class UpdateInstallReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_STATUS = "com.streamline.cinema.UPDATE_INSTALL_STATUS"
        private const val TAG = "StreamLine:update"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val prompt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT)
                }
                prompt?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (prompt != null) {
                    context.startActivity(prompt)
                } else {
                    Log.w(TAG, "installer prompt had no intent")
                }
            }
            PackageInstaller.STATUS_SUCCESS -> Log.d(TAG, "installer reported success")
            else -> {
                val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                    ?: "status $status"
                Log.w(TAG, "installer failed: $message")
                Toast.makeText(context, "Update install failed: $message", Toast.LENGTH_LONG).show()
            }
        }
    }
}
