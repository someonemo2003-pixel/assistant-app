package com.myassistant.app.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.myassistant.app.AssistantApp
import com.myassistant.app.ai.ProviderConfig
import com.myassistant.app.ai.ProviderFactory
import com.myassistant.app.core.ChatMessage
import com.myassistant.app.core.Role
import com.myassistant.app.memory.MemoryItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

enum class Screen { Chat, Settings, Memory }

class ConfirmRequest(val text: String, val answer: (Boolean) -> Unit)

class AppViewModel(app: Application) : AndroidViewModel(app) {

    private val graph = (app as AssistantApp).graph
    val providerStore = graph.providers
    val tools = graph.tools

    val messages = mutableStateListOf<ChatMessage>()
    var busy by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
    var confirmRequest by mutableStateOf<ConfirmRequest?>(null)
        private set
    var screen by mutableStateOf(Screen.Chat)

    val memories = mutableStateListOf<MemoryItem>()

    private var job: Job? = null

    fun send(text: String) {
        val t = text.trim()
        if (t.isEmpty() || busy) return
        error = null
        messages += ChatMessage(role = Role.USER, text = t)
        job = viewModelScope.launch {
            busy = true
            try {
                graph.agent.run(
                    history = messages.toMutableList(),
                    emit = { messages += it },
                    confirm = { askConfirm(it) },
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message ?: "حصل خطأ"
            } finally {
                busy = false
                confirmRequest = null
            }
        }
    }

    fun stop() {
        job?.cancel()
    }

    fun clearChat() {
        stop()
        messages.clear()
        error = null
    }

    private suspend fun askConfirm(text: String): Boolean = suspendCancellableCoroutine { cont ->
        confirmRequest = ConfirmRequest(text) { ok ->
            confirmRequest = null
            if (cont.isActive) cont.resume(ok)
        }
    }

    // ---- Providers ----

    fun saveProvider(config: ProviderConfig, newKey: String?) {
        providerStore.upsert(config)
        if (!newKey.isNullOrBlank()) providerStore.setApiKey(config.id, newKey)
    }

    fun deleteProvider(id: String) = providerStore.remove(id)
    fun moveProvider(id: String, delta: Int) = providerStore.move(id, delta)
    fun toggleProvider(config: ProviderConfig, enabled: Boolean) = providerStore.upsert(config.copy(enabled = enabled))

    suspend fun fetchModels(config: ProviderConfig, typedKey: String?): List<String> {
        val key = typedKey?.takeIf { it.isNotBlank() } ?: providerStore.apiKey(config.id)
            ?: throw IllegalStateException("اكتب مفتاح الـ API الأول")
        return ProviderFactory.create(config, key.trim()).listModels()
    }

    // ---- Memory ----

    fun loadMemories() {
        viewModelScope.launch {
            val items = withContext(Dispatchers.IO) { graph.memory.recent(2000) }
            memories.clear()
            memories.addAll(items)
        }
    }

    fun addMemory(text: String) {
        if (text.isBlank()) return
        viewModelScope.launch {
            withContext(Dispatchers.IO) { graph.memory.add(text) }
            loadMemories()
        }
    }

    fun deleteMemory(id: Long) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { graph.memory.delete(id) }
            memories.removeAll { it.id == id }
        }
    }
}
