package com.myassistant.app.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color
import com.myassistant.app.core.Role
import com.myassistant.app.ui.theme.NovaCyan
import com.myassistant.app.ui.theme.NovaPink
import com.myassistant.app.ui.theme.NovaViolet
import com.myassistant.app.ui.theme.UserBubble
import kotlin.math.cos
import kotlin.math.sin

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

    var sendFlashKey by remember { mutableIntStateOf(0) }
    var menuOpen by remember { mutableStateOf(false) }
    var pickerOpen by remember { mutableStateOf(false) }
    if (pickerOpen) ModelPicker(vm, configs.filter { it.enabled && vm.providerStore.hasKey(it.id) }) { pickerOpen = false }

    Box(Modifier.fillMaxSize()) {
    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    // Tap the name to choose which model answers (like a model switcher).
                    val sChat by vm.settings.sessionChat.collectAsState()
                    val dChat by vm.settings.chatBrain.collectAsState()
                    val current = configs.firstOrNull { it.id == sChat.ifEmpty { dChat } }?.name ?: "تلقائي"
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable { pickerOpen = true }.padding(horizontal = 10.dp, vertical = 2.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Nova", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleLarge)
                            Spacer(Modifier.size(8.dp))
                            // green = ready, red = needs an API key
                            Box(
                                Modifier.size(7.dp).clip(CircleShape)
                                    .background(if (readyCount > 0) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error),
                            )
                        }
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(50))
                                .clickable { pickerOpen = true }
                                .padding(start = 10.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(current, style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
                            Icon(Icons.Filled.KeyboardArrowDown, null, tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp))
                        }
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
                onSend = { vm.send(input); input = ""; sendFlashKey++ },
                sendFlashKey = sendFlashKey,
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
                openSettings = { vm.setupSection = "providers"; vm.screen = Screen.Settings },
            )
        } else {
            LazyColumn(
                state = listState,
                reverseLayout = true,
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                vm.error?.let { err -> item(key = "error") { ErrorCard(err) } }
                if (vm.busy) item(key = "typing") { WorkingLine() }
                // Steps the assistant ran are folded into one small line per task (tap to see them).
                val rows = buildList<List<com.myassistant.app.core.ChatMessage>> {
                    for (m in visible) {
                        val lastGroup = lastOrNull()
                        if (m.role == Role.TOOL && lastGroup != null && lastGroup.first().role == Role.TOOL) {
                            set(size - 1, lastGroup + m)
                        } else {
                            add(listOf(m))
                        }
                    }
                }
                items(rows, key = { it.first().id }) { group ->
                    val msg = group.first()
                    when (msg.role) {
                        Role.USER -> Bubble(msg.text, mine = true)
                        Role.ASSISTANT -> Bubble(msg.text, mine = false, footer = msg.providerName, thinking = msg.raw[com.myassistant.app.core.THINKING_KEY])
                        Role.TOOL -> StepsChip(
                            id = msg.id,
                            steps = group.asReversed().map { (vm.tools[it.toolName.orEmpty()]?.title ?: it.toolName.orEmpty()) to it.text },
                        )
                    }
                }
            }
        }
    }
    }
}

@Composable
private fun Bubble(text: String, mine: Boolean, footer: String? = null, thinking: String? = null) {
    var showThinking by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth(), contentAlignment = if (mine) Alignment.CenterEnd else Alignment.CenterStart) {
        val shape = RoundedCornerShape(
            topStart = 22.dp, topEnd = 22.dp,
            bottomStart = if (mine) 22.dp else 6.dp,
            bottomEnd = if (mine) 6.dp else 22.dp,
        )
        val bg = if (mine) Modifier.background(UserBubble, shape)
        else Modifier
            .background(MaterialTheme.colorScheme.surfaceContainerHigh, shape)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
        Column(
            Modifier.widthIn(max = 340.dp).clip(shape).then(bg).padding(horizontal = 16.dp, vertical = 13.dp),
        ) {
            SelectionContainer {
                Text(text, style = MaterialTheme.typography.bodyLarge, color = if (mine) Color.White else MaterialTheme.colorScheme.onSurface)
            }
            if (!thinking.isNullOrBlank()) {
                Text(
                    if (showThinking) "إخفاء التفكير" else "شوف تفكيره",
                    style = MaterialTheme.typography.labelSmall,
                    color = NovaCyan.copy(alpha = 0.8f),
                    modifier = Modifier.padding(top = 6.dp).clickable { showThinking = !showThinking },
                )
                if (showThinking) {
                    Text(
                        thinking.trim(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
            if (footer != null) {
                Text(
                    footer,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 5.dp),
                )
            }
        }
    }
}

private val failMarks = listOf("Error", "Unknown tool", "Permission not granted", "The user declined", "Could not", "Cannot", "فشل")

/** One quiet line for the steps behind an answer: "8 خطوات ✓" — tap to see each step. */
@Composable
private fun StepsChip(id: String, steps: List<Pair<String, String>>) {
    var open by rememberSaveable(id) { mutableStateOf(false) }
    val failed = steps.count { (_, r) -> failMarks.any { r.trimStart().startsWith(it) } }
    val label = when {
        steps.size == 1 -> steps.first().first
        else -> "${steps.size} خطوات"
    } + if (failed > 0) " · $failed مكملتش" else ""
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable { open = !open }
            .padding(horizontal = 4.dp, vertical = 2.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(6.dp).clip(CircleShape)
                    .background(if (failed > 0) MaterialTheme.colorScheme.error.copy(alpha = 0.8f) else NovaCyan.copy(alpha = 0.7f)),
            )
            Spacer(Modifier.size(8.dp))
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (open) {
            steps.forEach { (title, result) ->
                Text(
                    "• $title: " + result.lineSequence().firstOrNull().orEmpty(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                    maxLines = 2,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 14.dp, top = 4.dp),
                )
            }
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

/** While Nova works: what it's doing right now, how long it's been, and its latest thought (if the model shares it). */
@Composable
private fun WorkingLine() {
    val now by com.myassistant.app.agent.AgentStatus.now.collectAsState()
    val thought by com.myassistant.app.agent.AgentStatus.thought.collectAsState()
    var seconds by remember { mutableStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) {
            seconds = (System.currentTimeMillis() - com.myassistant.app.agent.AgentStatus.startedAt) / 1000
            kotlinx.coroutines.delay(1000)
        }
    }
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TypingIndicator()
            Spacer(Modifier.size(10.dp))
            Text(
                (now ?: "بيفكّر…") + if (seconds >= 3) "  ${seconds}ث" else "",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
            )
        }
        thought?.let {
            Text(
                it.lines().filter { l -> l.isNotBlank() }.takeLast(3).joinToString("\n"),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                maxLines = 3,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 8.dp, top = 6.dp),
            )
        }
    }
}

@Composable
private fun TypingIndicator() {
    val transition = rememberInfiniteTransition(label = "typing")
    val halo by transition.animateFloat(
        initialValue = 0.06f,
        targetValue = 0.18f,
        animationSpec = infiniteRepeatable(tween(1500), RepeatMode.Reverse),
        label = "thinkingGlow",
    )
    Row(
        Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.08f + halo * 0.20f))
            .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = halo), RoundedCornerShape(20.dp))
            .padding(horizontal = 15.dp, vertical = 12.dp),
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
    listening: Boolean,
    level: Float,
    speaking: Boolean,
    onSend: () -> Unit,
    sendFlashKey: Int,
    onStop: () -> Unit,
    onMic: () -> Unit,
) {
    val sendFlash = remember { androidx.compose.animation.core.Animatable(0f) }
    LaunchedEffect(sendFlashKey) {
        if (sendFlashKey > 0) {
            sendFlash.snapTo(1f)
            sendFlash.animateTo(0f, animationSpec = tween(520))
        }
    }
    val sendFlashColor = MaterialTheme.colorScheme.primary
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
            modifier = Modifier
                .weight(1f)
                .shadow(18.dp, RoundedCornerShape(26.dp))
                .clip(RoundedCornerShape(26.dp))
                .background(
                    Brush.linearGradient(
                        listOf(
                            Color.White.copy(alpha = 0.11f),
                            Color(0xFF282638).copy(alpha = 0.94f),
                            Color(0xFF191D28).copy(alpha = 0.92f),
                        ),
                    ),
                )
                .border(
                    1.dp,
                    Brush.linearGradient(
                        listOf(Color.White.copy(alpha = 0.34f), NovaViolet.copy(alpha = 0.48f), Color.White.copy(alpha = 0.12f)),
                    ),
                    RoundedCornerShape(26.dp),
                )
                .drawBehind {
                    if (sendFlash.value > 0f) {
                        drawRoundRect(
                            color = sendFlashColor.copy(alpha = sendFlash.value * 0.16f),
                            cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.height / 2f),
                        )
                    }
                }
                .semantics { contentDescription = "اكتب رسالة" },
            shape = RoundedCornerShape(26.dp),
            maxLines = 5,
            colors = TextFieldDefaults.colors(
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                disabledIndicatorColor = Color.Transparent,
                focusedContainerColor = Color.Transparent,
                unfocusedContainerColor = Color.Transparent,
                cursorColor = MaterialTheme.colorScheme.primary,
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
                modifier = Modifier.clip(CircleShape).clickable(onClick = onMic)
                    .semantics { contentDescription = "اتكلم مع Nova" },
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
            .background(Brush.linearGradient(listOf(NovaViolet, NovaPink)))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { content() }
}

/** A small animated glass-like orb with orbiting 3D rings; it reacts to the voice level. */
@Composable
fun NovaRing(size: androidx.compose.ui.unit.Dp, active: Boolean, level: Float = 0f, modifier: Modifier = Modifier) {
    val t = rememberInfiniteTransition(label = "orb")
    val breathe by t.animateFloat(
        initialValue = 0.97f, targetValue = 1.025f,
        animationSpec = infiniteRepeatable(tween(3200), RepeatMode.Reverse), label = "breathe",
    )
    val orbit by t.animateFloat(
        initialValue = 0f, targetValue = 360f,
        animationSpec = infiniteRepeatable(
            tween(if (active) 6500 else 36000, easing = androidx.compose.animation.core.LinearEasing),
        ),
        label = "orbit",
    )
    val voice by androidx.compose.animation.core.animateFloatAsState(level.coerceIn(0f, 1f), label = "voice")
    val scale = if (active) 1f + voice * 0.08f else breathe
    val glow by androidx.compose.animation.core.animateFloatAsState(if (active) 1f else 0.82f, label = "glow")
    Box(
        modifier.size(size).graphicsLayer { scaleX = scale; scaleY = scale },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val cx = size.width / 2f
            val cy = size.height / 2f
            val unit = size.minDimension
            val radius = unit * 0.265f
            val radians = Math.PI.toFloat() / 180f

            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        NovaViolet.copy(alpha = glow * 0.58f),
                        NovaViolet.copy(alpha = glow * 0.24f),
                        Color.Transparent,
                    ),
                    center = Offset(cx, cy),
                    radius = unit * 0.62f,
                ),
                radius = unit * 0.62f,
                center = Offset(cx, cy),
            )
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(NovaCyan.copy(alpha = glow * 0.26f), Color.Transparent),
                    center = Offset(cx - radius * 0.38f, cy + radius * 0.12f),
                    radius = radius * 1.65f,
                ),
                radius = radius * 1.65f,
                center = Offset(cx - radius * 0.38f, cy + radius * 0.12f),
            )

            fun orbitPath(
                foreground: Boolean,
                orbitScale: Float,
                tilt: Float,
                inclination: Float,
                phase: Float,
                tint: Color,
            ) {
                val steps = 160
                var segment: Path? = null
                for (i in 0..steps) {
                    val angle = (i.toFloat() / steps * 360f + orbit + phase) * radians
                    val depth = sin(angle) * sin(inclination)
                    val isForeground = depth >= 0f
                    val x = cx + cos(angle) * radius * orbitScale
                    val y = cy + sin(angle) * radius * orbitScale * tilt
                    if (isForeground == foreground) {
                        val path = segment
                        if (path == null) {
                            segment = Path().apply { moveTo(x, y) }
                        } else {
                            path.lineTo(x, y)
                        }
                    } else {
                        segment?.let {
                            drawPath(
                                it,
                                tint.copy(alpha = if (foreground) 0.82f else 0.30f),
                                style = Stroke(width = unit * 0.012f),
                            )
                        }
                        segment = null
                    }
                }
                segment?.let {
                    drawPath(
                        it,
                        tint.copy(alpha = if (foreground) 0.82f else 0.30f),
                        style = Stroke(width = unit * 0.012f),
                    )
                }
            }

            val rings = listOf(
                Triple(1.72f, 0.40f, 0.92f) to NovaViolet,
                Triple(1.48f, 0.68f, 0.54f) to NovaCyan,
            )
            rings.forEach { (geometry, tint) ->
                orbitPath(false, geometry.first, geometry.second, geometry.third, 0f, tint)
            }

            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(Color(0xFFE0D9FF), Color(0xFF887BD2), Color(0xFF454467), Color(0xFF1D202B)),
                    center = Offset(cx - radius * 0.36f, cy - radius * 0.42f),
                    radius = radius * 1.95f,
                ),
                radius = radius,
                center = Offset(cx, cy),
            )
            val glintAngle = orbit * radians
            val glintCenter = Offset(
                cx - radius * 0.40f + cos(glintAngle) * radius * 0.10f,
                cy - radius * 0.43f + sin(glintAngle) * radius * 0.08f,
            )
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(Color.White.copy(alpha = 0.92f), NovaViolet.copy(alpha = 0.32f), Color.Transparent),
                    center = glintCenter,
                    radius = radius * 0.82f,
                ),
                radius = radius * 0.82f,
                center = glintCenter,
            )

            rings.forEach { (geometry, tint) ->
                orbitPath(true, geometry.first, geometry.second, geometry.third, 0f, tint)
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

/** Empty chat: the animated orb becomes the focus without a spinning app icon. */
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


/**
 * Model menu: who answers (chat) and who runs phone actions. By default a pick applies to this chat only;
 * "خليه الافتراضي" saves it for every chat.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModelPicker(vm: AppViewModel, providers: List<com.myassistant.app.ai.ProviderConfig>, onDismiss: () -> Unit) {
    val s = vm.settings
    val sChat by s.sessionChat.collectAsState()
    val sAction by s.sessionAction.collectAsState()
    val dChat by s.chatBrain.collectAsState()
    val dAction by s.actionBrain.collectAsState()
    var asDefault by remember { mutableStateOf(false) }

    fun pick(chat: Boolean, id: String) {
        if (asDefault) {
            if (chat) { s.setChatBrain(id); s.sessionChat.value = "" } else { s.setActionBrain(id); s.sessionAction.value = "" }
        } else {
            if (chat) s.sessionChat.value = id else s.sessionAction.value = id
        }
    }

    androidx.compose.material3.ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp).verticalScroll(androidx.compose.foundation.rememberScrollState())) {
            Text("اختيار الموديل", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
            Text(
                "اختار مين يرد، أو مين ينفّذ أوامرك على الموبايل.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
            )
            ModelSection("يرد عليا", providers, sChat, dChat) { pick(true, it) }
            ModelSection("ينفّذ الأوامر على الموبايل", providers, sAction, dAction) { pick(false, it) }
            OpenCodeSection(vm, asDefault)
            Row(
                Modifier.fillMaxWidth().padding(top = 14.dp).clickable { asDefault = !asDefault },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                androidx.compose.material3.Checkbox(checked = asDefault, onCheckedChange = { asDefault = it })
                Text("خلّي اختياري الافتراضي لكل المحادثات", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface)
            }
            if (providers.isEmpty()) Text("مفيش مزوّدين جاهزين. قول لـ Nova: عايز أضيف مزوّد.", color = MaterialTheme.colorScheme.error)
        }
    }
}

/** OpenCode's models (the coding agent in Termux): pick one for this chat or as the default. */
@Composable
private fun OpenCodeSection(vm: AppViewModel, asDefault: Boolean) {
    val s = vm.settings
    val models by s.opencodeModels.collectAsState()
    val session by s.sessionOpencode.collectAsState()
    val default by s.opencodeModel.collectAsState()
    var query by remember { mutableStateOf("") }
    val current = session.ifEmpty { default }
    fun pick(id: String) { if (asDefault) { s.setOpencodeModel(id); s.sessionOpencode.value = "" } else s.sessionOpencode.value = id }

    Row(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("OpenCode (البرمجة)", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f))
        Text(
            if (vm.opencodeLoading) "بيحمّل…" else "تحديث",
            style = MaterialTheme.typography.labelMedium, color = NovaCyan,
            modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { vm.refreshOpencodeModels() }.padding(6.dp),
        )
    }
    vm.opencodeError?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error) }
    if (models.isEmpty()) {
        Text("دوس تحديث علشان أجيب موديلات OpenCode من Termux.", style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    if (models.size > 8) {
        androidx.compose.material3.OutlinedTextField(
            value = query, onValueChange = { query = it }, singleLine = true,
            placeholder = { Text("دوّر على موديل") }, modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
        )
    }
    val shown = (listOf("") + models).filter { query.isBlank() || it.contains(query.trim(), true) }
        .sortedByDescending { it == current }.take(30)
    shown.forEach { id ->
        val on = id == current
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                .background(if (on) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer)
                .border(1.dp, if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(14.dp))
                .clickable { pick(id) }
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(if (id.isEmpty()) "اللي OpenCode مختاره" else id.substringAfter('/'),
                    style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface, maxLines = 2)
                if (id.isNotEmpty()) Text(id.substringBefore('/'), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
            if (id == default) Text("الافتراضي", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            if (on) { Spacer(Modifier.size(8.dp)); Text("مختار", style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer) }
        }
    }
}

@Composable
private fun ModelSection(
    title: String,
    providers: List<com.myassistant.app.ai.ProviderConfig>,
    selected: String,
    default: String,
    onPick: (String) -> Unit,
) {
    Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(top = 18.dp, bottom = 6.dp))
    providers.forEach { c ->
        val on = (selected.ifEmpty { default }) == c.id
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                .background(if (on) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer)
                .border(1.dp, if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(14.dp))
                .clickable { onPick(c.id) }
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(c.name, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                Text(c.model, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
            }
            if (c.id == default) Text("الافتراضي", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            if (on) { Spacer(Modifier.size(8.dp)); Text("مختار", style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer) }
        }
    }
}
