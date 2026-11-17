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
                Text("⚡ العقل السريع والتعلّم", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
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
                        TextButton(onClick = { vm.screen = Screen.Learned }) { Text("شوف اللي اتعلمه ←") }
                    }
                }
            }
            item { MeaningModelCard(meaning, onDownload = vm::downloadMeaningModel, onDelete = vm::deleteMeaningModel) }
            item {
                Text("🧠 المزوّدين (العقل الكبير)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
            item {
                Text(
                    "المساعد بيجرّب المزوّدين بالترتيب ده. لو واحد خلص الحد المجاني أو وقع، ينقل للي بعده لوحده.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
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
            Text("🧩 موديل فهم المعنى", style = MaterialTheme.typography.titleSmall)
            Text(
                "موديل صغير من Google (EmbeddingGemma) بيشتغل على موبايلك من غير نت. بيخلّي العقل السريع يفهم " +
                    "نفس الطلب لو اتقال بكلام مختلف، ويدوّر في الذاكرة والشاتات بالمعنى.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            when (state) {
                is ModelState.NotDownloaded -> {
                    Button(onClick = onDownload, modifier = Modifier.fillMaxWidth()) {
                        Text("تحميل (حوالي ${MeaningModel.APPROX_MB} ميجا)")
                    }
                    Text("يفضّل على Wi-Fi. التحميل بيكمّل من مكان ما وقف لو النت قطع.", style = MaterialTheme.typography.labelSmall)
                }
                is ModelState.Downloading -> {
                    val frac = if (state.total > 0) state.downloaded.toFloat() / state.total else 0f
                    if (state.total > 0) {
                        LinearProgressIndicator(progress = { frac }, modifier = Modifier.fillMaxWidth())
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                    Text(
                        "بيتحمّل… ${state.downloaded / 1_000_000} / ${state.total / 1_000_000} ميجا — سيب التطبيق مفتوح",
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
                        Text("شغال ✓", color = MaterialTheme.colorScheme.tertiary, style = MaterialTheme.typography.labelLarge)
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
private fun ToggleRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                    placeholder = { Text(if (hasKey) "محفوظ ✓ — اكتب جديد لتغييره" else "الصق المفتاح هنا") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
                initial.keyUrl?.let { url ->
                    Text(
                        "هات مفتاح مجاني من هنا ↗",
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
                    "دوس ↻ عشان تختار من الموديلات المتاحة لمفتاحك.",
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
