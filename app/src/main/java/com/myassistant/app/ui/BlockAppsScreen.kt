package com.myassistant.app.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.myassistant.app.admin.AppCat
import com.myassistant.app.admin.DeviceOwner

/**
 * "/blockapps": blocked apps and the apps on the phone, by section. Blocking is for good:
 * there is no unblock anywhere, and every block asks for a confirmation first.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BlockAppsScreen(vm: AppViewModel) {
    LaunchedEffect(Unit) { if (vm.appRows.isEmpty()) vm.loadApps() }
    var section by rememberSaveable { mutableStateOf<String?>(null) } // null = all
    var query by rememberSaveable { mutableStateOf("") }
    var typed by rememberSaveable { mutableStateOf("") }
    var confirm by remember { mutableStateOf<Pair<String, String?>?>(null) }
    val owner = remember { DeviceOwner.isOwner(vm.getApplication()) }

    val rows = vm.appRows
    val inSection = rows.filter { section == null || it.cat.name == section }
        .filter { query.isBlank() || it.name.contains(query.trim(), true) }
    val blocked = inSection.filter { it.blocked }
    val free = inSection.filter { !it.blocked }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text("البرامج المحظورة") },
                navigationIcon = { IconButton(onClick = { vm.screen = Screen.Chat }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "رجوع") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp, end = 16.dp,
                top = padding.calculateTopPadding() + 4.dp, bottom = padding.calculateBottomPadding() + 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item(key = "summary") { Summary(rows.count { it.blocked }, owner) }

            item(key = "sections") {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(selected = section == null, onClick = { section = null }, label = { Text("الكل · ${rows.size}") })
                    AppCat.entries.forEach { c ->
                        val n = rows.count { it.cat == c }
                        if (n > 0) FilterChip(
                            selected = section == c.name, onClick = { section = c.name },
                            label = { Text("${c.label} · $n") },
                        )
                    }
                }
            }

            item(key = "search") {
                OutlinedTextField(
                    value = query, onValueChange = { query = it }, singleLine = true,
                    leadingIcon = { Icon(Icons.Filled.Search, null) },
                    placeholder = { Text("دوّر على برنامج") },
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            if (vm.appsLoading && rows.isEmpty()) {
                item(key = "loading") {
                    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                }
            }

            if (blocked.isNotEmpty()) {
                item(key = "h-blocked") { Header("محظور نهائيًا · ${blocked.size}") }
                items(blocked, key = { "b-" + (it.pkg ?: it.name) }) { r -> AppItem(r, vm, onBlock = null) }
            }
            if (free.isNotEmpty()) {
                item(key = "h-free") { Header("على موبايلك · ${free.size}") }
                items(free, key = { "f-" + (it.pkg ?: it.name) }) { r -> AppItem(r, vm, onBlock = { confirm = r.name to r.pkg }) }
            }

            item(key = "add") {
                Column(
                    Modifier.fillMaxWidth().padding(top = 12.dp).clip(RoundedCornerShape(28.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainer).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text("احظر برنامج مش متثبّت", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "اكتب اسمه زي ما بيظهر في المتجر، وزرار التثبيت بتاعه هيتغطّى.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = typed, onValueChange = { typed = it }, singleLine = true,
                        placeholder = { Text("مثلًا: ReelShort") }, shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth(),
                    )
                    Button(
                        onClick = { if (typed.isNotBlank()) confirm = typed.trim() to null },
                        enabled = typed.isNotBlank(),
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("احظر") }
                }
            }
        }
    }

    confirm?.let { (name, pkg) ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            icon = { Icon(Icons.Filled.Lock, null) },
            title = { Text("حظر $name؟") },
            text = { Text("الحظر نهائي، ومش هتقدر ترجّعه من Nova.") },
            confirmButton = {
                Button(
                    onClick = { vm.blockApp(name, pkg); confirm = null; typed = "" },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError,
                    ),
                ) { Text("احظر") }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("لا") } },
        )
    }
}

@Composable
private fun Summary(count: Int, owner: Boolean) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(32.dp))
            .background(MaterialTheme.colorScheme.primaryContainer).padding(horizontal = 20.dp, vertical = 18.dp),
    ) {
        Text("$count", style = MaterialTheme.typography.displayMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
        Text("برنامج محظور نهائيًا", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
        Spacer(Modifier.size(6.dp))
        Text(
            if (owner) "التحكم الكامل شغال: المحظور بيتمسح ومش بيتثبّت تاني." else "التحكم الكامل مش شغال، فالحظر شغال من خلال الشاشة بس.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
        )
    }
}

@Composable
private fun Header(text: String) {
    Text(
        text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 10.dp, bottom = 2.dp, start = 4.dp),
    )
}

/** One app. Long press = move it to another section. Blocked apps only show a lock (no unblock). */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AppItem(r: AppViewModel.AppRow, vm: AppViewModel, onBlock: (() -> Unit)?) {
    var menu by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp))
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .combinedClickable(onClick = {}, onLongClick = { menu = true })
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val icon = r.icon
            if (icon != null) {
                Image(icon, contentDescription = null, modifier = Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)))
            } else {
                Box(
                    Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHighest),
                    contentAlignment = Alignment.Center,
                ) { Text(r.name.take(1).uppercase(), style = MaterialTheme.typography.titleMedium) }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(r.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
                Text(
                    r.cat.label + if (r.blocked && !r.installed) " · مش متثبّت" else "",
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (onBlock == null) {
                Icon(Icons.Filled.Lock, contentDescription = "محظور", tint = MaterialTheme.colorScheme.error)
            } else {
                FilledTonalButton(onClick = onBlock, contentPadding = PaddingValues(horizontal = 16.dp)) { Text("احظر") }
            }
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            Text(
                "انقل لقسم:", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            )
            AppCat.entries.filter { it != r.cat }.forEach { c ->
                DropdownMenuItem(text = { Text(c.label) }, onClick = { vm.moveApp(r, c); menu = false })
            }
        }
    }
}
