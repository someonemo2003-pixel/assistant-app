package com.myassistant.app.ui

import android.app.Application
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
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

enum class Screen { Chat, Settings, Memory, Chats, Learned, Permissions, BlockApps, Browser }

class ConfirmRequest(val text: String, val answer: (Boolean) -> Unit)

class AppViewModel(app: Application) : AndroidViewModel(app) {

    private val graph = (app as AssistantApp).graph
    val providerStore = graph.providers
    val tools = graph.tools
    val settings = graph.settings
    val meaningState = graph.meaning.state

    val messages = mutableStateListOf<ChatMessage>()
    var busy by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
    var confirmRequest by mutableStateOf<ConfirmRequest?>(null)
        private set
    var screen by mutableStateOf(Screen.Chat)

    /** Which setup panel the Settings screen shows: providers | gmail | backup. */
    var setupSection by mutableStateOf("providers")

    init {
        viewModelScope.launch {
            UiRequests.open.collect { s ->
                if (s == null) return@collect
                when (s) {
                    "permissions" -> screen = Screen.Permissions
                    "learned" -> screen = Screen.Learned
                    "chats" -> screen = Screen.Chats
                    "data" -> screen = Screen.Memory
                    else -> { setupSection = s; screen = Screen.Settings }
                }
                UiRequests.consume()
            }
        }
    }

    val memories = mutableStateListOf<MemoryItem>()
    val conversations = mutableStateListOf<Conversation>()
    val experiences = mutableStateListOf<Experience>()

    /** The conversation on screen (null = new, not saved yet). */
    var currentConversation by mutableStateOf<Long?>(null)
        private set

    /** Short info line, e.g. "اتحفظ ٣ معلومات جديدة في الذاكرة". */
    var notice by mutableStateOf<String?>(null)

    private var job: Job? = null

    // ---- Voice ----

    val listener = com.myassistant.app.voice.Listener(app)
    val speaker = com.myassistant.app.voice.Speaker(app)
    /** True while a spoken back-and-forth is going on. */
    var voiceMode by mutableStateOf(false)
        private set
    /** Set by the activity when the app was opened from the mic shortcut/tile/assist button. */
    var listenRequest by mutableStateOf(0)
    var foreground = true

    fun startListening() {
        if (busy) return
        speaker.stop()
        listener.clearError()
        voiceMode = true
        screen = Screen.Chat
        listener.start { text ->
            if (text == null) {
                voiceMode = false
            } else if (isGoodbye(text)) {
                voiceMode = false
                speaker.speak("تمام، أنا هنا لو احتجتني.")
            } else {
                send(text, spoken = true)
            }
        }
    }

    fun stopVoice() {
        voiceMode = false
        listener.stop()
        speaker.stop()
    }

    private fun isGoodbye(t: String): Boolean {
        val w = t.trim().trimEnd('.', '!', '؟').lowercase()
        return w in setOf("خلاص", "شكرا", "شكراً", "شكرا ليك", "متشكر", "باي", "سلام", "مع السلامة", "اسكت", "بس", "stop", "bye")
    }

    /** After a spoken request: read the answer aloud, then (hands-free) listen again. */
    private fun afterVoiceTurn(startIndex: Int) {
        val reply = messages.drop(startIndex).lastOrNull { it.role == Role.ASSISTANT && it.text.isNotBlank() }?.text
            ?: error?.let { "حصلت مشكلة: $it" }
        val again: () -> Unit = {
            if (voiceMode && settings.handsFree.value && foreground) {
                startListening()
            } else {
                voiceMode = false
            }
        }
        if (reply != null && settings.speakReplies.value) speaker.speak(reply) { again() } else again()
    }

    override fun onCleared() {
        listener.stop()
        speaker.shutdown()
    }

    fun send(text: String, spoken: Boolean = false) {
        val t = text.trim()
        if (t.isEmpty() || busy) return
        if (t.startsWith("/")) { runCommand(t); return }
        if (!spoken) stopVoice()
        val startIndex = messages.size
        error = null
        notice = null
        val userMsg = ChatMessage(role = Role.USER, text = t)
        messages += userMsg
        job = viewModelScope.launch {
            busy = true
            com.myassistant.app.agent.AgentStatus.start()
            try {
                val convId = currentConversation ?: withContext(Dispatchers.IO) { graph.chats.newConversation(t) }
                    .also { currentConversation = it }
                graph.toolContext.currentConversation = convId
                persist(convId, userMsg)
                graph.agent.run(
                    history = messages.toMutableList(),
                    emit = { messages += it; persist(convId, it) },
                    confirm = { askConfirm(it) },
                    mode = settings.chatMode.value,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message ?: "حصل خطأ"
            } finally {
                busy = false
                com.myassistant.app.agent.AgentStatus.done()
                confirmRequest = null
                graph.indexSoon()
                if (spoken && voiceMode) afterVoiceTurn(startIndex)
            }
        }
    }

    /** Chat commands that open a page ("/blockapps" …). */
    fun runCommand(text: String) {
        val cmd = text.trim().substringBefore(' ').lowercase()

        when (SlashCommands.all.firstOrNull { it.command == cmd }?.command) {
            "/new" -> newChat()
            "/chats" -> screen = Screen.Chats
            "/models" -> modelPickerRequest = true
            "/blockapps" -> { screen = Screen.BlockApps; loadApps() }
            "/extension", "/browser", "/sites" -> screen = Screen.Browser
            "/tasks" -> { setupSection = "tasks"; screen = Screen.Settings }
            "/memory" -> screen = Screen.Memory
            "/learned" -> screen = Screen.Learned
            "/permissions" -> screen = Screen.Permissions
            "/keys" -> screen = Screen.Settings
            "/update" -> {
                notice = "بدوّر على تحديث…"
                viewModelScope.launch {
                    notice = runCatching { com.myassistant.app.update.Updater.checkAndInstall(getApplication()) }
                        .getOrElse { "مقدرتش أشوف التحديثات دلوقتي" }
                }
            }
            "/auto" -> { settings.setChatMode(com.myassistant.app.agent.ChatMode.AUTO); notice = "الوضع: تلقائي" }
            "/talk" -> { settings.setChatMode(com.myassistant.app.agent.ChatMode.TALK); notice = "الوضع: كلام بس" }
            "/phone" -> { settings.setChatMode(com.myassistant.app.agent.ChatMode.PHONE); notice = "الوضع: التحكم في الموبايل" }
            "/code" -> { settings.setChatMode(com.myassistant.app.agent.ChatMode.CODE); notice = "الوضع: OpenCode" }
            else -> notice = "مفيش أمر اسمه $cmd. اكتب / علشان تشوف الأوامر."
        }
    }

    /** "/models" asks the chat screen to open the model menu. */
    var modelPickerRequest by mutableStateOf(false)

    // ---- /blockapps ----

    data class AppRow(
        val name: String,
        val pkg: String?,
        val cat: com.myassistant.app.admin.AppCat,
        val blocked: Boolean,
        val installed: Boolean,
        val icon: androidx.compose.ui.graphics.ImageBitmap?,
    )

    var appRows by mutableStateOf<List<AppRow>>(emptyList())
        private set
    var appsLoading by mutableStateOf(false)
        private set

    fun loadApps() {
        val ctx = getApplication<Application>()
        appsLoading = true
        viewModelScope.launch {
            appRows = withContext(Dispatchers.IO) {
                val DO = com.myassistant.app.admin.DeviceOwner
                val cats = com.myassistant.app.admin.AppCategories
                val pm = ctx.packageManager
                val browsers = DO.browserPackages(ctx)
                fun icon(pkg: String) = runCatching {
                    pm.getApplicationIcon(pkg).toBitmap(96, 96).asImageBitmap()
                }.getOrNull()
                val blocked = DO.blocked(ctx)
                val launcher = pm.queryIntentActivities(
                    android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_LAUNCHER), 0,
                ).map { it.activityInfo.packageName to it.loadLabel(pm).toString() }.distinctBy { it.first }
                    .filter { (p, _) ->
                        p != ctx.packageName && runCatching {
                            pm.getApplicationInfo(p, 0).flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM == 0
                        }.getOrDefault(false)
                    }
                val blockedRows = blocked.map { b ->
                    val inst = b.pkg != null && launcher.any { it.first == b.pkg }
                    AppRow(b.name, b.pkg, cats.of(ctx, b.pkg, b.name, browsers), true, inst, b.pkg?.takeIf { inst }?.let(::icon))
                }
                val free = launcher.filter { (p, label) -> blocked.none { it.pkg == p || (it.pkg == null && it.name.equals(label, true)) } }
                    .map { (p, label) -> AppRow(label, p, cats.of(ctx, p, label, browsers), false, true, icon(p)) }
                (blockedRows + free).sortedBy { it.name.lowercase() }
            }
            appsLoading = false
        }
    }

    /** Blocks for good (there is no unblock), then refreshes the page. */
    fun blockApp(name: String, pkg: String?) {
        val ctx = getApplication<Application>()
        viewModelScope.launch {
            notice = withContext(Dispatchers.IO) { com.myassistant.app.admin.DeviceOwner.block(ctx, name, pkg) }
            loadApps()
        }
    }

    fun moveApp(row: AppRow, cat: com.myassistant.app.admin.AppCat) {
        com.myassistant.app.admin.AppCategories.move(getApplication(), row.pkg ?: row.name, cat)
        appRows = appRows.map { if (it === row) it.copy(cat = cat) else it }
    }

    fun stop() {
        stopVoice()
        job?.cancel()
    }

    private suspend fun persist(convId: Long, m: ChatMessage) {
        withContext(Dispatchers.IO) { runCatching { graph.chats.addMessage(convId, m) } }
    }

    /** Starts a fresh chat (the old one stays saved in the chat list). */
    fun newChat() {
        settings.sessionChat.value = ""
        settings.sessionAction.value = ""
        settings.sessionOpencode.value = ""
        stop()
        finishCurrent()
        messages.clear()
        error = null
        currentConversation = null
        graph.toolContext.currentConversation = null
    }

    /** True while the OpenCode model list is being read from Termux. */
    var opencodeLoading by mutableStateOf(false)
        private set
    var opencodeError by mutableStateOf<String?>(null)
        private set

    fun refreshOpencodeModels() {
        if (opencodeLoading) return
        opencodeLoading = true
        opencodeError = null
        viewModelScope.launch {
            val app = getApplication<Application>()
            val list = runCatching { com.myassistant.app.terminal.OpenCode.models(app) }.getOrDefault(emptyList())
            if (list.isEmpty()) opencodeError = "مقدرتش أجيب الموديلات. اتأكد إن OpenCode متجهّز في Termux."
            else settings.setOpencodeModels(list)
            opencodeLoading = false
        }
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
            if (saved > 0) withContext(Dispatchers.Main) { notice = "اتحفظ $saved معلومة جديدة في الذاكرة" }
        }
    }

    // ---- Gmail hub + daily report ----

    val mailEmail = graph.mail.email
    fun mailHasPassword() = graph.mail.hasPassword()

    /** Saves the settings and tests the login; returns a message for the user. */
    suspend fun saveAndTestMail(email: String, password: String?): String {
        graph.mail.save(email, password)
        val c = graph.mail.client() ?: return "اكتب الإيميل والـ App Password"
        return withContext(Dispatchers.IO) {
            try {
                "✓ اتصل بنجاح — في صندوق الوارد ${c.test()} رسالة"
            } catch (e: javax.mail.AuthenticationFailedException) {
                "✗ الدخول فشل: راجع الإيميل والـ App Password (لازم التحقق بخطوتين يكون مفعّل)"
            } catch (e: Exception) {
                "✗ مشكلة في الاتصال: ${e.message ?: e.javaClass.simpleName}"
            }
        }
    }

    fun disconnectMail() = graph.mail.clear()

    var reportOn by mutableStateOf(com.myassistant.app.mail.DailyReport.enabled(app))
        private set
    var reportHour by mutableStateOf(com.myassistant.app.mail.DailyReport.hour(app))
        private set
    var reportMinute by mutableStateOf(com.myassistant.app.mail.DailyReport.minute(app))
        private set

    fun setReport(on: Boolean, hour: Int = reportHour, minute: Int = reportMinute) {
        reportOn = on; reportHour = hour; reportMinute = minute
        com.myassistant.app.mail.DailyReport.configure(getApplication(), on, hour, minute)
    }

    var reportSources by mutableStateOf(com.myassistant.app.mail.DailyReport.sources(app))
        private set

    fun toggleReportSource(k: String) {
        val s = reportSources.toMutableSet().apply { if (!add(k)) remove(k) }
        reportSources = s
        com.myassistant.app.mail.DailyReport.setSources(getApplication(), s)
    }

    data class ChatAccount(val pkg: String, val app: String, val raw: String, val label: String, val custom: String?, val count: Int)

    val chatAccounts = mutableStateListOf<ChatAccount>()

    fun loadAccounts() {
        viewModelScope.launch {
            val ctx = getApplication<Application>()
            val list = withContext(Dispatchers.IO) {
                val all = com.myassistant.app.control.NotificationStore.get(ctx).accounts()
                all.filter { (pa, _, _) -> pa.first in com.myassistant.app.control.Accounts.WHATSAPP || all.count { it.first.first == pa.first } > 1 }
                    .map { (pa, acc, n) ->
                        ChatAccount(pa.first, pa.second, acc, com.myassistant.app.control.Accounts.label(ctx, pa.first, pa.second, acc),
                            com.myassistant.app.control.Accounts.customName(ctx, pa.first, acc), n)
                    }
            }
            chatAccounts.clear(); chatAccounts.addAll(list)
        }
    }

    fun renameAccount(a: ChatAccount, name: String) {
        com.myassistant.app.control.Accounts.rename(getApplication(), a.pkg, a.raw, name)
        loadAccounts()
    }

    /** Builds and shows the report right now (to try it). */
    fun reportNow() {
        notice = "بيجهّز التقرير…"
        graph.appScope.launch {
            val ctx = getApplication<Application>()
            val msg = try {
                com.myassistant.app.mail.DailyReport.deliver(ctx, com.myassistant.app.mail.DailyReport.build(ctx))
                "التقرير جاهز — هتلاقيه في الإشعارات وفي المحادثات"
            } catch (e: Exception) {
                "معرفتش أعمل التقرير: ${e.message}"
            }
            withContext(Dispatchers.Main) { notice = msg; loadConversations() }
        }
    }

    // ---- Meaning model ----

    fun downloadMeaningModel() {
        graph.appScope.launch {
            graph.meaning.download()
            runCatching { graph.recall.backfill() }
        }
    }

    fun deleteMeaningModel() {
        graph.appScope.launch { graph.meaning.delete() }
    }

    // ---- Data hub: expenses, reminders, places, backup ----

    val expenses = mutableStateListOf<com.myassistant.app.finance.Expense>()
    var expenseMonthOffset by mutableStateOf(0)
        private set

    fun loadExpenses(monthOffset: Int = expenseMonthOffset) {
        expenseMonthOffset = monthOffset
        viewModelScope.launch {
            val ctx = getApplication<Application>()
            val list = withContext(Dispatchers.IO) {
                runCatching { com.myassistant.app.finance.ExpenseSync.scanSms(ctx) }
                val (from, to) = com.myassistant.app.finance.monthRange(monthOffset)
                com.myassistant.app.finance.ExpenseStore.get(ctx).list(from, to)
            }
            expenses.clear(); expenses.addAll(list)
        }
    }

    fun setExpenseCategory(id: Long, category: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { com.myassistant.app.finance.ExpenseStore.get(getApplication()).setCategory(id, category) }
            val i = expenses.indexOfFirst { it.id == id }
            if (i >= 0) expenses[i] = expenses[i].copy(category = category)
        }
    }

    fun deleteExpense(id: Long) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { com.myassistant.app.finance.ExpenseStore.get(getApplication()).delete(id) }
            expenses.removeAll { it.id == id }
        }
    }

    val reminders = mutableStateListOf<com.myassistant.app.reminders.Reminder>()
    val places = mutableStateListOf<com.myassistant.app.reminders.SavedPlace>()

    fun loadReminders() {
        viewModelScope.launch {
            val ctx = getApplication<Application>()
            val (r, p) = withContext(Dispatchers.IO) {
                val st = com.myassistant.app.reminders.ReminderStore.get(ctx)
                st.all() to st.places()
            }
            reminders.clear(); reminders.addAll(r)
            places.clear(); places.addAll(p)
        }
    }

    fun deleteReminder(r: com.myassistant.app.reminders.Reminder) {
        viewModelScope.launch {
            val ctx = getApplication<Application>()
            withContext(Dispatchers.IO) {
                com.myassistant.app.reminders.ReminderScheduler.cancel(ctx, r)
                com.myassistant.app.reminders.ReminderStore.get(ctx).delete(r.id)
            }
            reminders.removeAll { it.id == r.id }
        }
    }

    fun deletePlace(name: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { com.myassistant.app.reminders.ReminderStore.get(getApplication()).deletePlace(name) }
            places.removeAll { it.name == name }
        }
    }

    fun exportBackup(uri: android.net.Uri) {
        viewModelScope.launch {
            notice = try {
                val n = withContext(Dispatchers.IO) { com.myassistant.app.data.Backup.export(getApplication(), uri) }
                "اتحفظت النسخة الاحتياطية ($n ملف)"
            } catch (e: Exception) {
                "معرفتش أحفظ النسخة: ${e.message}"
            }
        }
    }

    /** Returns true if the app must restart. */
    suspend fun importBackup(uri: android.net.Uri): String = try {
        val n = withContext(Dispatchers.IO) { com.myassistant.app.data.Backup.restore(getApplication(), uri) }
        "OK:$n"
    } catch (e: Exception) {
        "ERR:${e.message}"
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
        // If the assistant is working inside another app, bring ours back so the user sees the question.
        runCatching {
            val app = getApplication<Application>()
            app.startActivity(
                android.content.Intent(app, com.myassistant.app.MainActivity::class.java)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
            )
        }
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

    fun setMemoryCategory(id: Long, category: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { graph.memory.setCategory(id, category) }
            val i = memories.indexOfFirst { it.id == id }
            if (i >= 0) memories[i] = memories[i].copy(category = category)
        }
    }

    fun addMemory(text: String, category: String = com.myassistant.app.memory.MemoryCategories.GENERAL) {
        if (text.isBlank()) return
        viewModelScope.launch {
            withContext(Dispatchers.IO) { graph.memory.add(text, category) }
            loadMemories()
            graph.indexSoon()
        }
    }

    fun deleteMemory(id: Long) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { graph.memory.delete(id) }
            memories.removeAll { it.id == id }
        }
    }
}
