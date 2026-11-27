package com.myassistant.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import com.myassistant.app.ui.theme.AppBackdrop

@Composable
fun AppRoot(vm: AppViewModel = viewModel()) {
    BackHandler(enabled = vm.screen != Screen.Chat) {
        vm.screen = Screen.Chat
    }

    Box(Modifier.fillMaxSize()) {
        AppBackdrop()
        AnimatedContent(
            targetState = vm.screen,
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            label = "screen",
        ) { screen ->
            when (screen) {
                Screen.Chat -> ChatScreen(vm)
                Screen.Settings -> SettingsScreen(vm)
                Screen.Memory -> MemoryScreen(vm)
                Screen.Chats -> ChatsScreen(vm)
                Screen.Learned -> LearnedScreen(vm)
                Screen.Permissions -> PermissionsScreen(vm)
                Screen.BlockApps -> BlockAppsScreen(vm)
            }
        }
    }

    vm.confirmRequest?.let { req ->
        AlertDialog(
            onDismissRequest = { req.answer(false) },
            title = { Text("موافقة مطلوبة") },
            text = {
                androidx.compose.foundation.layout.Box(
                    androidx.compose.ui.Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                ) { Text("المساعد عايز ينفّذ: ${req.text}") }
            },
            confirmButton = { TextButton(onClick = { req.answer(true) }) { Text("نفّذ") } },
            dismissButton = { TextButton(onClick = { req.answer(false) }) { Text("لا") } },
        )
    }
}
