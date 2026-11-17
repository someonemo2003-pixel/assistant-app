package com.myassistant.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel

@Composable
fun AppRoot(vm: AppViewModel = viewModel()) {
    BackHandler(enabled = vm.screen != Screen.Chat) {
        vm.screen = if (vm.screen == Screen.Learned) Screen.Settings else Screen.Chat
    }

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
        }
    }

    vm.confirmRequest?.let { req ->
        AlertDialog(
            onDismissRequest = { req.answer(false) },
            title = { Text("موافقة مطلوبة") },
            text = { Text("المساعد عايز ينفّذ: ${req.text}") },
            confirmButton = { TextButton(onClick = { req.answer(true) }) { Text("نفّذ") } },
            dismissButton = { TextButton(onClick = { req.answer(false) }) { Text("لا") } },
        )
    }
}
