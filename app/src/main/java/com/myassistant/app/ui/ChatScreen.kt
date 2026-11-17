package com.myassistant.app.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color
import com.myassistant.app.core.Role
import com.myassistant.app.ui.theme.NovaCyan
import com.myassistant.app.ui.theme.NovaNight
import com.myassistant.app.ui.theme.NovaPink
import com.myassistant.app.ui.theme.NovaViolet
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

    var menuOpen by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize()) {
    NovaBackground()
    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Nova", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleLarge)
                        Spacer(Modifier.size(8.dp))
                        // green = ready, red = needs an API key
                        Box(
                            Modifier.size(7.dp).clip(CircleShape)
                                .background(if (readyCount > 0) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error),
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { vm.screen = Screen.Settings }) {
                        Icon(Icons.Filled.Settings, "الإعدادات", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                actions = {
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Filled.MoreVert, "القايمة", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(text = { Text("محادثة جديدة") }, leadingIcon = { Icon(Icons.Filled.Add, null) },
                                onClick = { menuOpen = false; vm.newChat() })
                            DropdownMenuItem(text = { Text("المحادثات") }, leadingIcon = { Icon(Icons.AutoMirrored.Filled.List, null) },
                                onClick = { menuOpen = false; vm.screen = Screen.Chats })
                            DropdownMenuItem(text = { Text("بياناتي") }, leadingIcon = { Icon(Icons.Filled.AccountCircle, null) },
                                onClick = { menuOpen = false; vm.screen = Screen.Memory })
                        }
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = Color.Transparent),
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
                listening = listen.listening,
                level = listen.level,
                speaking = speaking,
                onSend = { vm.send(input); input = "" },
                onStop = { vm.stop() },
                onMic = { if (listen.listening || speaking) vm.stopVoice() else onMic() },
            )
            }
        },
    ) { padding ->
        if (vm.messages.isEmpty() && vm.error == null) {
            EmptyState(
                modifier = Modifier.padding(padding),
                active = listen.listening || speaking || vm.busy,
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
}

@Composable
private fun Bubble(text: String, mine: Boolean, footer: String? = null) {
    Box(Modifier.fillMaxWidth(), contentAlignment = if (mine) Alignment.CenterEnd else Alignment.CenterStart) {
        val shape = RoundedCornerShape(
            topStart = 22.dp, topEnd = 22.dp,
            bottomStart = if (mine) 22.dp else 6.dp,
            bottomEnd = if (mine) 6.dp else 22.dp,
        )
        val bg = if (mine) Modifier.background(Brush.linearGradient(listOf(Color(0xFF3B82F6), NovaViolet)), shape)
        else Modifier
            .background(Color.White.copy(alpha = 0.06f), shape)
            .border(1.dp, Color.White.copy(alpha = 0.08f), shape)
        Column(
            Modifier.widthIn(max = 320.dp).clip(shape).then(bg).padding(horizontal = 15.dp, vertical = 11.dp),
        ) {
            SelectionContainer {
                Text(text, style = MaterialTheme.typography.bodyLarge, color = if (mine) Color.White else MaterialTheme.colorScheme.onSurface)
            }
            if (footer != null) {
                Text(
                    footer,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.alpha(0.7f).padding(top = 4.dp),
                )
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
                .background(NovaCyan.copy(alpha = 0.08f))
                .border(1.dp, NovaCyan.copy(alpha = 0.18f), RoundedCornerShape(12.dp))
                .padding(horizontal = 10.dp, vertical = 6.dp)
                .widthIn(max = 320.dp),
        ) {
            Icon(
                Icons.Filled.Build, null,
                tint = NovaCyan,
                modifier = Modifier.size(14.dp),
            )
            Spacer(Modifier.size(6.dp))
            Text(
                "$title · $result",
                style = MaterialTheme.typography.labelMedium,
                color = NovaCyan.copy(alpha = 0.85f),
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
            .background(Color.White.copy(alpha = 0.06f))
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
                    .background(listOf(NovaCyan, NovaViolet, NovaPink)[i]),
            )
        }
    }
}

@Composable
private fun InputBar(
    value: String,
    onValueChange: (String) -> Unit,
    busy: Boolean,
    listening: Boolean,
    level: Float,
    speaking: Boolean,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onMic: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .imePadding()
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextField(
            value = value,
            onValueChange = onValueChange,
            placeholder = { Text("اكتب لـ Nova…", color = MaterialTheme.colorScheme.onSurfaceVariant) },
            modifier = Modifier
                .weight(1f)
                .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(26.dp)),
            shape = RoundedCornerShape(26.dp),
            maxLines = 5,
            colors = TextFieldDefaults.colors(
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                disabledIndicatorColor = Color.Transparent,
                focusedContainerColor = Color.White.copy(alpha = 0.06f),
                unfocusedContainerColor = Color.White.copy(alpha = 0.06f),
                cursorColor = NovaCyan,
            ),
        )
        Spacer(Modifier.size(10.dp))
        when {
            busy -> RoundButton(onStop) { Icon(Icons.Filled.Close, "إيقاف", tint = Color.White) }
            value.isNotBlank() -> RoundButton(onSend) { Icon(Icons.AutoMirrored.Filled.Send, "إرسال", tint = Color.White) }
            // The Nova ring is the mic: tap to talk, it glows and moves with your voice.
            else -> NovaRing(
                size = 54.dp,
                active = listening || speaking,
                level = if (listening) level else if (speaking) 0.35f else 0f,
                modifier = Modifier.clip(CircleShape).clickable(onClick = onMic),
            )
        }
    }
}

@Composable
private fun RoundButton(onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        Modifier
            .size(54.dp)
            .clip(CircleShape)
            .background(Brush.linearGradient(listOf(NovaCyan, NovaViolet, NovaPink)))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { content() }
}

/** The glowing Nova ring: breathes when idle, turns and swells with the voice when active. */
@Composable
fun NovaRing(size: androidx.compose.ui.unit.Dp, active: Boolean, level: Float = 0f, modifier: Modifier = Modifier) {
    val t = rememberInfiniteTransition(label = "ring")
    val breathe by t.animateFloat(
        initialValue = 0.94f, targetValue = 1.04f,
        animationSpec = infiniteRepeatable(tween(2600), RepeatMode.Reverse), label = "breathe",
    )
    val spin by t.animateFloat(
        initialValue = 0f, targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(if (active) 2600 else 24000, easing = androidx.compose.animation.core.LinearEasing)),
        label = "spin",
    )
    val voice by androidx.compose.animation.core.animateFloatAsState(level.coerceIn(0f, 1f), label = "voice")
    val scale = if (active) 1f + voice * 0.35f else breathe
    val glow by androidx.compose.animation.core.animateFloatAsState(if (active) 1f else 0.55f, label = "glow")
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        // soft halo behind the ring
        Box(
            Modifier
                .size(size)
                .graphicsLayer { scaleX = scale * 1.15f; scaleY = scale * 1.15f; alpha = glow * 0.6f }
                .background(Brush.radialGradient(listOf(NovaViolet.copy(alpha = 0.55f), Color.Transparent)), CircleShape),
        )
        androidx.compose.foundation.Image(
            painter = androidx.compose.ui.res.painterResource(com.myassistant.app.R.drawable.ic_launcher_art),
            contentDescription = "اتكلم",
            modifier = Modifier
                .size(size)
                .graphicsLayer { scaleX = scale * 1.25f; scaleY = scale * 1.25f; rotationZ = spin; alpha = 0.75f + glow * 0.25f },
        )
    }
}

/** Night sky behind the chat: deep navy with a violet glow on top and a cyan one at the bottom. */
@Composable
private fun NovaBackground() {
    Box(
        Modifier
            .fillMaxSize()
            .background(NovaNight)
            .drawBehind {
                drawCircle(
                    Brush.radialGradient(listOf(NovaViolet.copy(alpha = 0.22f), Color.Transparent),
                        center = androidx.compose.ui.geometry.Offset(size.width * 0.2f, 0f), radius = size.width * 0.9f),
                    radius = size.width * 0.9f, center = androidx.compose.ui.geometry.Offset(size.width * 0.2f, 0f),
                )
                drawCircle(
                    Brush.radialGradient(listOf(NovaCyan.copy(alpha = 0.10f), Color.Transparent),
                        center = androidx.compose.ui.geometry.Offset(size.width, size.height), radius = size.width),
                    radius = size.width, center = androidx.compose.ui.geometry.Offset(size.width, size.height),
                )
            },
    )
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
            .background(Color.White.copy(alpha = 0.06f))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                if (listening) "بسمعك…" else "بيتكلم…" + if (voiceMode) " (وبعدها هسمعك تاني)" else "",
                style = MaterialTheme.typography.labelMedium,
                color = NovaCyan,
            )
            if (listening && partial.isNotEmpty()) {
                Text(partial, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface, maxLines = 3)
            }
        }
        IconButton(onClick = onStop) { Icon(Icons.Filled.Close, "إيقاف", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

/** Empty chat: just the glowing Nova ring, breathing. */
@Composable
private fun EmptyState(
    modifier: Modifier,
    active: Boolean,
    needsKey: Boolean,
    openSettings: () -> Unit,
) {
    Column(
        modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        NovaRing(size = 200.dp, active = active)
        if (needsKey) {
            Spacer(Modifier.height(28.dp))
            androidx.compose.material3.TextButton(onClick = openSettings) { Text("حط مفتاح API من الإعدادات", color = NovaCyan) }
        }
    }
}
