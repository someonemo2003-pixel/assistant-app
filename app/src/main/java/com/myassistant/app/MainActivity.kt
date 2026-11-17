package com.myassistant.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import com.myassistant.app.ui.AppRoot
import com.myassistant.app.ui.AppViewModel
import com.myassistant.app.ui.theme.AssistantTheme

class MainActivity : ComponentActivity() {

    private val vm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = androidx.activity.SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = androidx.activity.SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) handle(intent)
        setContent {
            AssistantTheme {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    AppRoot(vm)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    override fun onResume() {
        super.onResume()
        vm.foreground = true
    }

    override fun onPause() {
        vm.foreground = false
        super.onPause()
    }

    /** Opened from the mic shortcut, the quick-settings tile, or the phone's assistant button → start listening. */
    private fun handle(intent: Intent?) {
        val listen = intent?.action == ACTION_LISTEN || intent?.action == Intent.ACTION_ASSIST ||
            intent?.action == Intent.ACTION_VOICE_COMMAND || intent?.getBooleanExtra(EXTRA_LISTEN, false) == true
        if (listen) vm.listenRequest++
        if (intent?.getBooleanExtra("open_chats", false) == true) vm.screen = com.myassistant.app.ui.Screen.Chats
    }

    companion object {
        const val ACTION_LISTEN = "com.myassistant.app.LISTEN"
        const val EXTRA_LISTEN = "listen"
    }
}
