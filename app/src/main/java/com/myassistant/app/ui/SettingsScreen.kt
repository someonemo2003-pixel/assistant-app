@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.myassistant.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.myassistant.app.ai.ProviderConfig
import com.myassistant.app.ai.ProviderType
import com.myassistant.app.core.newId
import com.myassistant.app.semantic.MeaningModel
import com.myassistant.app.semantic.ModelState
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: AppViewModel) {
    val configs by vm.providerStore.configs.collectAsState()
    val keysVersion by vm.providerStore.keysVersion.collectAsState()
    val learn by vm.settings.learnFromAi.collectAsState()
    val autoMemory by vm.settings.autoMemory.collectAsState()
    val meaning by vm.meaningState.collectAsState()
    val speakReplies by vm.settings.speakReplies.collectAsState()
    val handsFree by vm.settings.handsFree.collectAsState()
    val chatBrain by vm.settings.chatBrain.collectAsState()
    val actionBrain by vm.settings.actionBrain.collectAsState()
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var editing by remember { mutableStateOf<ProviderConfig?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("الإعدادات", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = { vm.screen = Screen.Chat }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "رجوع")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Button(onClick = { vm.screen = Screen.Permissions }, modifier = Modifier.fillMaxWidth()) {
                    Text("الصلاحيات (التحكم في الموبايل)")
                }
            }
            item { MailCard(vm) }
            item { ReportCard(vm) }
            item {
                Text("الصوت", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                        ToggleRow("يرد عليا بصوت", "لما تكلمه بصوتك، يقرالك الرد", speakReplies) { vm.settings.setSpeakReplies(it) }
                        ToggleRow(
                            "محادثة متواصلة",
                            "بعد ما يرد، يسمعك تاني على طول من غير ما تدوس. قول \"خلاص\" أو \"شكراً\" عشان يقف.",
                            handsFree,
                        ) { vm.settings.setHandsFree(it) }
                        Row {
                            TextButton(onClick = {
                                runCatching {
                                    ctx.startActivity(android.content.Intent("com.android.settings.TTS_SETTINGS").addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                                }
                            }) { Text("صوت القراءة") }
                            TextButton(onClick = {
                                runCatching {
                                    ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_VOICE_INPUT_SETTINGS).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                                }.onFailure {
                                    runCatching { ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
                                }
                            }) { Text("المساعد الافتراضي") }
                        }
                    }
                }
            }
            item {
                Text("العقل السريع والتعلّم", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                        ToggleRow(
                            "يتعلم من العقل الكبير",
                            "الأوامر اللي العقل الكبير ينفّذها صح تتحفظ، والمرة الجاية تتنفّذ فوراً",
                            learn,
                        ) { vm.settings.setLearnFromAi(it) }
                        ToggleRow(
                            "يملأ الذاكرة لوحده",
                            "لما تبدأ محادثة جديدة، يحفظ المعلومات المهمة من اللي فاتت (طلب واحد للعقل الكبير)",
                            autoMemory,
                        ) { vm.settings.setAutoMemory(it) }
                        TextButton(onClick = { vm.screen = Screen.Learned }) { Text("شوف اللي اتعلمه ") }
                    }
                }
            }
            item { MeaningModelCard(meaning, onDownload = vm::downloadMeaningModel, onDelete = vm::deleteMeaningModel) }
            item {
                Text("مين يعمل إيه", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                        BrainPicker("الدردشة والأسئلة", "محتاج عقل فاهم عربي كويس (Gemini مثلاً)", chatBrain, configs) {
                            vm.settings.setChatBrain(it)
                        }
                        BrainPicker("تنفيذ الأوامر على الموبايل", "محتاج عقل سريع في الخطوات", actionBrain, configs) {
                            vm.settings.setActionBrain(it)
                        }
                    }
                }
            }
            item {
                Text("المزوّدين (العقل الكبير)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
            itemsIndexed(configs, key = { _, c -> c.id }) { index, c ->
                val hasKey = remember(c.id, keysVersion) { vm.providerStore.hasKey(c.id) }
                ProviderCard(
                    config = c,
                    position = index + 1,
                    hasKey = hasKey,
                    canUp = index > 0,
                    canDown = index < configs.lastIndex,
                    onToggle = { vm.toggleProvider(c, it) },
                    onUp = { vm.moveProvider(c.id, -1) },
                    onDown = { vm.moveProvider(c.id, 1) },
                    onEdit = { editing = c },
                )
            }
            item { FocusCard() }
            item { GuardCard() }
            item { UpdateCard() }
            item { BackupCard(vm) }
            item {
                OutlinedButton(
                    onClick = {
                        editing = ProviderConfig(
                            id = "custom-" + newId(), type = ProviderType.OPENAI_COMPATIBLE,
                            name = "", baseUrl = "https://", model = "",
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Filled.Add, null)
                    Spacer(Modifier.size(8.dp))
                    Text("إضافة مزوّد تاني")
                }
            }
        }
    }

    editing?.let { cfg ->
        val isNew = configs.none { it.id == cfg.id }
        ProviderDialog(
            initial = cfg,
            isNew = isNew,
            hasKey = vm.providerStore.hasKey(cfg.id),
            onDismiss = { editing = null },
            onSave = { c, key -> vm.saveProvider(c, key); editing = null },
            onDelete = if (!cfg.builtIn && !isNew) ({ vm.deleteProvider(cfg.id); editing = null }) else null,
            fetchModels = { c, key -> vm.fetchModels(c, key) },
        )
    }
}

@Composable
private fun MeaningModelCard(state: ModelState, onDownload: () -> Unit, onDelete: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("موديل فهم المعنى", style = MaterialTheme.typography.titleSmall)
            when (state) {
                is ModelState.NotDownloaded -> {
                    Button(onClick = onDownload, modifier = Modifier.fillMaxWidth()) {
                        Text("تحميل (حوالي ${MeaningModel.APPROX_MB} ميجا)")
                    }
                }
                is ModelState.Downloading -> {
                    val frac = if (state.total > 0) state.downloaded.toFloat() / state.total else 0f
                    if (state.total > 0) {
                        LinearProgressIndicator(progress = { frac }, modifier = Modifier.fillMaxWidth())
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                    Text(
                        "${state.downloaded / 1_000_000} / ${state.total / 1_000_000} ميجا",
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                is ModelState.Loading -> {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text("بيجهّز الموديل…", style = MaterialTheme.typography.labelSmall)
                }
                is ModelState.Ready -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.CheckCircle, null, tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.size(6.dp))
                        Text("شغال ", color = MaterialTheme.colorScheme.tertiary, style = MaterialTheme.typography.labelLarge)
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = onDelete) { Text("مسح الموديل", color = MaterialTheme.colorScheme.error) }
                    }
                }
                is ModelState.Failed -> {
                    Text(state.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    Button(onClick = onDownload) { Text("حاول تاني") }
                }
            }
        }
    }
}

@Composable
private fun MailCard(vm: AppViewModel) {
    val savedEmail by vm.mailEmail.collectAsState()
    var email by remember(savedEmail) { mutableStateOf(savedEmail) }
    var pass by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val uri = LocalUriHandler.current
    val ctx = androidx.compose.ui.platform.LocalContext.current

    Text("Gmail", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    Spacer(Modifier.size(8.dp))
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                email, { email = it }, label = { Text("إيميل Gmail") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email), modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                pass, { pass = it }, label = { Text("App Password (16 حرف)") }, singleLine = true,
                placeholder = { Text(if (vm.mailHasPassword()) "محفوظ — اكتب جديد لتغييره" else "xxxx xxxx xxxx xxxx") },
                visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "إنشاء App Password",
                color = MaterialTheme.colorScheme.primary,
                textDecoration = TextDecoration.Underline,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.clickable { runCatching { uri.openUri("https://myaccount.google.com/apppasswords") } },
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(enabled = !busy && email.contains('@'), onClick = {
                    busy = true
                    status = "بيجرّب الاتصال…"
                    scope.launch {
                        status = vm.saveAndTestMail(email, pass.takeIf { it.isNotBlank() })
                        pass = ""
                        busy = false
                    }
                }) { Text("حفظ واختبار") }
                if (savedEmail.isNotBlank()) {
                    TextButton(onClick = { vm.disconnectMail(); status = "اتشال الربط" }) {
                        Text("فصل", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
            status?.let { Text(it, style = MaterialTheme.typography.bodySmall) }

        }
    }
}

/** WhatsApp (and twin) accounts the assistant has seen, with names the user picks. */
@Composable
private fun AccountsSection(vm: AppViewModel) {
    androidx.compose.runtime.LaunchedEffect(Unit) { vm.loadAccounts() }
    if (vm.chatAccounts.isEmpty()) return
    var editing by remember { mutableStateOf<AppViewModel.ChatAccount?>(null) }
    HorizontalDivider()
    Text("حسابات الشات", style = MaterialTheme.typography.titleSmall)
    vm.chatAccounts.forEach { a ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(a.label, style = MaterialTheme.typography.bodyMedium)
            }
            IconButton(onClick = { editing = a }) { Icon(Icons.Filled.Edit, "تسمية") }
        }
    }
    editing?.let { a ->
        var name by remember(a) { mutableStateOf(a.custom.orEmpty()) }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text("اسم الحساب") },
            text = { OutlinedTextField(name, { name = it }, placeholder = { Text("مثلاً: شغل") }, singleLine = true) },
            confirmButton = { TextButton(onClick = { vm.renameAccount(a, name); editing = null }) { Text("حفظ") } },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("إلغاء") } },
        )
    }
}

@Composable
private fun BrainPicker(title: String, hint: String, selected: String, configs: List<ProviderConfig>, onSelect: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val current = configs.firstOrNull { it.id == selected }?.let { "${it.name} · ${it.model}" } ?: "بالترتيب العادي"
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
        }
        Box {
            TextButton(onClick = { open = true }) { Text(current, maxLines = 1) }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                DropdownMenuItem(text = { Text("بالترتيب العادي") }, onClick = { onSelect(""); open = false })
                configs.filter { it.enabled }.forEach { c ->
                    DropdownMenuItem(text = { Text("${c.name} · ${c.model}") }, onClick = { onSelect(c.id); open = false })
                }
            }
        }
    }
}

@Composable
private fun ToggleRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
        }
        Spacer(Modifier.size(8.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun ProviderCard(
    config: ProviderConfig,
    position: Int,
    hasKey: Boolean,
    canUp: Boolean,
    canDown: Boolean,
    onToggle: (Boolean) -> Unit,
    onUp: () -> Unit,
    onDown: () -> Unit,
    onEdit: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onEdit),
    ) {
        Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "$position",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.size(14.dp))
            Column(Modifier.weight(1f)) {
                Text(config.name.ifBlank { "بدون اسم" }, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    config.model.ifBlank { "—" },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
                Spacer(Modifier.size(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (hasKey) Icons.Filled.CheckCircle else Icons.Filled.Warning, null,
                        tint = if (hasKey) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(14.dp),
                    )
                    Spacer(Modifier.size(4.dp))
                    Text(
                        if (hasKey) "المفتاح محفوظ" else "محتاج مفتاح",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (hasKey) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error,
                    )
                }
            }
            Column {
                IconButton(onClick = onUp, enabled = canUp, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Filled.KeyboardArrowUp, "لفوق")
                }
                IconButton(onClick = onDown, enabled = canDown, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Filled.KeyboardArrowDown, "لتحت")
                }
            }
            IconButton(onClick = onEdit) { Icon(Icons.Filled.Edit, "تعديل") }
            Switch(checked = config.enabled, onCheckedChange = onToggle)
        }
    }
}

@Composable
private fun ProviderDialog(
    initial: ProviderConfig,
    isNew: Boolean,
    hasKey: Boolean,
    onDismiss: () -> Unit,
    onSave: (ProviderConfig, String?) -> Unit,
    onDelete: (() -> Unit)?,
    fetchModels: suspend (ProviderConfig, String?) -> List<String>,
) {
    var name by remember { mutableStateOf(initial.name) }
    var type by remember { mutableStateOf(initial.type) }
    var baseUrl by remember { mutableStateOf(initial.baseUrl) }
    var model by remember { mutableStateOf(initial.model) }
    var key by remember { mutableStateOf("") }
    var models by remember { mutableStateOf<List<String>>(emptyList()) }
    var menuOpen by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var fetchError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val uri = LocalUriHandler.current

    fun current() = initial.copy(name = name.trim(), type = type, baseUrl = baseUrl.trim(), model = model.trim())

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isNew) "مزوّد جديد" else initial.name) },
        text = {
            Column(
                Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (!initial.builtIn) {
                    OutlinedTextField(name, { name = it }, label = { Text("الاسم") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = type == ProviderType.OPENAI_COMPATIBLE,
                            onClick = { type = ProviderType.OPENAI_COMPATIBLE },
                            label = { Text("صيغة OpenAI") },
                        )
                        FilterChip(
                            selected = type == ProviderType.GEMINI,
                            onClick = { type = ProviderType.GEMINI },
                            label = { Text("Gemini") },
                        )
                    }
                    OutlinedTextField(
                        baseUrl, { baseUrl = it }, label = { Text("الرابط (Base URL)") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                OutlinedTextField(
                    value = key,
                    onValueChange = { key = it },
                    label = { Text("مفتاح الـ API") },
                    placeholder = { Text(if (hasKey) "محفوظ — اكتب جديد لتغييره" else "الصق المفتاح هنا") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
                initial.keyUrl?.let { url ->
                    Text(
                        "هات مفتاح مجاني من هنا ",
                        color = MaterialTheme.colorScheme.primary,
                        textDecoration = TextDecoration.Underline,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.clickable { runCatching { uri.openUri(url) } },
                    )
                }

                Box {
                    OutlinedTextField(
                        value = model,
                        onValueChange = { model = it },
                        label = { Text("الموديل") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        trailingIcon = {
                            if (loading) {
                                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            } else {
                                IconButton(onClick = {
                                    loading = true
                                    fetchError = null
                                    scope.launch {
                                        try {
                                            models = fetchModels(current(), key)
                                            menuOpen = models.isNotEmpty()
                                            if (models.isEmpty()) fetchError = "مفيش موديلات راجعة"
                                        } catch (e: Exception) {
                                            fetchError = e.message
                                        } finally {
                                            loading = false
                                        }
                                    }
                                }) { Icon(Icons.Filled.Refresh, "جلب الموديلات") }
                            }
                        },
                    )
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        models.forEach { m ->
                            DropdownMenuItem(text = { Text(m) }, onClick = { model = m; menuOpen = false })
                        }
                    }
                }
                Text(
                    "دوس عشان تختار من الموديلات المتاحة لمفتاحك.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                fetchError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }

                if (onDelete != null) {
                    TextButton(onClick = onDelete) { Text("حذف المزوّد", color = MaterialTheme.colorScheme.error) }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(current(), key.takeIf { it.isNotBlank() }) },
                enabled = name.isNotBlank() && model.isNotBlank() && baseUrl.startsWith("http"),
            ) { Text("حفظ") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("إلغاء") } },
    )
}


@Composable
private fun BackupCard(vm: AppViewModel) {
    val scope = rememberCoroutineScope()
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var status by remember { mutableStateOf<String?>(null) }
    var pending by remember { mutableStateOf<android.net.Uri?>(null) }
    val exporter = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri -> if (uri != null) { vm.exportBackup(uri); status = "اتحفظت النسخة" } }
    val importer = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
    ) { uri -> pending = uri }

    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("نسخة احتياطية", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    val d = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
                    exporter.launch("assistant-backup-$d.zip")
                }) { Text("احفظ نسخة") }
                OutlinedButton(onClick = { importer.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) }) { Text("استرجع") }
            }
            status?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }

    pending?.let { uri ->
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text("استرجاع النسخة؟") },
            text = { Text("هيتم استبدال بياناتك الحالية باللي في الملف، والتطبيق هيقفل ويفتح تاني.") },
            confirmButton = {
                TextButton(onClick = {
                    pending = null
                    scope.launch {
                        val r = vm.importBackup(uri)
                        if (r.startsWith("OK")) {
                            val intent = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)
                            val restart = android.content.Intent.makeRestartActivityTask(intent!!.component)
                            ctx.startActivity(restart)
                            Runtime.getRuntime().exit(0)
                        } else {
                            status = "" + r.removePrefix("ERR:")
                        }
                    }
                }) { Text("استرجع") }
            },
            dismissButton = { TextButton(onClick = { pending = null }) { Text("إلغاء") } },
        )
    }
}


/** Full control: add apps to block (permanent — no list, no undo). */
@Composable
private fun GuardCard() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val clip = androidx.compose.ui.platform.LocalClipboardManager.current
    val D = com.myassistant.app.admin.DeviceOwner
    val owner = remember { D.isOwner(ctx) }
    var newApp by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf<String?>(null) }

    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("التحكم الكامل", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(newApp, { newApp = it; msg = null }, placeholder = { Text("اسم برنامج") }, singleLine = true, modifier = Modifier.weight(1f))
                TextButton(onClick = { D.block(ctx, newApp); msg = "اتمنع"; newApp = "" }, enabled = newApp.isNotBlank()) { Text("امنع") }
            }
            msg?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            if (!owner) {
                Text("مالك الجهاز مش مفعّل", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                TextButton(onClick = { clip.setText(androidx.compose.ui.text.AnnotatedString(D.ADB_COMMAND)) }) { Text("انسخ أمر التفعيل") }
            }
        }
    }
}

/** Focus rules (added from chat), today's usage, delete with PIN. */
@Composable
private fun FocusCard() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val F = com.myassistant.app.focus.Focus
    var tick by remember { mutableStateOf(0) }
    val rules = remember(tick) { F.rules(ctx) }
    val usage = remember(tick) { F.usageToday(ctx, rules.flatMap { it.apps }) }
    val blocked = remember(tick) { F.blockedNow(ctx) }
    var deleting by remember { mutableStateOf<com.myassistant.app.focus.FocusRule?>(null) }
    var msg by remember { mutableStateOf<String?>(null) }

    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("وضع التركيز", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            if (rules.isEmpty()) Text("مفيش قواعد", color = MaterialTheme.colorScheme.onSurfaceVariant)
            rules.forEach { r ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        val on = r.apps.any { it in blocked }
                        Text(r.name + ": " + r.labels.joinToString("، "), style = MaterialTheme.typography.bodyMedium,
                            color = if (on) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                        Text(
                            listOfNotNull(
                                r.from?.let { "من $it لحد ${r.to}" },
                                if (r.limitMin > 0) "${r.apps.sumOf { usage[it] ?: 0 }} من ${r.limitMin} دقيقة النهارده" else null,
                            ).joinToString(" · "),
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = { deleting = r }) { Text("شيل") }
                }
            }
            msg?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
    deleting?.let { r ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("شيل \"${r.name}\"؟") },
            text = { Text("التطبيقات هترجع تفتح عادي.") },
            confirmButton = { TextButton(onClick = { msg = F.remove(ctx, r.id.toString()); deleting = null; tick++ }) { Text("شيل") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("إلغاء") } },
        )
    }
}

/** Version + auto update from GitHub. */
@Composable
private fun UpdateCard() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val U = com.myassistant.app.update.Updater
    var auto by remember { mutableStateOf(U.autoOn(ctx)) }
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("تحديث تلقائي", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text("النسخة ${U.current(ctx)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = auto, onCheckedChange = { auto = it; U.setAuto(ctx, it) })
            }
            OutlinedButton(onClick = {
                busy = true; status = "بيدوّر…"
                scope.launch { status = U.checkAndInstall(ctx.applicationContext); busy = false }
            }, enabled = !busy) { Text("دوّر على تحديث دلوقتي") }
            status?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
}


/** Daily Gmail report: on/off, time, try now. */
@Composable
private fun ReportCard(vm: AppViewModel) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("تقرير Gmail اليومي", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Switch(checked = vm.reportOn, onCheckedChange = { vm.setReport(it) })
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = {
                    android.app.TimePickerDialog(ctx, { _, h, m -> vm.setReport(true, h, m) }, vm.reportHour, vm.reportMinute, false).show()
                }) { Text("الميعاد: " + String.format(java.util.Locale.US, "%02d:%02d", vm.reportHour, vm.reportMinute)) }
                TextButton(onClick = { vm.reportNow() }) { Text("جرّب دلوقتي") }
            }
        }
    }
}
