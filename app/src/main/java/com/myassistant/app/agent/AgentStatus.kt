package com.myassistant.app.agent

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What Nova is doing right now (shown live under the chat while it works), and its latest thought. */
object AgentStatus {
    private val _now = MutableStateFlow<String?>(null)
    val now: StateFlow<String?> = _now.asStateFlow()
    private val _thought = MutableStateFlow<String?>(null)
    val thought: StateFlow<String?> = _thought.asStateFlow()
    @Volatile var startedAt = 0L
        private set

    fun start() { startedAt = System.currentTimeMillis(); _thought.value = null; _now.value = "بيفكّر…" }
    fun set(text: String) { _now.value = text }
    fun thought(text: String?) { if (!text.isNullOrBlank()) _thought.value = text.trim() }
    fun done() { _now.value = null; _thought.value = null }
}
