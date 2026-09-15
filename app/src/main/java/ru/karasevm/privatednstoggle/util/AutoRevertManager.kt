package ru.karasevm.privatednstoggle.util

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ru.karasevm.privatednstoggle.PrivateDNSApp
import ru.karasevm.privatednstoggle.R
import ru.karasevm.privatednstoggle.service.AutoRevertReceiver
import ru.karasevm.privatednstoggle.ui.MainActivity
import ru.karasevm.privatednstoggle.util.PreferenceHelper.autoRevertDelaySeconds
import ru.karasevm.privatednstoggle.util.PreferenceHelper.autoRevertEnabled
import ru.karasevm.privatednstoggle.util.PreferenceHelper.autoRevertPendingMode
import ru.karasevm.privatednstoggle.util.PreferenceHelper.autoRevertPendingProvider
import ru.karasevm.privatednstoggle.util.PreferenceHelper.autoRevertTarget

/**
 * Schedules a delayed restore of the Private DNS setting and shows a notification with a live
 * countdown while the delay is running. If the user does not interact with the notification the
 * dns setting is restored when the countdown finishes.
 */
object AutoRevertManager {

    const val ACTION_AUTO_REVERT = "ru.karasevm.privatednstoggle.action.AUTO_REVERT"
    const val ACTION_KEEP = "ru.karasevm.privatednstoggle.action.AUTO_REVERT_KEEP"
    const val ACTION_RESTORE_NOW = "ru.karasevm.privatednstoggle.action.AUTO_REVERT_RESTORE_NOW"

    const val EXTRA_TARGET = "auto_revert_target"
    const val EXTRA_PREVIOUS_MODE = "auto_revert_previous_mode"
    const val EXTRA_PREVIOUS_PROVIDER = "auto_revert_previous_provider"

    private const val TAG = "AutoRevertManager"

    private const val CHANNEL_ID = "auto_revert"
    private const val NOTIFICATION_ID = 1

    private const val REQUEST_ALARM = 10
    private const val REQUEST_KEEP = 11
    private const val REQUEST_RESTORE_NOW = 12
    private const val REQUEST_OPEN_APP = 13

    private const val REFRESH_TILE_ACTION = "refresh_tile"

    /**
     * Drives the restore while the app process is alive. AlarmManager alone is not enough because
     * exact alarms need a special permission that is denied by default from Android 14, and the
     * inexact fallback can be deferred well past the deadline.
     */
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var revertJob: Job? = null

    /**
     * Serializes changes so two quick dns changes cannot interleave their pending restore state.
     */
    private val mutex = Mutex()

    /**
     * Creates the notification channel used by the auto-revert notification.
     */
    fun createNotificationChannel(context: Context) {
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.auto_revert_channel_name),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = context.getString(R.string.auto_revert_channel_description)
        }
        context.getSystemService(NotificationManager::class.java)
            .createNotificationChannel(channel)
    }

    /**
     * Schedules a restore of the dns setting after the configured delay, replacing any previously
     * scheduled restore. Does nothing when auto-revert is disabled, and cancels a pending restore
     * when the freshly applied state is one there is nothing left to restore from.
     *
     * @param newMode dns mode that was just applied
     * @param newProvider dns provider that was just applied
     * @param previousMode dns mode before the change, used by the "previous" target
     * @param previousProvider dns provider before the change
     */
    fun onDnsChanged(
        context: Context,
        newMode: String,
        newProvider: String?,
        previousMode: String?,
        previousProvider: String?
    ) {
        val appContext = context.applicationContext
        scope.launch {
            mutex.withLock {
                handleDnsChanged(appContext, newMode, newProvider, previousMode, previousProvider)
            }
        }
    }

    private suspend fun handleDnsChanged(
        context: Context,
        newMode: String,
        newProvider: String?,
        previousMode: String?,
        previousProvider: String?
    ) {
        val preferences = PreferenceHelper.defaultPreference(context)
        if (!preferences.autoRevertEnabled) {
            return
        }

        val target = preferences.autoRevertTarget
        val delaySeconds = preferences.autoRevertDelaySeconds
        if (delaySeconds <= 0) {
            return
        }

        // The user reached the state the pending restore would apply, so it is obsolete.
        val pendingMode = preferences.autoRevertPendingMode
        val pendingProvider = preferences.autoRevertPendingProvider.ifEmpty { null }
        if (pendingMode.isNotEmpty() &&
            isSameState(newMode, newProvider, pendingMode, pendingProvider)
        ) {
            Log.d(TAG, "handleDnsChanged: manual change reached the pending restore")
            cancel(context)
            return
        }

        // There is nothing to restore when the new state already is the target.
        val targetState = resolveTargetState(context, target, previousMode, previousProvider)
        if (isSameState(newMode, newProvider, targetState.first, targetState.second)) {
            cancel(context)
            return
        }

        preferences.autoRevertPendingMode = targetState.first
        preferences.autoRevertPendingProvider = targetState.second ?: ""

        val deadline = System.currentTimeMillis() + delaySeconds * 1_000L
        Log.d(TAG, "onDnsChanged: restoring in $delaySeconds s (target=$target)")
        showNotification(context, target, previousMode, previousProvider, deadline)
        // The in-process timer is the primary trigger; the alarm covers process death.
        scheduleInProcessRevert(context, deadline, target, previousMode, previousProvider)
        scheduleAlarm(context, target, previousMode, previousProvider, deadline)
    }

    /**
     * Cancels a pending restore and removes its notification.
     */
    fun cancel(context: Context) {
        revertJob?.cancel()
        revertJob = null
        runCatching {
            context.getSystemService(AlarmManager::class.java)
                .cancel(alarmPendingIntent(context))
        }.onFailure { Log.e(TAG, "failed to cancel alarm", it) }
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
        val preferences = PreferenceHelper.defaultPreference(context)
        preferences.autoRevertPendingMode = ""
        preferences.autoRevertPendingProvider = ""
    }

    /**
     * Restores the dns state described by the intent and cancels the pending restore.
     */
    suspend fun performRevert(context: Context, intent: Intent) {
        val target = intent.getIntExtra(EXTRA_TARGET, PrivateDNSUtils.AUTO_REVERT_TARGET_PREVIOUS)
        val previousMode = intent.getStringExtra(EXTRA_PREVIOUS_MODE)
        val previousProvider = intent.getStringExtra(EXTRA_PREVIOUS_PROVIDER)
        revertTo(context, target, previousMode, previousProvider)
    }

    private fun scheduleInProcessRevert(
        context: Context,
        deadline: Long,
        target: Int,
        previousMode: String?,
        previousProvider: String?
    ) {
        revertJob?.cancel()
        revertJob = scope.launch {
            delay((deadline - System.currentTimeMillis()).coerceAtLeast(0L))
            Log.d(TAG, "in-process timer fired")
            runCatching { revertTo(context, target, previousMode, previousProvider) }
                .onFailure { Log.e(TAG, "in-process revert failed", it) }
        }
    }

    private suspend fun revertTo(
        context: Context,
        target: Int,
        previousMode: String?,
        previousProvider: String?
    ) {
        val state = resolveTargetState(context, target, previousMode, previousProvider)
        Log.d(TAG, "revertTo: restoring to ${state.first} ${state.second}")
        applyDnsState(context, state.first, state.second)
        cancel(context)
    }

    /**
     * Writes the dns mode and provider to the system settings and notifies the tile.
     */
    private fun applyDnsState(context: Context, mode: String, provider: String?) {
        val contentResolver = context.contentResolver
        PrivateDNSUtils.setPrivateProvider(contentResolver, provider)
        PrivateDNSUtils.setPrivateMode(contentResolver, mode)
        context.sendBroadcast(
            Intent(REFRESH_TILE_ACTION).setPackage(context.packageName)
        )
    }

    private suspend fun resolveTargetState(
        context: Context,
        target: Int,
        previousMode: String?,
        previousProvider: String?
    ): Pair<String, String?> {
        if (target == PrivateDNSUtils.AUTO_REVERT_TARGET_FIRST) {
            // getFirstEnabled throws when there are no enabled servers.
            val firstServer = runCatching {
                (context.applicationContext as PrivateDNSApp).repository.getFirstEnabled()
            }.getOrNull()
            return if (firstServer != null) {
                PrivateDNSUtils.DNS_MODE_PRIVATE to firstServer.server
            } else {
                PrivateDNSUtils.DNS_MODE_OFF to null
            }
        }
        return fixedTargetState(target, previousMode, previousProvider)
    }

    private fun fixedTargetState(
        target: Int,
        previousMode: String?,
        previousProvider: String?
    ): Pair<String, String?> {
        return when (target) {
            PrivateDNSUtils.AUTO_REVERT_TARGET_OFF -> PrivateDNSUtils.DNS_MODE_OFF to null
            PrivateDNSUtils.AUTO_REVERT_TARGET_AUTO -> PrivateDNSUtils.DNS_MODE_AUTO to null
            else -> {
                val mode = previousMode?.takeIf { it.isNotEmpty() } ?: PrivateDNSUtils.DNS_MODE_OFF
                val provider =
                    if (mode.equals(PrivateDNSUtils.DNS_MODE_PRIVATE, ignoreCase = true)) {
                        previousProvider
                    } else {
                        null
                    }
                mode to provider
            }
        }
    }

    private fun isSameState(
        mode1: String,
        provider1: String?,
        mode2: String,
        provider2: String?
    ): Boolean {
        if (!mode1.equals(mode2, ignoreCase = true)) {
            return false
        }
        // Providers only matter in private mode.
        if (!mode1.equals(PrivateDNSUtils.DNS_MODE_PRIVATE, ignoreCase = true)) {
            return true
        }
        return provider1 == provider2
    }

    private fun describeTarget(
        context: Context,
        target: Int,
        previousMode: String?,
        previousProvider: String?
    ): String {
        if (target == PrivateDNSUtils.AUTO_REVERT_TARGET_FIRST) {
            return context.getString(R.string.auto_revert_description_first)
        }
        val (mode, provider) = fixedTargetState(target, previousMode, previousProvider)
        return when {
            mode.equals(PrivateDNSUtils.DNS_MODE_OFF, ignoreCase = true) ->
                context.getString(R.string.dns_off)

            mode.equals(PrivateDNSUtils.DNS_MODE_AUTO, ignoreCase = true) ->
                context.getString(R.string.dns_auto)

            !provider.isNullOrEmpty() -> provider

            else -> context.getString(R.string.auto_revert_description_previous)
        }
    }

    private fun showNotification(
        context: Context,
        target: Int,
        previousMode: String?,
        previousProvider: String?,
        deadline: Long
    ) {
        createNotificationChannel(context)
        val description = describeTarget(context, target, previousMode, previousProvider)
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_private_black_24dp)
            .setContentTitle(context.getString(R.string.auto_revert_notification_title))
            .setContentText(
                context.getString(R.string.auto_revert_notification_text, description)
            )
            .setWhen(deadline)
            .setShowWhen(true)
            .setUsesChronometer(true)
            .setChronometerCountDown(true)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setAutoCancel(false)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(openAppPendingIntent(context))
            .addAction(
                0,
                context.getString(R.string.auto_revert_keep),
                broadcastPendingIntent(
                    context, ACTION_KEEP, REQUEST_KEEP,
                    PrivateDNSUtils.AUTO_REVERT_TARGET_PREVIOUS, null, null
                )
            )
            .addAction(
                0,
                context.getString(R.string.auto_revert_restore_now),
                broadcastPendingIntent(
                    context, ACTION_RESTORE_NOW, REQUEST_RESTORE_NOW,
                    target, previousMode, previousProvider
                )
            )

        val notificationManager = NotificationManagerCompat.from(context)
        if (!notificationManager.areNotificationsEnabled()) {
            Log.w(TAG, "notifications disabled, restore cannot be cancelled")
            return
        }
        try {
            notificationManager.notify(NOTIFICATION_ID, builder.build())
        } catch (exception: SecurityException) {
            Log.e(TAG, "failed to post notification", exception)
        }
    }

    private fun scheduleAlarm(
        context: Context,
        target: Int,
        previousMode: String?,
        previousProvider: String?,
        deadline: Long
    ) {
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        val pendingIntent = broadcastPendingIntent(
            context, ACTION_AUTO_REVERT, REQUEST_ALARM, target, previousMode, previousProvider
        )
        runCatching {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, deadline, pendingIntent)
            } else {
                // Exact alarms are not permitted, fall back to a best effort alarm.
                Log.w(TAG, "exact alarms not permitted, using inexact fallback")
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, deadline, pendingIntent)
            }
        }.onFailure { Log.e(TAG, "failed to schedule alarm", it) }
    }

    private fun alarmPendingIntent(context: Context): PendingIntent {
        return broadcastPendingIntent(
            context, ACTION_AUTO_REVERT, REQUEST_ALARM,
            PrivateDNSUtils.AUTO_REVERT_TARGET_PREVIOUS, null, null
        )
    }

    private fun broadcastPendingIntent(
        context: Context,
        action: String,
        requestCode: Int,
        target: Int,
        previousMode: String?,
        previousProvider: String?
    ): PendingIntent {
        val intent = Intent(context, AutoRevertReceiver::class.java).apply {
            this.action = action
            putExtra(EXTRA_TARGET, target)
            putExtra(EXTRA_PREVIOUS_MODE, previousMode)
            putExtra(EXTRA_PREVIOUS_PROVIDER, previousProvider)
        }
        return PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun openAppPendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context,
            REQUEST_OPEN_APP,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
