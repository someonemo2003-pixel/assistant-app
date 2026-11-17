package com.myassistant.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.myassistant.app.finance.ExpenseParser
import com.myassistant.app.memory.MemoryCategories
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** "بياناتي": everything the assistant knows and tracks, organised in tabs and filters. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemoryScreen(vm: AppViewModel) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val tabs = listOf("🧠 الذاكرة", "💰 المصاريف", "⏰ التذكيرات")

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("بياناتي", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = { vm.screen = Screen.Chat }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "رجوع") }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            TabRow(selectedTabIndex = tab) {
                tabs.forEachIndexed { i, t -> Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t) }) }
            }
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Lock, null, tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.size(6.dp))
                        Text(
                            "كل ده محفوظ على موبايلك بس.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                when (tab) {
                    0 -> memoryTab(vm)
                    1 -> expensesTab(vm)
                    else -> remindersTab(vm)
                }
            }
        }
    }
    LaunchedEffect(tab) {
        when (tab) {
            0 -> vm.loadMemories()
            1 -> vm.loadExpenses()
            else -> vm.loadReminders()
        }
    }
}

// ---------------- memory ----------------

private fun LazyListScope.memoryTab(vm: AppViewModel) {
    item { MemoryFilters(vm) }
}

@Composable
private fun MemoryFilters(vm: AppViewModel) {
    var filter by rememberSaveable { mutableStateOf<String?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    var newText by remember { mutableStateOf("") }
    var newCat by remember { mutableStateOf(MemoryCategories.GENERAL) }
    val fmt = remember { DateFormat.getDateInstance(DateFormat.MEDIUM) }
    val counts = vm.memories.groupingBy { it.category }.eachCount()

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(selected = filter == null, onClick = { filter = null }, label = { Text("الكل (${vm.memories.size})") })
            MemoryCategories.all.forEach { (k, label) ->
                val n = counts[k] ?: 0
                if (n > 0 || filter == k) FilterChip(selected = filter == k, onClick = { filter = k }, label = { Text("$label ($n)") })
            }
        }
        OutlinedTextField(query, { query = it }, placeholder = { Text("🔍 دوّر…") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(newText, { newText = it }, placeholder = { Text("ضيف معلومة…") }, modifier = Modifier.weight(1f), maxLines = 3)
            Spacer(Modifier.size(6.dp))
            CategoryPicker(newCat, MemoryCategories.all) { newCat = it }
            FilledIconButton(onClick = { vm.addMemory(newText, newCat); newText = "" }, enabled = newText.isNotBlank()) {
                Icon(Icons.Filled.Add, "إضافة")
            }
        }
        val shown = vm.memories.filter { (filter == null || it.category == filter) && (query.isBlank() || it.text.contains(query.trim(), true)) }
        if (shown.isEmpty()) Empty("مفيش حاجة هنا. قول للمساعد مثلاً: \"افتكر إن اسمي أحمد\"")
        shown.forEach { m ->
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
                Row(Modifier.padding(start = 14.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(m.text, style = MaterialTheme.typography.bodyLarge)
                        Text(fmt.format(Date(m.createdAt)), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    CategoryPicker(m.category, MemoryCategories.all) { vm.setMemoryCategory(m.id, it) }
                    IconButton(onClick = { vm.deleteMemory(m.id) }) { Icon(Icons.Filled.Delete, "حذف", tint = MaterialTheme.colorScheme.error) }
                }
            }
        }
    }
}

// ---------------- expenses ----------------

private fun LazyListScope.expensesTab(vm: AppViewModel) {
    item { ExpensesView(vm) }
}

@Composable
private fun ExpensesView(vm: AppViewModel) {
    var cat by rememberSaveable { mutableStateOf<String?>(null) }
    val fmt = remember { SimpleDateFormat("MM-dd HH:mm", Locale.US) }
    val out = vm.expenses.filter { it.debit }
    val inc = vm.expenses.filter { !it.debit }
    val byCat = out.groupBy { it.category }.mapValues { e -> e.value.sumOf { it.amount } }.entries.sortedByDescending { it.value }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(selected = vm.expenseMonthOffset == 0, onClick = { vm.loadExpenses(0) }, label = { Text("الشهر ده") })
            FilterChip(selected = vm.expenseMonthOffset == 1, onClick = { vm.loadExpenses(1) }, label = { Text("الشهر اللي فات") })
        }
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
            Column(Modifier.padding(14.dp)) {
                Text("صرفت: ${money(out.sumOf { it.amount })} جنيه", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text("دخلّك: ${money(inc.sumOf { it.amount })} جنيه · ${vm.expenses.size} عملية", style = MaterialTheme.typography.bodySmall)
            }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(selected = cat == null, onClick = { cat = null }, label = { Text("الكل") })
            byCat.forEach { (k, v) ->
                FilterChip(selected = cat == k, onClick = { cat = k }, label = { Text("${ExpenseParser.categories[k] ?: k} ${money(v)}") })
            }
            if (inc.isNotEmpty()) FilterChip(selected = cat == "income", onClick = { cat = "income" }, label = { Text(ExpenseParser.categories.getValue("income")) })
        }
        val shown = vm.expenses.filter { cat == null || it.category == cat }
        if (shown.isEmpty()) {
            Empty("مفيش عمليات. المساعد بيقرا رسايل البنك والمحفظة لوحده — محتاج صلاحية 💬 الرسايل SMS.")
        }
        shown.forEach { e ->
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
                Row(Modifier.padding(start = 14.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            (if (e.debit) "−" else "+") + money(e.amount) + " ${e.currency}  " + (e.merchant ?: ""),
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (e.debit) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.tertiary,
                        )
                        Text("${fmt.format(Date(e.time))} · ${e.account ?: e.source}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    CategoryPicker(e.category, ExpenseParser.categories) { vm.setExpenseCategory(e.id, it) }
                    IconButton(onClick = { vm.deleteExpense(e.id) }) { Icon(Icons.Filled.Delete, "حذف", tint = MaterialTheme.colorScheme.error) }
                }
            }
        }
    }
}

private fun money(v: Double) = String.format(Locale.US, "%,.0f", v)

// ---------------- reminders ----------------

private fun LazyListScope.remindersTab(vm: AppViewModel) {
    item { RemindersView(vm) }
}

@Composable
private fun RemindersView(vm: AppViewModel) {
    var showDone by rememberSaveable { mutableStateOf(false) }
    val fmt = remember { SimpleDateFormat("EEE d MMM HH:mm", Locale("ar", "EG")) }
    val repeatLabel = mapOf("daily" to "كل يوم", "weekly" to "كل أسبوع", "monthly" to "كل شهر")
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("قول للمساعد: \"فكّرني بكرة ٥ أكلم أحمد\" أو \"فكّرني أشتري عيش لما أوصل البيت\".", style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(selected = !showDone, onClick = { showDone = false }, label = { Text("الجاية") })
            FilterChip(selected = showDone, onClick = { showDone = true }, label = { Text("اللي خلصت") })
        }
        val shown = vm.reminders.filter { it.done == showDone }
        if (shown.isEmpty()) Empty(if (showDone) "مفيش" else "مفيش تذكيرات جاية")
        shown.forEach { r ->
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
                Row(Modifier.padding(start = 14.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(r.text, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            if (r.isPlace) "📍 " + (if (r.event == "leave") "لما تخرج من " else "لما توصل ") + r.place
                            else "🕐 " + fmt.format(Date(r.at)) + (repeatLabel[r.repeat]?.let { " · $it" } ?: ""),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = { vm.deleteReminder(r) }) { Icon(Icons.Filled.Delete, "حذف", tint = MaterialTheme.colorScheme.error) }
                }
            }
        }
        Text("📍 أماكنك المحفوظة", style = MaterialTheme.typography.titleSmall)
        if (vm.places.isEmpty()) Text("وإنت في البيت قول: \"احفظ مكاني ده باسم البيت\"", style = MaterialTheme.typography.bodySmall)
        vm.places.forEach { p ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("• ${p.name}", modifier = Modifier.weight(1f))
                IconButton(onClick = { vm.deletePlace(p.name) }) { Icon(Icons.Filled.Delete, "حذف", tint = MaterialTheme.colorScheme.error) }
            }
        }
    }
}

// ---------------- shared ----------------

@Composable
private fun CategoryPicker(selected: String, options: Map<String, String>, onSelect: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Text(
            (options[selected] ?: selected).substringBefore(' '),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.clickable { open = true }.padding(8.dp),
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { (k, label) -> DropdownMenuItem(text = { Text(label) }, onClick = { onSelect(k); open = false }) }
        }
    }
}

@Composable
private fun Empty(text: String) {
    Box(Modifier.fillMaxWidth().padding(vertical = 24.dp), contentAlignment = Alignment.Center) {
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
