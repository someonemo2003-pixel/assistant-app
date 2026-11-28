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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Menu
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
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
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
    LaunchedEffect(vm.modelPickerRequest) { if (vm.modelPickerRequest) { pickerOpen = true; vm.modelPickerRequest = false } }
    if (pickerOpen) ModelPicker(vm, configs.filter { it.enabled && vm.providerStore.hasKey(it.id) }) { pickerOpen = false }

    val haze = remember { dev.chrisbanes.haze.HazeState() }
    val glass = dev.chrisbanes.haze.HazeStyle(
        backgroundColor = MaterialTheme.colorScheme.surface,
        tint = dev.chrisbanes.haze.HazeTint(MaterialTheme.colorScheme.surface.copy(alpha = 0.88f)),
        blurRadius = 24.dp,
        noiseFactor = 0.04f,
    )
    Box(Modifier.fillMaxSize()) {
    val wash = MaterialTheme.colorScheme.primaryContainer
    Box(
        Modifier.fillMaxSize().drawBehind {
            drawRect(Brush.verticalGradient(
                0f to Color.Transparent, 0.55f to Color.Transparent, 1f to wash.copy(alpha = 0.55f),
            ))
        },
    )
    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            androidx.compose.material3.TopAppBar(
                modifier = Modifier.hazeEffect(state = haze, style = glass),
                navigationIcon = {
                    IconButton(onClick = { vm.screen = Screen.Chats }) {
                        Icon(Icons.Filled.Menu, "المحادثات", tint = MaterialTheme.colorScheme.onSurface)
                    }
                },
                title = {
                    // Tap the model's name to switch (like Gemini's model menu).
                    val sChat by vm.settings.sessionChat.collectAsState()
                    val dChat by vm.settings.chatBrain.collectAsState()
                    val current = configs.firstOrNull { it.id == sChat.ifEmpty { dChat } }?.name ?: "Nova"
                    Row(
                        Modifier.clip(RoundedCornerShape(50)).clickable { pickerOpen = true }.padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(current, style = MaterialTheme.typography.titleLarge, maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            modifier = Modifier.widthIn(max = 170.dp))
                        Icon(Icons.Filled.KeyboardArrowDown, null, tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(22.dp))
                        if (readyCount == 0) {
                            Spacer(Modifier.size(6.dp))
                            Box(Modifier.size(7.dp).clip(CircleShape).background(MaterialTheme.colorScheme.error))
                        }
                    }
                },
                actions = {
                    IconButton(onClick = { vm.newChat() }) {
                        Icon(Icons.Filled.Edit, "محادثة جديدة", tint = MaterialTheme.colorScheme.onSurface)
                    }
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Filled.MoreVert, "القايمة", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(text = { Text("بياناتي") }, leadingIcon = { Icon(Icons.Filled.AccountCircle, null) },
                                onClick = { menuOpen = false; vm.screen = Screen.Memory })
                            DropdownMenuItem(text = { Text("البرامج المحظورة") }, leadingIcon = { Icon(Icons.Filled.Lock, null) },
                                onClick = { menuOpen = false; vm.runCommand("/blockapps") })
                            DropdownMenuItem(text = { Text("المتصفح والمواقع") }, leadingIcon = { Icon(Icons.Filled.Language, null) },
                                onClick = { menuOpen = false; vm.runCommand("/extension") })
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
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
            SlashSuggestions(input) { cmd -> input = ""; vm.runCommand(cmd) }
            ModeBadge(vm)
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
                vm = vm,
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
                // Messages scroll under the glass bars.
                modifier = Modifier.fillMaxSize().hazeSource(haze),
                contentPadding = PaddingValues(
                    start = 16.dp, end = 16.dp,
                    top = padding.calculateTopPadding() + 14.dp,
                    bottom = padding.calculateBottomPadding() + 14.dp,
                ),
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
                    Box(Modifier.fillMaxWidth().animateItem(
                        fadeInSpec = androidx.compose.animation.core.spring(stiffness = androidx.compose.animation.core.Spring.StiffnessMediumLow),
                        placementSpec = androidx.compose.animation.core.spring(
                            dampingRatio = 0.72f, stiffness = androidx.compose.animation.core.Spring.StiffnessMediumLow,
                            visibilityThreshold = androidx.compose.ui.unit.IntOffset.VisibilityThreshold,
                        ),
                    )) {
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
        // Google style: your messages in a soft bubble, Nova's answers as plain text.
        val bg = if (mine) Modifier.background(MaterialTheme.colorScheme.secondaryContainer, shape) else Modifier
        Column(
            Modifier.widthIn(max = if (mine) 320.dp else 600.dp).clip(shape).then(bg)
                .padding(horizontal = if (mine) 16.dp else 4.dp, vertical = if (mine) 11.dp else 4.dp),
        ) {
            SelectionContainer {
                Text(text, style = MaterialTheme.typography.bodyLarge,
                    color = if (mine) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface)
            }
            if (!thinking.isNullOrBlank()) {
                Text(
                    if (showThinking) "إخفاء التفكير" else "شوف تفكيره",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
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
                    .background(if (failed > 0) MaterialTheme.colorScheme.error.copy(alpha = 0.8f) else MaterialTheme.colorScheme.primary),
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

@OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun TypingIndicator() {
    // Google's loading indicator: a shape that slowly morphs while Nova works.
    androidx.compose.material3.LoadingIndicator(modifier = Modifier.size(36.dp))
}

/** While the message starts with "/", shows the matching commands; a tap opens that page. */
@Composable
private fun SlashSuggestions(input: String, onPick: (String) -> Unit) {
    val list = SlashCommands.matching(input)
    if (list.isEmpty()) return
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp)
            .shadow(4.dp, RoundedCornerShape(24.dp))
            .clip(RoundedCornerShape(24.dp)).background(MaterialTheme.colorScheme.surfaceContainerLowest)
            .heightIn(max = 300.dp).verticalScroll(androidx.compose.foundation.rememberScrollState())
            .padding(vertical = 6.dp),
    ) {
        list.forEach { c ->
            Row(
                Modifier.fillMaxWidth().clickable { onPick(c.command) }.padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.secondaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        when (c.command) {
                            "/blockapps" -> Icons.Filled.Lock
                            "/update" -> Icons.Filled.Refresh
                            "/extension" -> Icons.Filled.Language
                            "/tasks" -> Icons.Filled.CheckCircle
                            "/web" -> Icons.Filled.Search
                            else -> Icons.Filled.Edit
                        },
                        null, tint = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.size(20.dp),
                    )
                }
                Spacer(Modifier.size(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(c.title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
                    // Commands are Latin: keep them left-to-right so "/" stays in front and never wraps.
                    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                        Text(
                            c.command,
                            style = MaterialTheme.typography.bodySmall.copy(textDirection = androidx.compose.ui.text.style.TextDirection.Ltr),
                            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, softWrap = false,
                        )
                    }
                }
            }
        }
    }
}

/** Shows the chosen mode when it isn't automatic; tap to go back to automatic. */
@Composable
private fun ModeBadge(vm: AppViewModel) {
    val mode by vm.settings.chatMode.collectAsState()
    if (mode == com.myassistant.app.agent.ChatMode.AUTO) return
    Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.Center) {
        androidx.compose.material3.InputChip(
            selected = true,
            onClick = { vm.settings.setChatMode(com.myassistant.app.agent.ChatMode.AUTO) },
            label = { Text("الوضع: ${mode.label}") },
            trailingIcon = { Icon(Icons.Filled.Close, "رجوع للتلقائي", modifier = Modifier.size(16.dp)) },
        )
    }
}

/** Pick who handles the next messages: Nova decides, just talk, the phone, or OpenCode. */
@Composable
private fun ModeRow(vm: AppViewModel) {
    val mode by vm.settings.chatMode.collectAsState()
    Row(
        Modifier.fillMaxWidth().horizontalScroll(androidx.compose.foundation.rememberScrollState())
            .padding(start = 12.dp, end = 12.dp, top = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        com.myassistant.app.agent.ChatMode.entries.forEach { m ->
            val on = m == mode
            androidx.compose.material3.FilterChip(
                selected = on,
                onClick = { vm.settings.setChatMode(m) },
                label = { Text(m.label, style = MaterialTheme.typography.labelLarge) },
                colors = androidx.compose.material3.FilterChipDefaults.filterChipColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.7f),
                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                    selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ),
            )
        }
    }
    if (mode != com.myassistant.app.agent.ChatMode.AUTO) {
        Text(
            mode.hint,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
        )
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
    vm: AppViewModel,
) {
    var plus by remember { mutableStateOf(false) }
    val mode by vm.settings.chatMode.collectAsState()
    Box(
        Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .shadow(6.dp, RoundedCornerShape(32.dp))
                .clip(RoundedCornerShape(32.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerLowest)
                .padding(horizontal = 6.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box {
                IconButton(onClick = { plus = true }) { Icon(Icons.Filled.Add, "الوضع", tint = MaterialTheme.colorScheme.onSurface) }
                DropdownMenu(expanded = plus, onDismissRequest = { plus = false }) {
                    com.myassistant.app.agent.ChatMode.entries.forEach { m ->
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text(m.label, style = MaterialTheme.typography.bodyLarge)
                                    Text(m.hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            },
                            trailingIcon = { if (m == mode) Icon(Icons.Filled.Check, null, tint = MaterialTheme.colorScheme.primary) },
                            onClick = { vm.settings.setChatMode(m); plus = false },
                        )
                    }
                }
            }
            TextField(
                value = value,
                onValueChange = onValueChange,
                placeholder = { Text("اسأل Nova", color = MaterialTheme.colorScheme.onSurfaceVariant) },
                modifier = Modifier.weight(1f).semantics { contentDescription = "اكتب رسالة" },
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
            if (!busy && value.isBlank()) {
                IconButton(onClick = onMic) {
                    Icon(if (listening || speaking) Icons.Filled.Close else Icons.Filled.Mic, "اتكلم مع Nova",
                        tint = MaterialTheme.colorScheme.onSurface)
                }
            }
            Spacer(Modifier.size(4.dp))
            androidx.compose.material3.FilledTonalIconButton(
                onClick = { when { busy -> onStop(); value.isNotBlank() -> onSend(); else -> onMic() } },
                modifier = Modifier.size(48.dp),
            ) {
                when {
                    busy -> Icon(Icons.Filled.Close, "إيقاف")
                    value.isNotBlank() -> Icon(Icons.AutoMirrored.Filled.Send, "إرسال")
                    else -> Icon(Icons.Filled.GraphicEq, "محادثة صوتية")
                }
            }
        }
    }
}

@Composable
private fun RoundButton(onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        Modifier
            .size(54.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primary)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { content() }
}

/**
 * Nova's logo: a real full moon (NASA LRO photo map, public domain) with a warm glow that breathes,
 * and grows with the voice. Only the glow moves, in the draw/layer phase, so it stays smooth.
 */
@Composable
fun NovaRing(size: androidx.compose.ui.unit.Dp, active: Boolean, level: Float = 0f, modifier: Modifier = Modifier) {
    val t = rememberInfiniteTransition(label = "moon")
    val breathe = t.animateFloat(
        initialValue = 0.0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(3600, easing = androidx.compose.animation.core.FastOutSlowInEasing), RepeatMode.Reverse),
        label = "breathe",
    )
    val voice = androidx.compose.animation.core.animateFloatAsState(level.coerceIn(0f, 1f), label = "voice")
    val on = androidx.compose.animation.core.animateFloatAsState(if (active) 1f else 0f, label = "active")
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        // Warm moonlight around it.
        Box(
            Modifier.fillMaxSize()
                .graphicsLayer {
                    val g = 0.55f + breathe.value * 0.25f + on.value * 0.15f + voice.value * 0.35f
                    alpha = g.coerceAtMost(1f)
                    val s = 1f + breathe.value * 0.02f + voice.value * 0.12f
                    scaleX = s; scaleY = s
                }
                .drawWithCache {
                    // Light that comes out of the moon's own edge (the moon covers the inner part) and fades out.
                    val glow = Brush.radialGradient(
                        0.00f to Color(0xFFFFE8C2).copy(alpha = 0.70f),
                        0.66f to Color(0xFFFFE8C2).copy(alpha = 0.70f),
                        0.72f to Color(0xFFFFDDB0).copy(alpha = 0.42f),
                        0.82f to Color(0xFFFFD39C).copy(alpha = 0.14f),
                        1.00f to Color.Transparent,
                    )
                    onDrawBehind { drawCircle(glow) }
                },
        )
        androidx.compose.foundation.Image(
            painter = androidx.compose.ui.res.painterResource(com.myassistant.app.R.drawable.nova_moon),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(0.68f).graphicsLayer {
                val s = 1f + voice.value * 0.04f
                scaleX = s; scaleY = s
            },
        )
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
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                if (listening) "بسمعك…" else "بيتكلم…" + if (voiceMode) " (وبعدها هسمعك تاني)" else "",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
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
        androidx.compose.foundation.Image(
            painter = androidx.compose.ui.res.painterResource(com.myassistant.app.R.drawable.ic_launcher_fg),
            contentDescription = null,
            modifier = Modifier.size(96.dp),
        )
        Text(
            "نبدأ منين؟",
            style = MaterialTheme.typography.headlineLarge,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        if (needsKey) {
            Spacer(Modifier.height(20.dp))
            androidx.compose.material3.TextButton(onClick = openSettings) { Text("حط مفتاح API الأول", color = MaterialTheme.colorScheme.primary) }
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
            Row(Modifier.fillMaxWidth().padding(bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                androidx.compose.material3.FilterChip(
                    selected = !asDefault, onClick = { asDefault = false },
                    label = { Text("للمحادثة دي بس") },
                )
                androidx.compose.material3.FilterChip(
                    selected = asDefault, onClick = { asDefault = true },
                    label = { Text("الافتراضي دايمًا") },
                )
            }
            Text(
                "أو دوس دوسة طويلة على أي موديل يبقى الافتراضي.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ModelSection("يرد عليا", providers, sChat, dChat, onDefault = { s.setChatBrain(it); s.sessionChat.value = "" }) { pick(true, it) }
            ModelSection("ينفّذ الأوامر على الموبايل", providers, sAction, dAction,
                onDefault = { s.setActionBrain(it); s.sessionAction.value = "" }) { pick(false, it) }
            OpenCodeSection(vm, asDefault)
            if (providers.isEmpty()) Text("مفيش مزوّدين جاهزين. قول لـ Nova: عايز أضيف مزوّد.", color = MaterialTheme.colorScheme.error)
        }
    }
}

/** OpenCode's models (the coding agent in Termux): pick one for this chat or as the default. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
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
            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary,
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
            Modifier.padding(vertical = 5.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp))
                .background(if (on) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer)
                .border(1.dp, if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(14.dp))
                .combinedClickable(onClick = { pick(id) }, onLongClick = { s.setOpencodeModel(id); s.sessionOpencode.value = "" })
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

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun ModelSection(
    title: String,
    providers: List<com.myassistant.app.ai.ProviderConfig>,
    selected: String,
    default: String,
    onDefault: (String) -> Unit,
    onPick: (String) -> Unit,
) {
    Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(top = 18.dp, bottom = 6.dp))
    providers.forEach { c ->
        val on = (selected.ifEmpty { default }) == c.id
        Row(
            Modifier.padding(vertical = 5.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp))
                .background(if (on) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer)
                .border(1.dp, if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(14.dp))
                .combinedClickable(onClick = { onPick(c.id) }, onLongClick = { onDefault(c.id) })
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
