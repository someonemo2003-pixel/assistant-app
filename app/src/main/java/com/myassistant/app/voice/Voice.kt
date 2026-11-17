package com.myassistant.app.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/** Listening state shown in the UI. */
data class ListenState(
    val listening: Boolean = false,
    val partial: String = "",
    /** 0..1 microphone level for the animation. */
    val level: Float = 0f,
    val error: String? = null,
)

/**
 * Speech → text with Android's recogniser (Egyptian Arabic by default).
 * Must be used from the main thread.
 */
class Listener(private val context: Context) {

    private var recognizer: SpeechRecognizer? = null
    private val _state = MutableStateFlow(ListenState())
    val state: StateFlow<ListenState> = _state.asStateFlow()

    fun available() = SpeechRecognizer.isRecognitionAvailable(context)

    /** [onResult] gets the final text; null means nothing was heard. */
    fun start(language: String = "ar-EG", onResult: (String?) -> Unit) {
        stop()
        if (!available()) {
            _state.value = ListenState(error = "مفيش خدمة تعرّف على الكلام على الموبايل (ثبّت تطبيق Google).")
            onResult(null)
            return
        }
        val r = SpeechRecognizer.createSpeechRecognizer(context)
        recognizer = r
        var delivered = false
        fun deliver(text: String?) {
            if (delivered) return
            delivered = true
            _state.value = _state.value.copy(listening = false, level = 0f)
            onResult(text)
        }
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                _state.value = ListenState(listening = true)
            }
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) {
                _state.value = _state.value.copy(level = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f))
            }
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() {
                _state.value = _state.value.copy(level = 0f)
            }
            override fun onError(error: Int) {
                val msg = when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> null
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "محتاج صلاحية الميكروفون."
                    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "مشكلة في النت أثناء التعرّف على الصوت."
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "الميكروفون مشغول، جرّب تاني."
                    SpeechRecognizer.ERROR_AUDIO -> "مشكلة في الميكروفون."
                    else -> "معرفتش أسمعك كويس، جرّب تاني."
                }
                _state.value = ListenState(error = msg)
                deliver(null)
            }
            override fun onResults(results: Bundle?) {
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim()
                _state.value = ListenState(partial = text.orEmpty())
                deliver(text?.takeIf { it.isNotEmpty() })
            }
            override fun onPartialResults(partialResults: Bundle?) {
                val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                if (text.isNotEmpty()) _state.value = _state.value.copy(partial = text)
            }
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, language)
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
        _state.value = ListenState(listening = true)
        runCatching { r.startListening(intent) }.onFailure {
            _state.value = ListenState(error = "معرفتش أشغّل الميكروفون.")
            deliver(null)
        }
    }

    fun stop() {
        recognizer?.let {
            runCatching { it.cancel() }
            runCatching { it.destroy() }
        }
        recognizer = null
        if (_state.value.listening) _state.value = ListenState()
    }

    fun clearError() {
        _state.value = _state.value.copy(error = null)
    }
}

/** Text → speech with the phone's voice (Arabic if installed). */
class Speaker(context: Context) {

    private var ready = false
    private var pending: Pair<String, (() -> Unit)?>? = null
    private val callbacks = HashMap<String, () -> Unit>()

    private val _speaking = MutableStateFlow(false)
    val speaking: StateFlow<Boolean> = _speaking.asStateFlow()

    /** False if no Arabic voice is installed. */
    var hasArabic = true
        private set

    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        ready = status == TextToSpeech.SUCCESS
        if (ready) {
            val r = tts.setLanguage(Locale("ar", "EG")).let {
                if (it < TextToSpeech.LANG_AVAILABLE) tts.setLanguage(Locale("ar")) else it
            }
            hasArabic = r >= TextToSpeech.LANG_AVAILABLE
            tts.setSpeechRate(1.05f)
            pending?.let { (t, cb) -> speak(t, cb) }
            pending = null
        }
    }

    init {
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) { _speaking.value = true }
            override fun onDone(utteranceId: String?) = finish(utteranceId)
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) = finish(utteranceId)
            override fun onStop(utteranceId: String?, interrupted: Boolean) = finish(utteranceId)
        })
    }

    private fun finish(id: String?) {
        _speaking.value = false
        id?.let { callbacks.remove(it) }?.let { cb -> android.os.Handler(android.os.Looper.getMainLooper()).post { cb() } }
    }

    fun speak(text: String, onDone: (() -> Unit)? = null) {
        val clean = cleanForSpeech(text)
        if (clean.isBlank()) { onDone?.invoke(); return }
        if (!ready) { pending = clean to onDone; return }
        val id = System.nanoTime().toString()
        onDone?.let { callbacks[id] = it }
        tts.speak(clean, TextToSpeech.QUEUE_FLUSH, null, id)
    }

    fun stop() {
        callbacks.clear()
        runCatching { tts.stop() }
        _speaking.value = false
    }

    fun shutdown() {
        stop()
        tts.shutdown()
    }

    companion object {
        /** Removes emojis, markdown and links so the voice reads naturally. */
        fun cleanForSpeech(s: String): String = s
            .replace(Regex("https?://\\S+"), "")
            .replace(Regex("[*_`#>|~\\[\\]]"), "")
            .replace(Regex("[\\p{So}\\p{Cn}\\x{1F000}-\\x{1FAFF}\\x{2600}-\\x{27BF}\\x{FE0F}]"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(1200)
    }
}
