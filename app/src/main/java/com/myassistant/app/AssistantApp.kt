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
import com.myassistant.app.tools.DraftSmsTool
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

class AssistantApp : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
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

    /** Tools that do things on the phone. */
    private val actionTools = listOf(
        SetAlarmTool(),
        SetTimerTool(),
        OpenAppTool(),
        DraftSmsTool(),
    )

    val tools = ToolRegistry(
        actionTools + listOf(
            RememberTool(),
            SearchMemoryTool(),
            SearchChatsTool(),
            TeachShortcutTool(actionTools.filter { it.learnable }.associate { it.name to it.description }),
        ),
    )

    val toolContext = ToolContext(context.applicationContext, memory, chats, experience)
    val router = ProviderRouter { providers.activeProviders() }

    val agent = Agent(
        router = router,
        tools = tools,
        memory = memory,
        toolContext = toolContext,
        learnFromAi = { settings.learnFromAi.value },
    )

    val memoryExtractor = MemoryExtractor(router, memory)
}
