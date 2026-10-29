package com.cardroid.app

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.Process
import com.google.firebase.FirebaseApp
import com.google.firebase.crashlytics.FirebaseCrashlytics

/** Marker so user-sent reports group together in the Crashlytics console, apart from real crashes. */
internal class UserReportedIssue(message: String) : Exception(message)

/**
 * Uploads the sanitized connection report (the same text as Copy/Share) as a Crashlytics non-fatal.
 * Only called after the user confirms; never sends CarDroidLog lines or protocol data.
 * Returns a short reference ID, or null when Crashlytics is unavailable (debug build, no Firebase config).
 *
 * Crashlytics only uploads a non-fatal once its process restarts, and the main process lives on with
 * the car service. So the report is recorded in the throwaway ":devreport" process, which then dies,
 * and a second start of that process uploads it straight away.
 */
internal fun sendReportToDeveloper(context: Context, report: String): String? {
    val enabled = runCatching { FirebaseCrashlytics.getInstance().isCrashlyticsCollectionEnabled }.getOrDefault(false)
    if (!enabled) return null
    val id = java.util.UUID.randomUUID().toString().take(8)
    val app = context.applicationContext
    val service = Intent(app, DeveloperReportService::class.java)
    app.startService(Intent(service).putExtra(DeveloperReportService.EXTRA_ID, id).putExtra(DeveloperReportService.EXTRA_REPORT, report))
    Handler(Looper.getMainLooper()).postDelayed({ runCatching { app.startService(service) } }, FLUSH_DELAY_MS)
    return id
}

private const val FLUSH_DELAY_MS = 4_000L

/** Runs in ":devreport". With a report: record it, then die. Without: a fresh start, so Crashlytics uploads it. */
class DeveloperReportService : Service() {
    override fun onBind(intent: Intent?) = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        FirebaseApp.initializeApp(this)
        val crashlytics = FirebaseCrashlytics.getInstance()
        val id = intent?.getStringExtra(EXTRA_ID)
        val report = intent?.getStringExtra(EXTRA_REPORT)
        if (id != null && report != null) {
            crashlytics.setCustomKey("report_id", id)
            // One key per line: Crashlytics logs did not show up on non-fatals in the console, keys do.
            report.lines().filter { it.isNotBlank() }.take(MAX_LINES).forEachIndexed { i, line ->
                crashlytics.setCustomKey("report_%02d".format(i + 1), line)
            }
            crashlytics.recordException(UserReportedIssue("User report $id"))
        }
        // Give Crashlytics' disk writer (record) or uploader (fresh start) time to finish, then end the process.
        Handler(Looper.getMainLooper()).postDelayed({
            stopSelf()
            Process.killProcess(Process.myPid())
        }, if (report != null) 1_500L else 10_000L)
        return START_NOT_STICKY
    }

    companion object {
        const val EXTRA_ID = "id"
        const val EXTRA_REPORT = "report"
        // Crashlytics keeps at most 64 custom keys per event; leave room for report_id.
        private const val MAX_LINES = 60
    }
}
