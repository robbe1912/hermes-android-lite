package com.robbe1912.hermeslite.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.Locale

/**
 * One speech turn with the speech recognizer of the device.
 *
 * The device decides where the audio goes: some recognizers work on the device, some use a network
 * service. Call [start], [finish] and [cancel] on the main thread.
 */
class VoiceInput(context: Context) {
    private val appContext = context.applicationContext

    private var recognizer: SpeechRecognizer? = null

    /** true from [start] until the turn ends. */
    val listening = MutableStateFlow(false)

    /** The words that the recognizer heard so far. It is the final text when the turn ends well. */
    val text = MutableStateFlow("")

    /** Why the last turn ended without a text. null when it ended well or was cancelled. */
    val error = MutableStateFlow<String?>(null)

    /** false when the device has no speech recognizer. */
    val available: Boolean get() = SpeechRecognizer.isRecognitionAvailable(appContext)

    /** Starts a turn. The app needs the microphone permission first. */
    fun start() {
        cancel()
        if (!available) {
            error.value = "This device has no speech recognition."
            return
        }
        val started = SpeechRecognizer.createSpeechRecognizer(appContext)
        recognizer = started
        started.setRecognitionListener(listener)
        started.startListening(
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
            },
        )
        text.value = ""
        error.value = null
        listening.value = true
    }

    /** Ends the turn with the words that the recognizer heard until now. */
    fun finish() {
        recognizer?.stopListening()
    }

    /** Ends the turn without a text. Nothing happens when the app does not listen. */
    fun cancel() {
        val current = recognizer ?: return
        recognizer = null
        current.cancel()
        current.destroy()
        listening.value = false
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) = Unit
        override fun onBeginningOfSpeech() = Unit
        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() = Unit
        override fun onEvent(eventType: Int, params: Bundle?) = Unit

        override fun onPartialResults(partialResults: Bundle?) {
            if (recognizer == null) return
            partialResults?.firstText()?.let { text.value = it }
        }

        override fun onResults(results: Bundle?) = end(results?.firstText(), null)

        override fun onError(error: Int) = end(null, message(error))
    }

    /** Ends the turn. A late callback of a cancelled recognizer changes nothing. */
    private fun end(result: String?, failure: String?) {
        val current = recognizer ?: return
        recognizer = null
        current.destroy()
        result?.let { text.value = it }
        error.value = failure
        Log.i("HermesLite", "Voice input ended: ${failure ?: "${result?.length ?: 0} characters"}")
        listening.value = false
    }

    private fun Bundle.firstText(): String? =
        getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.takeIf { it.isNotBlank() }

    private fun message(code: Int): String = when (code) {
        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "I heard no words."
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "The app needs the microphone permission."
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Speech recognition needs a network."
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Speech recognition is busy. Try again."
        else -> "Speech recognition failed (code $code)."
    }
}
