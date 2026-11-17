package com.myassistant.app

import android.app.Application
import android.content.Context
import com.myassistant.app.agent.Agent
import com.myassistant.app.agent.MemoryExtractor
import com.myassistant.app.ai.ProviderRouter
import com.myassistant.app.ai.ProviderStore
import com.myassistant.app.data.AppSettings
import com.myassistant.app.data.ChatStore
import com.myassistant.app.fast.ExperienceStore
import com.myassistant.app.memory.MemoryStore
import com.myassistant.app.memory.SqliteMemoryStore
import com.myassistant.app.security.SecretStore
import com.myassistant.app.semantic.MeaningModel
import com.myassistant.app.semantic.Recall
import com.myassistant.app.control.PhoneButtonTool
import com.myassistant.app.control.ClearNotificationsTool
import com.myassistant.app.control.MissedMessagesTool
import com.myassistant.app.control.NotificationAlertTool
import com.myassistant.app.control.ReadNotificationsTool
import com.myassistant.app.control.ReplyNotificationTool
import com.myassistant.app.control.ReadScreenTool
import com.myassistant.app.control.ScrollTool
import com.myassistant.app.control.TapTool
import com.myassistant.app.control.TypeTextTool
import com.myassistant.app.tools.AddEventTool
import com.myassistant.app.tools.BrightnessTool
import com.myassistant.app.tools.CallTool
import com.myassistant.app.tools.DeviceStatusTool
import com.myassistant.app.tools.DraftSmsTool
import com.myassistant.app.tools.FindContactTool
import com.myassistant.app.tools.FlashlightTool
import com.myassistant.app.tools.ListAppsTool
import com.myassistant.app.tools.ListEventsTool
import com.myassistant.app.tools.ListFilesTool
import com.myassistant.app.tools.LocationTool
import com.myassistant.app.tools.OpenFileTool
import com.myassistant.app.tools.OpenSettingsTool
import com.myassistant.app.tools.OpenUrlTool
import com.myassistant.app.tools.ReadFileTool
import com.myassistant.app.tools.ReadSmsTool
import com.myassistant.app.tools.RecentCallsTool
import com.myassistant.app.tools.SearchFilesTool
import com.myassistant.app.tools.SendSmsTool
import com.myassistant.app.tools.SetVolumeTool
import com.myassistant.app.tools.ScreenTimeTool
import com.myassistant.app.tools.OpenAppTool
import com.myassistant.app.tools.RememberTool
import com.myassistant.app.tools.SearchChatsTool
import com.myassistant.app.tools.SearchMemoryTool
import com.myassistant.app.tools.SetAlarmTool
import com.myassistant.app.tools.SetTimerTool
import com.myassistant.app.tools.TeachShortcutTool
import com.myassistant.app.tools.ToolContext
import com.myassistant.app.tools.ToolRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class AssistantApp : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
        graph.startBackground()
    }
}

/** Wires everything together. New tools/providers plug in here. */
class AppGraph(context: Context) {
    /** For background work that should outlive a screen (e.g. memory extraction). */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val settings = AppSettings(context)
    val secrets = SecretStore(context)
    val providers = ProviderStore(context, secrets)
    val memory: MemoryStore = SqliteMemoryStore(context)
    val chats = ChatStore(context)
    val experience = ExperienceStore(context)
    val meaning = MeaningModel(context)
    val recall = Recall(meaning, memory, chats, experience)

    /** Tools that do things on the phone. Add a new capability here. */
    private val actionTools = listOf(
        SetAlarmTool(), SetTimerTool(), OpenAppTool(), DraftSmsTool(),
        // screen control
        ReadScreenTool(), TapTool(), TypeTextTool(), ScrollTool(), PhoneButtonTool(),
        // phone data
        ReadNotificationsTool(), MissedMessagesTool(), ReplyNotificationTool(), ClearNotificationsTool(), NotificationAlertTool(),
        FindContactTool(), CallTool(), SendSmsTool(), ReadSmsTool(), RecentCallsTool(),
        ListEventsTool(), AddEventTool(), LocationTool(),
        ListFilesTool(), SearchFilesTool(), ReadFileTool(), OpenFileTool(),
        // device
        ScreenTimeTool(), DeviceStatusTool(), SetVolumeTool(), FlashlightTool(), BrightnessTool(), OpenSettingsTool(), ListAppsTool(), OpenUrlTool(),
    )

    val tools = ToolRegistry(
        actionTools + listOf(
            RememberTool(),
            SearchMemoryTool(),
            SearchChatsTool(),
            TeachShortcutTool(actionTools.filter { it.learnable }.associate { it.name to it.description }),
        ),
    )

    val toolContext = ToolContext(context.applicationContext, memory, chats, experience, recall, settings)
    val router = ProviderRouter { providers.activeProviders() }

    val agent = Agent(
        router = router,
        tools = tools,
        memory = memory,
        toolContext = toolContext,
        learnFromAi = { settings.learnFromAi.value },
    )

    val memoryExtractor = MemoryExtractor(router, memory)

    /** Loads the meaning model (if downloaded) and indexes anything new. */
    fun startBackground() {
        appScope.launch {
            meaning.warmUp()
            runCatching { recall.backfill() }
        }
    }

    /** Index new chats/memories/learned phrases soon after they're created. */
    fun indexSoon() {
        if (!meaning.isReady) return
        appScope.launch { runCatching { recall.backfill(300) } }
    }
}
