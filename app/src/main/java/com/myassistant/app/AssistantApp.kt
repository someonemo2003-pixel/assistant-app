package com.myassistant.app

import android.app.Application
import android.content.Context
import com.myassistant.app.agent.Agent
import com.myassistant.app.ai.ProviderRouter
import com.myassistant.app.ai.ProviderStore
import com.myassistant.app.memory.MemoryStore
import com.myassistant.app.memory.SqliteMemoryStore
import com.myassistant.app.security.SecretStore
import com.myassistant.app.tools.DraftSmsTool
import com.myassistant.app.tools.OpenAppTool
import com.myassistant.app.tools.RememberTool
import com.myassistant.app.tools.SearchMemoryTool
import com.myassistant.app.tools.SetAlarmTool
import com.myassistant.app.tools.SetTimerTool
import com.myassistant.app.tools.ToolContext
import com.myassistant.app.tools.ToolRegistry

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
    val secrets = SecretStore(context)
    val providers = ProviderStore(context, secrets)
    val memory: MemoryStore = SqliteMemoryStore(context)

    val tools = ToolRegistry(
        listOf(
            SetAlarmTool(),
            SetTimerTool(),
            OpenAppTool(),
            DraftSmsTool(),
            RememberTool(),
            SearchMemoryTool(),
        ),
    )

    val agent = Agent(
        router = ProviderRouter { providers.activeProviders() },
        tools = tools,
        memory = memory,
        toolContext = ToolContext(context.applicationContext, memory),
    )
}
