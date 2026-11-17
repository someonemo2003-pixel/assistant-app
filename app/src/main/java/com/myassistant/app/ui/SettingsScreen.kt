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
                    Text("🔐 الصلاحيات (التحكم في الموبايل)")
                }
            }
            item { MailCard(vm) }
            item {
                val walking by vm.walking.collectAsState()
                Text("🚶 الصحة والأماكن", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Spacer(Modifier.size(8.dp))
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                        ToggleRow(
                            "تسجيل الأماكن طول اليوم",
                            "نقطة كل حوالي ربع ساعة (خفيف على البطارية) — محفوظ على موبايلك بس",
                            vm.lightTracking,
                        ) { vm.changeLightTracking(it) }
                        Row {
                            TextButton(onClick = { vm.openMap() }) { Text("🗺️ خريطتي") }
                            TextButton(onClick = { vm.toggleWalk() }) { Text(if (walking) "⏹ وقّف المشي" else "▶️ ابدأ تتبّع المشي") }
                            TextButton(onClick = { vm.clearPlaces() }) { Text("مسح السجل", color = MaterialTheme.colorScheme.error) }
                        }
                    }
                }
            }
            item {
                Text("🎙️ الصوت", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
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
                        Text(
                            "طرق سريعة تكلمه بيها: دوس مطوّل على أيقونة التطبيق ← \"اتكلم\"، أو ضيف زرار \"كلّم مساعدي\" " +
                                "في شريط الإعدادات السريعة (اسحب لتحت ← ✏️ تعديل)، أو اختاره مساعد افتراضي من " +
                                "الإعدادات ← التطبيقات ← التطبيقات الافتراضية ← المساعد.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
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
                Text("🧭 مين يعمل إيه", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                        BrainPicker("💬 الدردشة والأسئلة", "محتاج عقل فاهم عربي كويس (Gemini مثلاً)", chatBrain, configs) {
                            vm.settings.setChatBrain(it)
                        }
                        BrainPicker("📱 تنفيذ الأوامر على الموبايل", "محتاج عقل سريع في الخطوات", actionBrain, configs) {
                            vm.settings.setActionBrain(it)
                        }
                        Text(
                            "لو اللي اخترته وقع أو خلص الحد المجاني، بينقل للباقيين بالترتيب اللي تحت.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
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
            item { GuardCard() }
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
private fun MailCard(vm: AppViewModel) {
    val savedEmail by vm.mailEmail.collectAsState()
    var email by remember(savedEmail) { mutableStateOf(savedEmail) }
    var pass by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val uri = LocalUriHandler.current
    val ctx = androidx.compose.ui.platform.LocalContext.current

    Text("📧 Gmail والتقرير اليومي", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    Spacer(Modifier.size(8.dp))
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "الإيميل اللي كل حساباتك بتعمل له Forward. المساعد بيقراه في الخلفية من غير ما يفتح أي تطبيق، " +
                    "وبيعرف كل رسالة جت لأنهي حساب.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                email, { email = it }, label = { Text("إيميل Gmail") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email), modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                pass, { pass = it }, label = { Text("App Password (16 حرف)") }, singleLine = true,
                placeholder = { Text(if (vm.mailHasPassword()) "محفوظ ✓ — اكتب جديد لتغييره" else "xxxx xxxx xxxx xxxx") },
                visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "اعمل App Password من هنا ↗ (لازم التحقق بخطوتين يكون مفعّل)",
                color = MaterialTheme.colorScheme.primary,
                textDecoration = TextDecoration.Underline,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.clickable { runCatching { uri.openUri("https://myaccount.google.com/apppasswords") } },
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(enabled = !busy && email.contains('@'), onClick = {
                    busy = true
                    status = "⏳ بيجرّب الاتصال…"
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

            HorizontalDivider()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("التقرير اليومي", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "اختار هيبقى فيه إيه. وتقدر تطلب أي تقرير من الشات: \"تقرير الجيميل بس\" أو \"تقرير واتس التوأم\".",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = vm.reportOn, onCheckedChange = { vm.setReport(it) })
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = {
                    android.app.TimePickerDialog(ctx, { _, h, m -> vm.setReport(true, h, m) }, vm.reportHour, vm.reportMinute, false).show()
                }) { Text("الميعاد: " + String.format(java.util.Locale.US, "%02d:%02d", vm.reportHour, vm.reportMinute)) }
                TextButton(onClick = { vm.reportNow() }) { Text("جرّب التقرير دلوقتي") }
            }
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                com.myassistant.app.mail.DailyReport.SOURCES.forEach { (k, label) ->
                    FilterChip(selected = k in vm.reportSources, onClick = { vm.toggleReportSource(k) }, label = { Text(label) })
                }
            }
            AccountsSection(vm)
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
    Text(
        "كل نسخة/حساب ظهر في الإشعارات. سمّي كل واحد (مثلاً شخصي، شغل) عشان تطلب تقريره لوحده.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    vm.chatAccounts.forEach { a ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(a.label, style = MaterialTheme.typography.bodyMedium)
                Text("${a.count} رسالة · ${a.raw}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
            Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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


@Composable
private fun BackupCard(vm: AppViewModel) {
    val scope = rememberCoroutineScope()
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var status by remember { mutableStateOf<String?>(null) }
    var pending by remember { mutableStateOf<android.net.Uri?>(null) }
    val exporter = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri -> if (uri != null) { vm.exportBackup(uri); status = "✅ اتحفظت النسخة" } }
    val importer = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
    ) { uri -> pending = uri }

    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("💾 نسخة احتياطية", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                "الذاكرة والمحادثات والمصاريف والتذكيرات والإعدادات في ملف واحد. المفاتيح وباسورد الإيميل مش بتتحفظ فيها (للأمان).",
                style = MaterialTheme.typography.bodySmall,
            )
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
                            status = "❌ " + r.removePrefix("ERR:")
                        }
                    }
                }) { Text("استرجع") }
            },
            dismissButton = { TextButton(onClick = { pending = null }) { Text("إلغاء") } },
        )
    }
}


/** 🛡️ Full control: block apps, PIN, device owner status and how to turn it on. */
@Composable
private fun GuardCard() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val clip = androidx.compose.ui.platform.LocalClipboardManager.current
    val D = com.myassistant.app.admin.DeviceOwner
    var tick by remember { mutableStateOf(0) }
    val owner = remember(tick) { D.isOwner(ctx) }
    val blocked = remember(tick) { D.blocked(ctx) }
    var newApp by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf<String?>(null) }
    var pinFor by remember { mutableStateOf<String?>(null) } // "unblock:<name>" | "setpin" | "release"
    var showSteps by remember { mutableStateOf(false) }

    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("🛡️ التحكم الكامل", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                if (owner) "✅ مالك الجهاز شغال: المنع من النظام نفسه، ومساعدي مايتمسحش." else "⚪ مالك الجهاز مش مفعّل — المنع شغال بالتحكم في الشاشة بس.",
                style = MaterialTheme.typography.bodySmall,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(newApp, { newApp = it }, placeholder = { Text("اسم برنامج تمنعه…") }, singleLine = true, modifier = Modifier.weight(1f))
                TextButton(onClick = { msg = D.block(ctx, newApp); newApp = ""; tick++ }, enabled = newApp.isNotBlank()) { Text("امنع") }
            }
            blocked.forEach { b ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("🚫 ${b.name}", modifier = Modifier.weight(1f))
                    TextButton(onClick = { pinFor = "unblock:" + b.name }) { Text("شيل") }
                }
            }
            msg?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            Row {
                TextButton(onClick = { pinFor = "setpin" }) { Text(if (D.hasPin(ctx)) "غيّر الرقم السري" else "حط رقم سري") }
                if (owner) TextButton(onClick = { pinFor = "release" }) { Text("إلغاء مالك الجهاز", color = MaterialTheme.colorScheme.error) }
                else TextButton(onClick = { showSteps = !showSteps }) { Text("إزاي أفعّل مالك الجهاز؟") }
            }
            if (showSteps && !owner) {
                Text(
                    "1) خد باك أب للواتس التوأم (إعدادات واتساب ← الدردشات ← نسخ احتياطي) وبعدين اقفل App Twin.\n" +
                        "2) الإعدادات ← المستخدمين والحسابات: شيل كل الحسابات (جوجل، Honor ID، وأي حساب تاني) مؤقتاً.\n" +
                        "3) فعّل خيارات المطوّر وتصحيح USB، ووصّل الموبايل باللابتوب.\n" +
                        "4) على اللابتوب نزّل Platform-Tools من جوجل، وافتح الترمينال في الفولدر واكتب الأمر ده:",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(D.ADB_COMMAND, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                TextButton(onClick = { clip.setText(androidx.compose.ui.text.AnnotatedString(D.ADB_COMMAND)) }) { Text("📋 انسخ الأمر") }
                Text("5) لما يقول Success رجّع حساباتك والـ Twin، وارجع هنا تلاقيها ✅.", style = MaterialTheme.typography.bodySmall)
            }
        }
    }

    pinFor?.let { what ->
        var old by remember(what) { mutableStateOf("") }
        var new by remember(what) { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { pinFor = null },
            title = { Text(when { what == "setpin" -> "الرقم السري"; what == "release" -> "إلغاء مالك الجهاز"; else -> "شيل المنع" }) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (D.hasPin(ctx)) OutlinedTextField(old, { old = it }, label = { Text("الرقم الحالي") }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword))
                    if (what == "setpin") OutlinedTextField(new, { new = it }, label = { Text("الرقم الجديد (فاضي = من غير رقم)") }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword))
                    if (what == "release") Text("هترجع للمنع بالتحكم في الشاشة بس، ومساعدي هيبقى ينفع يتمسح.")
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    msg = when {
                        what == "setpin" -> if (D.setPin(ctx, old, new)) "✅ اتحفظ" else "❌ الرقم الحالي غلط"
                        what == "release" -> if (D.release(ctx, old)) "تم إلغاء مالك الجهاز" else "❌ الرقم غلط"
                        else -> D.unblock(ctx, what.removePrefix("unblock:"), old)
                    }
                    pinFor = null; tick++
                }) { Text("تمام") }
            },
            dismissButton = { TextButton(onClick = { pinFor = null }) { Text("إلغاء") } },
        )
    }
}
