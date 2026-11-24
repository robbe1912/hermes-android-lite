package com.robbe1912.hermeslite.push

import android.content.Context
import android.content.Intent
import com.robbe1912.hermeslite.MainActivity

/**
 * The intent that a notification sends when the user taps it.
 *
 * The launcher activity is open to all apps. So the intent carries the id of this install as a
 * nonce. The app opens a run only when the nonce is its own.
 */
object WakeIntent {
    private const val ACTION = "com.robbe1912.hermeslite.WAKE"
    private const val NONCE = "nonce"
    private const val SESSION = "session_id"
    private const val JOB = "job_id"
    private const val TITLE = "title"

    /** What the notification opens: the thread of a run, or the runs of a job. */
    data class Target(val sessionId: String?, val jobId: String, val title: String)

    fun intent(context: Context, nonce: String, jobId: String, title: String, sessionId: String?): Intent =
        Intent(context, MainActivity::class.java)
            .setAction(ACTION)
            .putExtra(NONCE, nonce)
            .putExtra(JOB, jobId)
            .putExtra(TITLE, title)
            .putExtra(SESSION, sessionId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Reads the target of an intent. Returns null when the intent is not from this app. */
    fun target(intent: Intent?, nonce: String): Target? {
        if (intent == null || intent.action != ACTION || nonce.isBlank()) return null
        if (intent.getStringExtra(NONCE) != nonce) return null
        val jobId = intent.getStringExtra(JOB)?.takeIf { it.isNotBlank() } ?: return null
        val sessionId = intent.getStringExtra(SESSION)?.takeIf { it.isNotBlank() }
        return Target(sessionId, jobId, intent.getStringExtra(TITLE) ?: jobId)
    }
}
