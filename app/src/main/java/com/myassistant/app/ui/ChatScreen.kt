package com.myassistant.app.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color
import com.myassistant.app.core.Role
import com.myassistant.app.ui.theme.UserBubble

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(vm: AppViewModel) {
    val configs by vm.providerStore.configs.collectAsState()
    val keysVersion by vm.providerStore.keysVersion.collectAsState()
    val readyCount = remember(configs, keysVersion) {
        configs.count { it.enabled && vm.providerStore.hasKey(it.id) }
    }

    var input by rememberSaveable { mutableStateOf("") }
    val listen by vm.listener.state.collectAsState()
    val speaking by vm.speaker.speaking.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current
    val micPermission = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) { granted -> if (granted) vm.startListening() else vm.notice = "محتاج صلاحية الميكروفون عشان أسمعك." }
    val onMic: () -> Unit = {
        if (androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            vm.startListening()
        } else {
            micPermission.launch(android.Manifest.permission.RECORD_AUDIO)
        }
    }
    LaunchedEffect(vm.listenRequest) { if (vm.listenRequest > 0) onMic() }
    LaunchedEffect(listen.error) { listen.error?.let { vm.notice = it } }
    val visible = vm.messages.filter { it.role != Role.ASSISTANT || it.text.isNotBlank() }.asReversed()
    val listState = rememberLazyListState()
    LaunchedEffect(vm.messages.size, vm.busy, vm.error) { listState.animateScrollToItem(0) }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("مساعدي", fontWeight = FontWeight.Bold)
                        Text(
                            if (readyCount > 0) "$readyCount مزوّد جاهز" else "محتاج مفتاح API — افتح الإعدادات",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (readyCount > 0) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { vm.screen = Screen.Settings }) { Icon(Icons.Filled.Settings, "الإعدادات") }
                },
                actions = {
                    IconButton(onClick = { vm.screen = Screen.Chats }) { Icon(Icons.AutoMirrored.Filled.List, "المحادثات") }
                    IconButton(onClick = { vm.screen = Screen.Memory }) { Icon(Icons.Filled.Star, "الذاكرة") }
                    if (vm.messages.isNotEmpty()) {
                        IconButton(onClick = { vm.newChat() }) { Icon(Icons.Filled.Add, "محادثة جديدة") }
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
        bottomBar = {
            Column {
            vm.notice?.let { n ->
                Text(
                    n,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    textAlign = TextAlign.Center,
                )
            }
            VoiceBar(
                listening = listen.listening,
                partial = listen.partial,
                level = listen.level,
                speaking = speaking,
                voiceMode = vm.voiceMode,
                onStop = { vm.stopVoice() },
            )
            InputBar(
                value = input,
                onValueChange = { input = it },
                busy = vm.busy,
                onSend = { vm.send(input); input = "" },
                onStop = { vm.stop() },
                onMic = onMic,
            )
            }
        },
    ) { padding ->
        if (vm.messages.isEmpty() && vm.error == null) {
            EmptyState(
                modifier = Modifier.padding(padding),
                onSuggestion = { vm.send(it) },
                needsKey = readyCount == 0,
                openSettings = { vm.screen = Screen.Settings },
            )
        } else {
            LazyColumn(
                state = listState,
                reverseLayout = true,
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                vm.error?.let { err -> item(key = "error") { ErrorCard(err) } }
                if (vm.busy) item(key = "typing") { TypingIndicator() }
                items(visible, key = { it.id }) { msg ->
                    when (msg.role) {
                        Role.USER -> Bubble(msg.text, mine = true)
                        Role.ASSISTANT -> Bubble(msg.text, mine = false, footer = msg.providerName)
                        Role.TOOL -> ToolChip(vm.tools[msg.toolName.orEmpty()]?.title ?: msg.toolName.orEmpty(), msg.text)
                    }
                }
            }
        }
    }
}

@Composable
private fun Bubble(text: String, mine: Boolean, footer: String? = null) {
    Box(Modifier.fillMaxWidth(), contentAlignment = if (mine) Alignment.CenterEnd else Alignment.CenterStart) {
        val shape = RoundedCornerShape(
            topStart = 20.dp, topEnd = 20.dp,
            bottomStart = if (mine) 20.dp else 6.dp,
            bottomEnd = if (mine) 6.dp else 20.dp,
        )
        Surface(
            shape = shape,
            color = if (mine) UserBubble else MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = if (mine) Color.White else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.widthIn(max = 320.dp),
        ) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                SelectionContainer { Text(text, style = MaterialTheme.typography.bodyLarge) }
                if (footer != null) {
                    Text(
                        footer,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.alpha(0.55f).padding(top = 4.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun ToolChip(title: String, result: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.tertiaryContainer)
                .padding(horizontal = 10.dp, vertical = 6.dp)
                .widthIn(max = 320.dp),
        ) {
            Icon(
                Icons.Filled.Build, null,
                tint = MaterialTheme.colorScheme.onTertiaryContainer,
                modifier = Modifier.size(14.dp),
            )
            Spacer(Modifier.size(6.dp))
            Text(
                "$title · $result",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
                maxLines = 3,
            )
        }
    }
}

@Composable
private fun ErrorCard(text: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.errorContainer)
            .padding(12.dp),
    ) {
        Icon(Icons.Filled.Warning, null, tint = MaterialTheme.colorScheme.onErrorContainer)
        Spacer(Modifier.size(8.dp))
        Text(text, color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun TypingIndicator() {
    val transition = rememberInfiniteTransition(label = "typing")
    Row(
        Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        repeat(3) { i ->
            val a by transition.animateFloat(
                initialValue = 0.25f, targetValue = 1f,
                animationSpec = infiniteRepeatable(tween(500, delayMillis = i * 150), RepeatMode.Reverse),
                label = "dot$i",
            )
            Box(
                Modifier
                    .size(8.dp)
                    .alpha(a)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
            )
        }
    }
}

@Composable
private fun InputBar(
    value: String,
    onValueChange: (String) -> Unit,
    busy: Boolean,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onMic: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp) {
        Row(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            TextField(
                value = value,
                onValueChange = onValueChange,
                placeholder = { Text("اكتب طلبك…") },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(24.dp),
                maxLines = 5,
                colors = TextFieldDefaults.colors(
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    disabledIndicatorColor = Color.Transparent,
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                ),
            )
            Spacer(Modifier.size(8.dp))
            if (busy) {
                FilledIconButton(onClick = onStop, modifier = Modifier.size(52.dp)) {
                    Icon(Icons.Filled.Close, "إيقاف")
                }
            } else if (value.isBlank()) {
                FilledIconButton(onClick = onMic, modifier = Modifier.size(52.dp)) {
                    Icon(Icons.Filled.Mic, "اتكلم")
                }
            } else {
                FilledIconButton(
                    onClick = onSend,
                    modifier = Modifier.size(52.dp),
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, "إرسال")
                }
            }
        }
    }
}

/** Shows what the assistant is hearing, or that it is talking, with a button to stop. */
@Composable
private fun VoiceBar(
    listening: Boolean,
    partial: String,
    level: Float,
    speaking: Boolean,
    voiceMode: Boolean,
    onStop: () -> Unit,
) {
    if (!listening && !speaking) return
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.primaryContainer)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val pulse by rememberInfiniteTransition(label = "mic").animateFloat(
            initialValue = 0.85f, targetValue = 1.15f,
            animationSpec = infiniteRepeatable(tween(600), RepeatMode.Reverse), label = "pulse",
        )
        val scale = if (listening) (0.9f + level * 0.5f).coerceAtMost(1.4f) else pulse
        Box(
            Modifier
                .size(36.dp * scale)
                .clip(CircleShape)
                .background(if (listening) UserBubble else MaterialTheme.colorScheme.tertiary),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (listening) Icons.Filled.Mic else Icons.AutoMirrored.Filled.VolumeUp, null,
                tint = Color.White, modifier = Modifier.size(20.dp),
            )
        }
        Spacer(Modifier.size(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                if (listening) "بسمعك…" else "بيتكلم…" + if (voiceMode) " (وبعدها هسمعك تاني)" else "",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            if (listening && partial.isNotEmpty()) {
                Text(partial, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onPrimaryContainer, maxLines = 3)
            }
        }
        IconButton(onClick = onStop) { Icon(Icons.Filled.Close, "إيقاف", tint = MaterialTheme.colorScheme.onPrimaryContainer) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EmptyState(
    modifier: Modifier,
    onSuggestion: (String) -> Unit,
    needsKey: Boolean,
    openSettings: () -> Unit,
) {
    Column(
        modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            Modifier
                .size(88.dp)
                .clip(CircleShape)
                .background(
                    Brush.linearGradient(
                        listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.secondary),
                    ),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.Star, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(40.dp))
        }
        Spacer(Modifier.height(20.dp))
        Text("أهلاً! أنا مساعدك", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Text(
            "اطلب أي حاجة بكلامك العادي — اكتب أو دوس 🎤 واتكلم",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        if (needsKey) {
            SuggestionChip(onClick = openSettings, label = { Text("ابدأ: حط مفتاح Gemini المجاني") })
        } else {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                listOf(
                    "صحّيني بكرة الساعة 7 الصبح",
                    "افتح يوتيوب",
                    "اعمل مؤقت 10 دقايق",
                    "افتكر إن عيد ميلاد أمي يوم 5 مارس",
                ).forEach { s -> SuggestionChip(onClick = { onSuggestion(s) }, label = { Text(s) }) }
            }
        }
    }
}
