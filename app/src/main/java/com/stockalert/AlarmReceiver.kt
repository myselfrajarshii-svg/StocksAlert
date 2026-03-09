package com.stockalert

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import java.util.Calendar
import java.util.TimeZone

/**
 * Fires when AlarmManager triggers at a scheduled IST time.
 * 1. Checks it's a weekday (NSE: Mon–Fri)
 * 2. Fetches prices from Yahoo Finance v8 chart API
 * 3. Posts a notification with sound + vibration
 * 4. Reschedules for the next day
 */
class AlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != AlarmScheduler.ACTION_STOCK_ALERT) return

        val timeLabel  = intent.getStringExtra(AlarmScheduler.EXTRA_TIME_LABEL) ?: "?"
        val alarmIndex = intent.getIntExtra(AlarmScheduler.EXTRA_ALARM_INDEX, 0)

        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "StockAlert:Fetch")
        wl.acquire(60_000L)

        val pending = goAsync()

        Thread {
            try {
                if (isMarketDay()) {
                    fetchAndNotify(context, timeLabel)
                }
                AlarmScheduler.scheduleAll(context)   // reschedule tomorrow
            } finally {
                wl.release()
                pending.finish()
            }
        }.start()
    }

    private fun isMarketDay(): Boolean {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("Asia/Kolkata"))
        val dow = cal.get(Calendar.DAY_OF_WEEK)
        return dow != Calendar.SATURDAY && dow != Calendar.SUNDAY
    }

    private fun fetchAndNotify(context: Context, timeLabel: String) {
        val symbols = PrefsManager.getStocks(context)

        // Fetch — returns Map<originalSymbol, StockPrice?>
        val prices = StockFetcher.fetchPrices(symbols)

        // Build lines using the SAME key that was passed in (no case transformation needed)
        val lines = symbols.map { sym -> StockFetcher.formatLine(sym, prices[sym]) }

        val displayTime = toDisplayTime(timeLabel)
        val notifId     = timeLabel.replace(":", "").toIntOrNull() ?: 1030

        NotificationHelper.postPriceAlert(
            context        = context,
            timeLabel      = displayTime,
            lines          = lines,
            notificationId = notifId
        )
    }

    /** "15:00" → "3:00 PM",  "10:30" → "10:30 AM" */
    private fun toDisplayTime(timeStr: String): String {
        val parts  = timeStr.split(":")
        val hour   = parts.getOrNull(0)?.toIntOrNull() ?: return timeStr
        val minute = parts.getOrNull(1)?.toIntOrNull() ?: 0
        val amPm   = if (hour < 12) "AM" else "PM"
        val h12    = when { hour == 0 -> 12; hour > 12 -> hour - 12; else -> hour }
        return "%d:%02d %s".format(h12, minute, amPm)
    }
}
