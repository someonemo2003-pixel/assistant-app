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
import com.myassistant.app.data.Conversation
import com.myassistant.app.fast.Experience
import com.myassistant.app.memory.MemoryItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

enum class Screen { Chat, Settings, Memory, Chats, Learned }

class ConfirmRequest(val text: String, val answer: (Boolean) -> Unit)

class AppViewModel(app: Application) : AndroidViewModel(app) {

    private val graph = (app as AssistantApp).graph
    val providerStore = graph.providers
    val tools = graph.tools
    val settings = graph.settings

    val messages = mutableStateListOf<ChatMessage>()
    var busy by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
    var confirmRequest by mutableStateOf<ConfirmRequest?>(null)
        private set
    var screen by mutableStateOf(Screen.Chat)

    val memories = mutableStateListOf<MemoryItem>()
    val conversations = mutableStateListOf<Conversation>()
    val experiences = mutableStateListOf<Experience>()

    /** The conversation on screen (null = new, not saved yet). */
    var currentConversation by mutableStateOf<Long?>(null)
        private set

    /** Short info line, e.g. "اتحفظ ٣ معلومات جديدة في الذاكرة". */
    var notice by mutableStateOf<String?>(null)

    private var job: Job? = null

    fun send(text: String) {
        val t = text.trim()
        if (t.isEmpty() || busy) return
        error = null
        notice = null
        val userMsg = ChatMessage(role = Role.USER, text = t)
        messages += userMsg
        job = viewModelScope.launch {
            busy = true
            try {
                val convId = currentConversation ?: withContext(Dispatchers.IO) { graph.chats.newConversation(t) }
                    .also { currentConversation = it }
                graph.toolContext.currentConversation = convId
                persist(convId, userMsg)
                graph.agent.run(
                    history = messages.toMutableList(),
                    emit = { messages += it; persist(convId, it) },
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

    private suspend fun persist(convId: Long, m: ChatMessage) {
        withContext(Dispatchers.IO) { runCatching { graph.chats.addMessage(convId, m) } }
    }

    /** Starts a fresh chat (the old one stays saved in the chat list). */
    fun newChat() {
        stop()
        finishCurrent()
        messages.clear()
        error = null
        currentConversation = null
        graph.toolContext.currentConversation = null
    }

    fun openConversation(id: Long) {
        stop()
        finishCurrent()
        error = null
        viewModelScope.launch {
            val loaded = withContext(Dispatchers.IO) { graph.chats.messages(id) }
            messages.clear()
            messages.addAll(loaded)
            currentConversation = id
            graph.toolContext.currentConversation = id
            screen = Screen.Chat
        }
    }

    /** When leaving a chat, pull lasting facts from it into memory (in the background). */
    private fun finishCurrent() {
        val convId = currentConversation ?: return
        val snapshot = messages.toList()
        if (!settings.autoMemory.value || snapshot.count { it.role == Role.USER } < 2) return
        graph.appScope.launch {
            val already = runCatching { graph.chats.conversations().firstOrNull { it.id == convId }?.extracted }.getOrNull()
            if (already == true) return@launch
            val saved = runCatching { graph.memoryExtractor.extract(snapshot) }.getOrNull() ?: return@launch
            runCatching { graph.chats.markExtracted(convId) }
            if (saved > 0) withContext(Dispatchers.Main) { notice = "🧠 اتحفظ $saved معلومة جديدة في الذاكرة" }
        }
    }

    // ---- Chats ----

    fun loadConversations() {
        viewModelScope.launch {
            val list = withContext(Dispatchers.IO) { graph.chats.conversations() }
            conversations.clear()
            conversations.addAll(list)
        }
    }

    fun deleteConversation(id: Long) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { graph.chats.delete(id) }
            conversations.removeAll { it.id == id }
            if (currentConversation == id) {
                messages.clear()
                currentConversation = null
            }
        }
    }

    // ---- What the fast brain learned ----

    fun loadExperiences() {
        viewModelScope.launch {
            val list = withContext(Dispatchers.IO) { graph.experience.all() }
            experiences.clear()
            experiences.addAll(list)
        }
    }

    fun deleteExperience(id: Long) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { graph.experience.delete(id) }
            experiences.removeAll { it.id == id }
        }
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
