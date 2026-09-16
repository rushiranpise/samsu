/*
 * Adapted from Root-My-Galaxy-Extended's AutoRootService / AutoRootRunner
 * (dev.busung.s25uroot) by igorcv88, which derives from BuSung-dev/Root-My-Galaxy.
 * Apache License 2.0. Adapted to SamSU: one foreground service, no separate
 * executor process, and the gates live in AutoRoot.kt.
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
import dev.indevelopment.m3qroot.AutoRootSession
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
 * Unattended root at boot.
 *
 * This is the one place in SamSU where a kernel write happens without a press,
 * so it is fenced on every side: the user has to enable it, the device has to
 * match a bundled payload, KernelSU Manager has to be the required version, the
 * boot-settle gate has to have elapsed, the once-per-boot attempt has to be
 * unclaimed, and — the important one — the exact artifacts about to run must
 * already have been verified on this firmware by an earlier successful run.
 */
class AutoRootService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (job?.isActive == true) return START_NOT_STICKY
        if (!AutoRoot.isEnabled(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            startForegroundCompat(
                buildNotification(getString(R.string.autoroot_notification_starting)),
            )
        } catch (error: RuntimeException) {
            Log.w(TAG, "Foreground start refused", error)
            AutomationPrefs.setLastAutoRootResult(
                this,
                getString(R.string.autoroot_last_result_blocked),
            )
            stopSelf()
            return START_NOT_STICKY
        }

        val history = RunHistoryStore(this)
        val entry = history.begin("Auto root", null, false)
        val lines = StringBuilder()
        job = scope.launch {
            var outcome = AutoRootOutcome(false, false, "not started")
            try {
                val session = AutoRootSession.create(this@AutoRootService) { line ->
                    lines.append(line).append('\n')
                    Log.i(TAG, line)
                }
                outcome = AutoRoot.run(
                    context = this@AutoRootService,
                    session = session,
                    onLog = { line ->
                        lines.append(line).append('\n')
                        Log.i(TAG, line)
                    },
                    onProgress = { progress ->
                        notify(buildNotification(progress))
                    },
                )
            } catch (error: Throwable) {
                lines.append("auto root failed: ").append(error.message).append('\n')
                outcome = AutoRootOutcome(false, false, error.message ?: "unexpected failure")
            }

            val summary = describe(outcome)
            AutomationPrefs.setLastAutoRootResult(applicationContext, summary)
            runCatching {
                history.finish(
                    entry,
                    if (outcome.rooted) RunResult.Succeeded else RunResult.Failed,
                    lines.toString(),
                )
            }
            notify(buildNotification(summary))
            Log.i(TAG, "Auto Root outcome: $summary")
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

    private fun describe(outcome: AutoRootOutcome): String = getString(
        when {
            outcome.rooted -> R.string.autoroot_last_result_rooted
            outcome.ran -> R.string.autoroot_last_result_ran_not_rooted
            else -> R.string.autoroot_last_result_skipped
        },
        outcome.detail,
    )

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_power)
            .setContentTitle(getString(R.string.autoroot_notification_title))
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
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.autoroot_notification_channel),
            NotificationManager.IMPORTANCE_LOW,
        )
        channel.setShowBadge(false)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
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
        private const val TAG = "SamSU-AutoRoot"
        private const val CHANNEL_ID = "auto_root"
        private const val NOTIFICATION_ID = 43500
        private const val NOTIFICATION_LINGER_MILLIS = 8_000L

        /** Boot-time entry point; does nothing unless the user enabled it. */
        fun startIfConfigured(context: Context) {
            if (!AutoRoot.isEnabled(context)) return
            ContextCompat.startForegroundService(
                context,
                Intent(context, AutoRootService::class.java),
            )
        }
    }
}
