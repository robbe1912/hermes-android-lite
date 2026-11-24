package com.robbe1912.hermeslite.voice

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.Locale

/**
 * Reads answers aloud with the text-to-speech engine of the device.
 *
 * The engine starts when the user turns the setting on, so the first answer does not wait for it.
 */
class ReadAloud(context: Context) {
    private val appContext = context.applicationContext
    private val lock = Any()

    private var engine: TextToSpeech? = null
    private var ready = false
    private var pending: String? = null

    /** true from [speak] until the engine ends the answer. */
    val speaking = MutableStateFlow(false)

    /** Starts the engine now. A call does nothing when the engine runs. */
    fun prepare() {
        synchronized(lock) {
            if (engine != null) return
            engine = TextToSpeech(appContext) { status -> appContext.mainExecutor.execute { onInit(status) } }
        }
    }

    /** Reads [text] and drops Markdown marks that a voice should not read. */
    fun speak(text: String) {
        prepare()
        val clean = speakable(text)
        if (clean.isBlank()) return
        synchronized(lock) {
            if (ready) {
                engine?.speak(clean, TextToSpeech.QUEUE_FLUSH, null, "answer")
                speaking.value = true
                Log.i("HermesLite", "Reads ${clean.length} characters aloud")
            } else {
                // The engine reports soon. The text waits for it.
                pending = clean
            }
        }
    }

    /** Ends the answer now. A text that waits for the engine is dropped. */
    fun stop() {
        synchronized(lock) {
            pending = null
            if (ready) engine?.stop()
            speaking.value = false
        }
    }

    /** The engine reports on another thread. The report goes to the main thread, after the field is set. */
    private fun onInit(status: Int) {
        synchronized(lock) {
            ready = status == TextToSpeech.SUCCESS
            if (!ready) {
                Log.w("HermesLite", "The voice engine of the device did not start (status $status).")
                return
            }
            engine?.language = Locale.getDefault()
            engine?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit
                override fun onDone(utteranceId: String?) {
                    speaking.value = false
                }

                @Deprecated("Replaced by onError(String, Int) in API 21")
                override fun onError(utteranceId: String?) {
                    speaking.value = false
                }
            })
            pending?.let { speak(it) }
            pending = null
        }
    }
}

private val link = Regex("\\[([^]]*)]\\([^)]*\\)")
private val lineMarks = Regex("(?m)^(#{1,6}\\s+|>\\s?|[-*+]\\s+)")
private val bold = Regex("\\*\\*(\\S(?:.*?\\S)?)\\*\\*")
private val italic = Regex("(?<![\\w*])\\*(?!\\s)([^*\\n]+?)(?<!\\s)\\*(?![\\w*])")
private val codeMarks = Regex("`+")
private val blankRuns = Regex("\\n{3,}")

/** The text of an answer without Markdown marks: headings, quotes, bullets, emphasis, code marks, links. */
internal fun speakable(text: String): String = link.replace(text, "$1")
    .replace(lineMarks, "")
    .replace(bold, "$1")
    .replace(italic, "$1")
    .replace(codeMarks, "")
    .replace(blankRuns, "\n\n")
    .trim()
