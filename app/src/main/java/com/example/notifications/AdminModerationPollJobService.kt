package com.example.notifications

import android.app.job.JobParameters
import android.app.job.JobService
import com.example.data.local.SoundLapaPreferences
import com.example.data.remote.CloudLibraryRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class AdminModerationPollJobService : JobService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pollJob: Job? = null

    override fun onStartJob(params: JobParameters?): Boolean {
        pollJob?.cancel()
        pollJob = serviceScope.launch {
            try {
                val repo = CloudLibraryRepository(applicationContext)
                if (!repo.isCurrentUserAvatarReviewer()) {
                    AdminNotificationHelper.cancelBackgroundPolling(applicationContext)
                    jobFinished(params, false)
                    return@launch
                }
                val prefs = SoundLapaPreferences(applicationContext)
                val settings = prefs.currentSettings()
                val sinceAvatar = settings.lastNotifiedAvatarRequestMs
                val sinceReport = settings.lastNotifiedBugReportMs

                val result = repo.fetchAdminNewEventsSince(
                    sinceAvatarMs = sinceAvatar,
                    sinceReportMs = sinceReport,
                ).getOrNull()

                if (result != null) {
                    val newAvatars = result.newPendingAvatarRequests
                    if (newAvatars.isNotEmpty()) {
                        val newest = newAvatars.first()
                        val maxTs = newAvatars.maxOf { it.createdAtMs }
                        if (sinceAvatar > 0L) {
                            AdminNotificationHelper.notifyNewAvatarRequest(
                                context = applicationContext,
                                requesterName = newest.displayName,
                                accountUid = newest.accountUid,
                                pendingCount = newAvatars.size,
                            )
                        }
                        prefs.setLastNotifiedAvatarRequestMs(maxTs)
                    }

                    val newReports = result.newBugReports
                    if (newReports.isNotEmpty() && repo.isCurrentUserVerifiedDeveloper()) {
                        val newest = newReports.first()
                        val maxTs = newReports.maxOf { it.createdAtMs }
                        if (sinceReport > 0L) {
                            AdminNotificationHelper.notifyNewBugReport(
                                context = applicationContext,
                                reporterName = newest.reporterName,
                                previewMessage = newest.message,
                                newCount = newReports.size,
                            )
                        }
                        prefs.setLastNotifiedBugReportMs(maxTs)
                    }
                }
            } catch (_: Throwable) {
                // Non-fatal background check failure; will retry on next periodic cycle
            } finally {
                jobFinished(params, false)
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters?): Boolean {
        pollJob?.cancel()
        return true
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }
}
