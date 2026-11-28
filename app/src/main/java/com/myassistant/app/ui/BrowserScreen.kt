package com.myassistant.app.ui

import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import com.myassistant.app.admin.BrowserPolicy
import com.myassistant.app.admin.DeviceOwner

/**
 * "/extension": website rules for Chrome and Edge. Blocked sites are for good (confirm first, no unblock);
 * the switches can be changed, turning one off asks first.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserScreen(vm: AppViewModel) {
    val ctx = vm.getApplication<android.app.Application>()
    var tick by remember { mutableIntStateOf(0) } // re-read after every change
    val owner = remember { DeviceOwner.isOwner(ctx) }
    val sites = remember(tick) { BrowserPolicy.sites(ctx) }
    var typed by rememberSaveable { mutableStateOf("") }
    var confirmSites by remember { mutableStateOf<Pair<String, List<String>>?>(null) } // title to sites
    var confirmOff by remember { mutableStateOf<Pair<String, String>?>(null) } // key to label
    var error by remember { mutableStateOf<String?>(null) }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text("المتصفح والمواقع") },
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
            item(key = "summary") {
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(32.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer).padding(horizontal = 20.dp, vertical = 18.dp),
                ) {
                    Text("${sites.size}", style = MaterialTheme.typography.displayMedium, fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer)
                    Text("موقع محظور نهائيًا", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    Spacer(Modifier.size(6.dp))
                    Text(
                        if (owner) "القواعد بتتحط جوه Chrome وEdge نفسهم، ومينفعش تتشال من المتصفح."
                        else "التحكم الكامل مش شغال، فالقواعد دي مش هتتطبق دلوقتي.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                    )
                }
            }

            item(key = "h-browsers") { SectionTitle("المتصفحات") }
            items(BrowserPolicy.BROWSERS, key = { "br-" + it.first }) { (pkg, name) ->
                val installed = remember(tick) { BrowserPolicy.isInstalled(ctx, pkg) }
                val applied = remember(tick) { BrowserPolicy.applied(ctx, pkg) }
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainer).padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.secondaryContainer),
                        contentAlignment = Alignment.Center,
                    ) { Icon(Icons.Filled.Language, null, tint = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.size(20.dp)) }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(name, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            when {
                                !installed -> "مش متثبّت، القواعد هتتحط أول ما يتثبّت"
                                applied -> "القواعد شغالة"
                                sites.isEmpty() && !BrowserPolicy.noIncognito(ctx) -> "مفيش قواعد لسه"
                                else -> "القواعد لسه ما اتحطتش"
                            },
                            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (installed && applied) Icon(Icons.Filled.Check, "شغال", tint = MaterialTheme.colorScheme.primary)
                }
            }

            item(key = "h-switches") { SectionTitle("إعدادات") }
            item(key = "switches") {
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(MaterialTheme.colorScheme.surfaceContainer),
                ) {
                    listOf(
                        Triple("no_incognito", "اقفل التصفح المتخفي", "علشان المواقع المحظورة متتفتحش من Incognito أو InPrivate") to BrowserPolicy.noIncognito(ctx),
                        Triple("safe_search", "البحث الآمن في جوجل", "يخفي النتايج الخادشة من البحث") to BrowserPolicy.safeSearch(ctx),
                        Triple("yt_restrict", "يوتيوب الوضع المقيّد", "يخفي الفيديوهات غير المناسبة على يوتيوب من المتصفح") to BrowserPolicy.youtubeRestricted(ctx),
                    ).forEach { (info, on) ->
                        val (key, title, hint) = info
                        val checked = remember(tick) { on }
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(title, style = MaterialTheme.typography.bodyLarge)
                                Text(hint, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Spacer(Modifier.width(12.dp))
                            Switch(
                                checked = checked,
                                enabled = owner,
                                onCheckedChange = { v ->
                                    if (v) { BrowserPolicy.setSwitch(ctx, key, true); tick++ } else confirmOff = key to title
                                },
                            )
                        }
                    }
                }
            }

            item(key = "h-presets") { SectionTitle("احظر مجموعة مرة واحدة") }
            items(BrowserPolicy.PRESETS, key = { "p-" + it.title }) { p ->
                val left = p.sites.filter { it !in sites }
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainer).padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(p.title, style = MaterialTheme.typography.bodyLarge)
                        Text(p.sites.take(4).joinToString(" · ") + if (p.sites.size > 4) " …" else "",
                            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    }
                    Spacer(Modifier.width(8.dp))
                    if (left.isEmpty()) Icon(Icons.Filled.Lock, "محظور", tint = MaterialTheme.colorScheme.error)
                    else FilledTonalButton(onClick = { confirmSites = p.title to left }, enabled = owner,
                        contentPadding = PaddingValues(horizontal = 16.dp)) { Text("احظر") }
                }
            }

            item(key = "add") {
                Column(
                    Modifier.fillMaxWidth().padding(top = 6.dp).clip(RoundedCornerShape(28.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainer).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text("احظر موقع", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "اكتب الموقع، زي tiktok.com. ولو عايز صفحة معيّنة بس اكتبها كاملة، زي youtube.com/shorts.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = typed, onValueChange = { typed = it; error = null }, singleLine = true,
                        placeholder = { Text("example.com") }, shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth(),
                        isError = error != null, supportingText = error?.let { { Text(it) } },
                    )
                    Button(
                        onClick = {
                            val s = BrowserPolicy.normalize(typed)
                            when {
                                s == null -> error = "ده مش شكل موقع"
                                s in sites -> error = "محظور أصلًا"
                                else -> confirmSites = s to listOf(s)
                            }
                        },
                        enabled = typed.isNotBlank() && owner,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("احظر") }
                }
            }

            if (sites.isNotEmpty()) {
                item(key = "h-sites") { SectionTitle("محظور نهائيًا · ${sites.size}") }
                items(sites, key = { "s-$it" }) { s ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainer).padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(s, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f), maxLines = 1)
                        Icon(Icons.Filled.Lock, "محظور", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
    }

    confirmSites?.let { (title, list) ->
        AlertDialog(
            onDismissRequest = { confirmSites = null },
            icon = { Icon(Icons.Filled.Lock, null) },
            title = { Text("حظر $title؟") },
            text = {
                Text((if (list.size > 1) list.joinToString("\n") + "\n\n" else "") + "الحظر نهائي، ومش هتقدر ترجّعه من Nova.")
            },
            confirmButton = {
                Button(
                    onClick = { BrowserPolicy.block(ctx, list); confirmSites = null; typed = ""; tick++ },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError,
                    ),
                ) { Text("احظر") }
            },
            dismissButton = { TextButton(onClick = { confirmSites = null }) { Text("لا") } },
        )
    }

    confirmOff?.let { (key, title) ->
        AlertDialog(
            onDismissRequest = { confirmOff = null },
            title = { Text("تقفل \"$title\"؟") },
            confirmButton = { TextButton(onClick = { BrowserPolicy.setSwitch(ctx, key, false); confirmOff = null; tick++ }) { Text("اقفله") } },
            dismissButton = { Button(onClick = { confirmOff = null }) { Text("سيبه شغال") } },
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 10.dp, bottom = 2.dp, start = 4.dp),
    )
}
