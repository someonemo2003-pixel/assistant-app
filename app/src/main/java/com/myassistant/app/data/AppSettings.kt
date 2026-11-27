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

    private val _chatBrain = MutableStateFlow(prefs.getString(CHAT_BRAIN, "nvidia-kimi") ?: "")
    /** Provider id used first for conversation ("" = normal order). */
    val chatBrain: StateFlow<String> = _chatBrain.asStateFlow()
    fun setChatBrain(id: String) { prefs.edit().putString(CHAT_BRAIN, id).apply(); _chatBrain.value = id }

    private val _actionBrain = MutableStateFlow(prefs.getString(ACTION_BRAIN, "groq") ?: "")
    /** Provider id used first while operating the phone screen ("" = normal order). */
    val actionBrain: StateFlow<String> = _actionBrain.asStateFlow()
    fun setActionBrain(id: String) { prefs.edit().putString(ACTION_BRAIN, id).apply(); _actionBrain.value = id }

    /** Picked from the model menu for the current chat only (not saved; cleared on a new chat). Empty = use the default. */
    val sessionChat = MutableStateFlow("")
    val sessionAction = MutableStateFlow("")

    private val _chatMode = MutableStateFlow(com.myassistant.app.agent.ChatMode.of(prefs.getString(CHAT_MODE, null)))
    /** Who handles messages: auto, just talk, the phone, or OpenCode (kept until the user changes it). */
    val chatMode: StateFlow<com.myassistant.app.agent.ChatMode> = _chatMode.asStateFlow()
    fun setChatMode(m: com.myassistant.app.agent.ChatMode) { prefs.edit().putString(CHAT_MODE, m.name).apply(); _chatMode.value = m }

    private val _opencodeModel = MutableStateFlow(prefs.getString(OC_MODEL, "") ?: "")
    /** Model OpenCode uses ("provider/model"; "" = OpenCode's own default). */
    val opencodeModel: StateFlow<String> = _opencodeModel.asStateFlow()
    fun setOpencodeModel(id: String) { prefs.edit().putString(OC_MODEL, id).apply(); _opencodeModel.value = id }
    val sessionOpencode = MutableStateFlow("")
    fun effectiveOpencode(): String = sessionOpencode.value.ifEmpty { _opencodeModel.value }

    private val _opencodeModels = MutableStateFlow(
        (prefs.getString(OC_MODELS, "") ?: "").split('\n').filter { it.isNotBlank() },
    )
    /** Models OpenCode on the phone can use (last fetched list). */
    val opencodeModels: StateFlow<List<String>> = _opencodeModels.asStateFlow()
    fun setOpencodeModels(list: List<String>) { prefs.edit().putString(OC_MODELS, list.joinToString("\n")).apply(); _opencodeModels.value = list }

    /** What actually answers / runs phone actions right now. */
    fun effectiveChat(): String = sessionChat.value.ifEmpty { _chatBrain.value }
    fun effectiveAction(): String = sessionAction.value.ifEmpty { _actionBrain.value }

    private val _confirmSends = MutableStateFlow(prefs.getBoolean(CONFIRM_SENDS, false))
    /** Ask before sending messages/emails/SMS and calling. Off = just do it. */
    val confirmSends: StateFlow<Boolean> = _confirmSends.asStateFlow()
    fun setConfirmSends(on: Boolean) { prefs.edit().putBoolean(CONFIRM_SENDS, on).apply(); _confirmSends.value = on }

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
        const val CHAT_BRAIN = "chat_brain"
        const val CHAT_MODE = "chat_mode"
        const val CONFIRM_SENDS = "confirm_sends"
        const val ACTION_BRAIN = "action_brain"
        const val OC_MODEL = "opencode_model"
        const val OC_MODELS = "opencode_models"
    }
}
