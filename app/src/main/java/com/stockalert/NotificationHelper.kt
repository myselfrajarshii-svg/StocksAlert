package com.stockalert

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.net.Uri
import androidx.core.app.NotificationCompat

object NotificationHelper {

    // Channel ID is made dynamic per sound so changing sound forces a new channel
    private const val CHANNEL_BASE = "stock_price_alerts"

    /**
     * (Re)creates the notification channel with the currently saved sound.
     * Android does not allow updating sound on an existing channel, so we use
     * a versioned channel ID — old ones are cleaned up automatically.
     */
    fun createChannel(context: Context) {
        val soundUri  = Uri.parse(PrefsManager.getSoundUri(context))
        val channelId = currentChannelId(context)
        val nm        = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // Remove all old versioned channels
        nm.notificationChannels
            .filter { it.id.startsWith(CHANNEL_BASE) && it.id != channelId }
            .forEach { nm.deleteNotificationChannel(it.id) }

        if (nm.getNotificationChannel(channelId) != null) return  // already exists

        val audioAttr = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

        val channel = NotificationChannel(
            channelId,
            "Stock Price Alerts",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description     = "Scheduled NSE stock price alerts"
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 300, 200, 300)
            enableLights(true)
            setSound(soundUri, audioAttr)
        }

        nm.createNotificationChannel(channel)
    }

    fun postPriceAlert(
        context: Context,
        timeLabel: String,
        lines: List<String>,
        notificationId: Int = System.currentTimeMillis().toInt()
    ) {
        val nm        = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channelId = currentChannelId(context)
        val soundUri  = Uri.parse(PrefsManager.getSoundUri(context))

        val tapIntent = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title    = "📈 Stock Alert — $timeLabel IST"
        val bodyText = lines.joinToString("\n")

        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(lines.firstOrNull() ?: "Prices updated")
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText(bodyText)
                    .setBigContentTitle(title)
                    .setSummaryText("${lines.size} stocks • NSE")
            )
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setContentIntent(tapIntent)
            .setAutoCancel(true)
            .setSound(soundUri)
            .setVibrate(longArrayOf(0, 300, 200, 300))
            .build()

        nm.notify(notificationId, notification)
    }

    /** Channel ID encodes a hash of the sound URI so each new sound = new channel */
    fun currentChannelId(context: Context): String {
        val hash = PrefsManager.getSoundUri(context).hashCode().and(0xFFFF)
        return "${CHANNEL_BASE}_$hash"
    }
}
