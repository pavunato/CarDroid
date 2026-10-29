package com.cardroid.app

import com.google.firebase.crashlytics.FirebaseCrashlytics

/** Marker so user-sent reports group together in the Crashlytics console, apart from real crashes. */
internal class UserReportedIssue(message: String) : Exception(message)

/**
 * Uploads the sanitized connection report (the same text as Copy/Share) as a Crashlytics non-fatal.
 * Only called after the user confirms; never sends CarDroidLog lines or protocol data.
 * Returns a short reference ID, or null when Crashlytics is unavailable (debug build, no Firebase config).
 */
internal fun sendReportToDeveloper(report: String): String? = runCatching {
    val crashlytics = FirebaseCrashlytics.getInstance()
    if (!crashlytics.isCrashlyticsCollectionEnabled) return null
    val id = java.util.UUID.randomUUID().toString().take(8)
    crashlytics.setCustomKey("report_id", id)
    report.lineSequence().forEach(crashlytics::log)
    crashlytics.recordException(UserReportedIssue("User report $id"))
    id
}.getOrNull()
