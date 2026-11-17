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
    }
}
