package com.robbe1912.hermeslite.push

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.robbe1912.hermeslite.App
import com.robbe1912.hermeslite.MainActivity
import com.robbe1912.hermeslite.R

/**
 * Gets the messages of Firebase.
 *
 * The relay on the gateway server sends one data message when a cron run ends. The message has
 * no content. The app shows a notification that opens the run.
 */
class PushService : FirebaseMessagingService() {
    @Suppress("OVERRIDE_DEPRECATION") // This Firebase version still calls this method.
    override fun onNewToken(token: String) {
        App.instance.push.onNewToken(token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val data = message.data
        val jobId = data["job_id"]?.takeIf { it.isNotBlank() } ?: return
        val name = data["name"]?.takeIf { it.isNotBlank() } ?: jobId
        val failed = data["status"] in FAILED

        val manager = NotificationManagerCompat.from(this)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.channel_cron), NotificationManager.IMPORTANCE_DEFAULT),
        )
        val intent = WakeIntent.intent(this, App.instance.settings.installId.value, jobId, name, data["session_id"])
        val open = PendingIntent.getActivity(this, jobId.hashCode(), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(name)
            .setContentText(getString(if (failed) R.string.run_failed else R.string.run_ended))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        val allowed = Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (allowed && manager.areNotificationsEnabled()) manager.notify(jobId.hashCode(), notification)
    }

    private companion object {
        const val CHANNEL = "cron_runs"
        val FAILED = setOf("failed", "error", "timeout")
    }
}
