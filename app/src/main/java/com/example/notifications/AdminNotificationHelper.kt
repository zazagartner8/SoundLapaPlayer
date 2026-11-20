package com.example.notifications

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.R

object AdminNotificationHelper {

    const val EXTRA_OPEN_ADMIN_TAB = "soundlap_open_admin_tab"
    const val TAB_MODERATORS = "moderators"
    const val TAB_REPORTS = "reports"

    private const val CHANNEL_AVATAR_MODERATION = "soundlap_moderation_avatars"
    private const val CHANNEL_DEVELOPER_REPORTS = "soundlap_developer_reports"

    private const val NOTIF_ID_AVATAR_REQUEST = 4101
    private const val NOTIF_ID_BUG_REPORT = 4102
    private const val JOB_ID_ADMIN_POLL = 4199

    private const val POLL_INTERVAL_MS = 15L * 60_000L

    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val avatarChannel = NotificationChannel(
            CHANNEL_AVATAR_MODERATION,
            "Заявки на смену аватарки",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "Уведомления для модераторов и разработчика о новых заявках на смену аватара"
            enableVibration(true)
        }
        val reportChannel = NotificationChannel(
            CHANNEL_DEVELOPER_REPORTS,
            "Репорты пользователей",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "Уведомления для разработчика о новых отчётах об ошибках"
            enableVibration(true)
        }
        manager.createNotificationChannel(avatarChannel)
        manager.createNotificationChannel(reportChannel)
    }

    fun hasNotificationPermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun notifyNewAvatarRequest(
        context: Context,
        requesterName: String,
        accountUid: String?,
        pendingCount: Int,
    ) {
        if (!hasNotificationPermission(context)) return
        ensureChannels(context)
        val safeName = requesterName.trim().ifBlank { "Пользователь" }
        val title = if (pendingCount > 1) {
            "Новые заявки на аватарку ($pendingCount)"
        } else {
            "Новая заявка на смену аватарки"
        }
        val body = buildString {
            append(safeName)
            if (!accountUid.isNullOrBlank()) {
                append(" (")
                append(accountUid)
                append(")")
            }
            append(" отправил(а) аватарку на проверку")
        }
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_OPEN_ADMIN_TAB, TAB_MODERATORS)
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            NOTIF_ID_AVATAR_REQUEST,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_AVATAR_MODERATION)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()
        runCatching {
            NotificationManagerCompat.from(context).notify(NOTIF_ID_AVATAR_REQUEST, notification)
        }
    }

    fun notifyNewBugReport(
        context: Context,
        reporterName: String,
        previewMessage: String,
        newCount: Int,
    ) {
        if (!hasNotificationPermission(context)) return
        ensureChannels(context)
        val safeName = reporterName.trim().ifBlank { "Пользователь" }
        val title = if (newCount > 1) {
            "Новые репорты ($newCount)"
        } else {
            "Новый репорт от $safeName"
        }
        val body = previewMessage.trim().take(180).ifBlank { "Поступил новый отчёт об ошибке" }
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_OPEN_ADMIN_TAB, TAB_REPORTS)
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            NOTIF_ID_BUG_REPORT,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_DEVELOPER_REPORTS)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()
        runCatching {
            NotificationManagerCompat.from(context).notify(NOTIF_ID_BUG_REPORT, notification)
        }
    }

    fun scheduleBackgroundPolling(context: Context) {
        val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
        val existing = scheduler.getPendingJob(JOB_ID_ADMIN_POLL)
        if (existing != null) return
        val component = ComponentName(context, AdminModerationPollJobService::class.java)
        val jobInfo = JobInfo.Builder(JOB_ID_ADMIN_POLL, component)
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setPeriodic(POLL_INTERVAL_MS)
            .build()
        runCatching { scheduler.schedule(jobInfo) }
    }

    fun cancelBackgroundPolling(context: Context) {
        val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
        runCatching { scheduler.cancel(JOB_ID_ADMIN_POLL) }
    }
}
