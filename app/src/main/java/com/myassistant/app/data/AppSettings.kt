package com.myassistant.app.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Simple on/off preferences. Add a new one with a key and a default. */
class AppSettings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private val _learnFromAi = MutableStateFlow(prefs.getBoolean(LEARN, true))
    val learnFromAi: StateFlow<Boolean> = _learnFromAi.asStateFlow()

    private val _autoMemory = MutableStateFlow(prefs.getBoolean(AUTO_MEMORY, true))
    val autoMemory: StateFlow<Boolean> = _autoMemory.asStateFlow()

    private val _askBeforeScreen = MutableStateFlow(prefs.getBoolean(ASK_SCREEN, true))
    /** Ask once per request before the assistant taps/types in other apps. */
    val askBeforeScreen: StateFlow<Boolean> = _askBeforeScreen.asStateFlow()

    fun setAskBeforeScreen(on: Boolean) {
        prefs.edit().putBoolean(ASK_SCREEN, on).apply()
        _askBeforeScreen.value = on
    }

    private val _speakReplies = MutableStateFlow(prefs.getBoolean(SPEAK, true))
    /** Read replies aloud when the request was spoken. */
    val speakReplies: StateFlow<Boolean> = _speakReplies.asStateFlow()
    fun setSpeakReplies(on: Boolean) { prefs.edit().putBoolean(SPEAK, on).apply(); _speakReplies.value = on }

    private val _handsFree = MutableStateFlow(prefs.getBoolean(HANDS_FREE, true))
    /** After speaking a reply, listen again automatically (a real back-and-forth conversation). */
    val handsFree: StateFlow<Boolean> = _handsFree.asStateFlow()
    fun setHandsFree(on: Boolean) { prefs.edit().putBoolean(HANDS_FREE, on).apply(); _handsFree.value = on }

    fun setLearnFromAi(on: Boolean) {
        prefs.edit().putBoolean(LEARN, on).apply()
        _learnFromAi.value = on
    }

    fun setAutoMemory(on: Boolean) {
        prefs.edit().putBoolean(AUTO_MEMORY, on).apply()
        _autoMemory.value = on
    }

    private companion object {
        const val LEARN = "learn_from_ai"
        const val AUTO_MEMORY = "auto_memory"
        const val ASK_SCREEN = "ask_before_screen"
        const val SPEAK = "speak_replies"
        const val HANDS_FREE = "hands_free"
    }
}
