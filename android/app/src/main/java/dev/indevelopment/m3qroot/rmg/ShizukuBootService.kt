/*
 * Adapted from Root-My-Galaxy-Extended's ShizukuBootService (dev.busung.s25uroot)
 * by igorcv88, which derives from BuSung-dev/Root-My-Galaxy. Apache License 2.0.
 * Adapted to SamSU: the transport chain lives in ShizukuAutoStart, the Wi-Fi
 * wait is handled inside the local ADB session, and the result is recorded for
 * the activity card instead of driving a Compose settings page.
 */
package dev.indevelopment.m3qroot.rmg

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import dev.indevelopment.m3qroot.MainActivity
import dev.indevelopment.m3qroot.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Boot-time Shizuku start.
 *
 * Android gives a boot receiver only a few seconds, and starting Shizuku can
 * need a full local-ADB bring-up, so the work runs in a foreground service with
 * a live notification. The service does nothing at all unless the user turned
 * the option on, and it never touches the exploit path.
 */
class ShizukuBootService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (job?.isActive == true) return START_NOT_STICKY
        val notification = buildNotification(
            getString(R.string.shizuku_notification_checking),
        )
        try {
            startForegroundCompat(notification)
        } catch (error: RuntimeException) {
            // Foreground start can be refused depending on device state; the
            // user can always start Shizuku from the card instead.
            Log.w(TAG, "Foreground start refused", error)
            ShizukuPrefs.setLastResult(
                this,
                getString(R.string.shizuku_last_result_blocked),
            )
            stopSelf()
            return START_NOT_STICKY
        }

        job = scope.launch {
            val outcome = ShizukuAutoStart.start(applicationContext) { line -> Log.i(TAG, line) }
            ShizukuPrefs.setLastResult(applicationContext, describe(outcome))
            notify(
                buildNotification(
                    if (outcome.started) {
                        getString(R.string.shizuku_notification_started, outcome.method)
                    } else {
                        getString(R.string.shizuku_notification_failed, outcome.detail)
                    },
                ),
            )
            Log.i(TAG, "Auto-start outcome: ${describe(outcome)}")
            delay(NOTIFICATION_LINGER_MILLIS)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun describe(outcome: ShizukuStartOutcome): String =
        if (outcome.started) {
            getString(R.string.shizuku_last_result_started, outcome.method)
        } else {
            getString(R.string.shizuku_last_result_failed, outcome.detail)
        }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_refresh)
            .setContentTitle(getString(R.string.shizuku_notification_title))
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun notify(notification: Notification) {
        runCatching {
            getSystemService(NotificationManager::class.java)
                .notify(NOTIFICATION_ID, notification)
        }
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.shizuku_notification_channel),
            NotificationManager.IMPORTANCE_LOW,
        )
        channel.setShowBadge(false)
        manager.createNotificationChannel(channel)
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            @Suppress("DEPRECATION")
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        private const val TAG = "SamSU-ShizukuBoot"
        private const val CHANNEL_ID = "shizuku_boot"
        private const val NOTIFICATION_ID = 43499
        private const val NOTIFICATION_LINGER_MILLIS = 6_000L

        /**
         * Starts the service only when the user asked for it, Shizuku is not
         * already up, and the app is allowed to run a foreground service.
         */
        fun startIfConfigured(context: Context) {
            if (!ShizukuPrefs.startOnBoot(context)) return
            if (ShizukuBinderWaitHelper.isShizukuRunning()) return
            ContextCompat.startForegroundService(
                context,
                Intent(context, ShizukuBootService::class.java),
            )
        }
    }
}

/** Non-suspending Binder probe, so the companion can stay a plain function. */
private object ShizukuBinderWaitHelper {
    fun isShizukuRunning(): Boolean = dev.indevelopment.m3qroot.ShizukuBridge.isRunning()
}
