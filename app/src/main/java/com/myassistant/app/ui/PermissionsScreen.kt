package com.myassistant.app.ui

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.myassistant.app.control.PermItem
import com.myassistant.app.control.Permissions

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PermissionsScreen(vm: AppViewModel) {
    val ctx = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        tick++
        onPauseOrDispose { }
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { tick++ }
    val askScreen by vm.settings.askBeforeScreen.collectAsState()
    val confirmSends by vm.settings.confirmSends.collectAsState()

    fun open(intent: Intent) {
        runCatching { ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onFailure { runCatching { ctx.startActivity(Permissions.appDetails(ctx).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
    }

    val items = remember(tick) { Permissions.all.map { it to it.isGranted(ctx) } }
    val grantedCount = items.count { it.second }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("الصلاحيات ($grantedCount/${items.size})", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = { vm.screen = Screen.Settings }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "رجوع") }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Button(
                    onClick = { launcher.launch(Permissions.allRuntime()) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("فعّل كل الصلاحيات العادية مرة واحدة") }
            }
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                    Column(Modifier.padding(12.dp)) {
                        Text(
                            "على Honor: لو \"التحكم في الشاشة\" أو \"قراءة الإشعارات\" طلعلك إنه إعداد مقيّد (Restricted)، " +
                                "افتح معلومات التطبيق ← ⋮ فوق ← \"السماح بالإعدادات المقيّدة\"، وارجع فعّله.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                        TextButton(onClick = { open(Permissions.appDetails(ctx)) }) { Text("افتح معلومات التطبيق") }
                    }
                }
            }
            items(items, key = { it.first.id }) { (p, granted) ->
                PermRow(p, granted) {
                    when {
                        p.runtime.isNotEmpty() -> launcher.launch(p.runtime.toTypedArray())
                        p.settingsIntent != null -> open(p.settingsIntent.invoke(ctx))
                    }
                }
            }
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("يسألني قبل ما يتحكم في الشاشة", style = MaterialTheme.typography.titleSmall)
                            Text(
                                "مرة واحدة في كل طلب. لو قفلته، هيدوس ويكتب في التطبيقات من غير ما يسأل.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(checked = askScreen, onCheckedChange = { vm.settings.setAskBeforeScreen(it) })
                    }
                }
            }
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("يسألني قبل الإرسال والمكالمات", style = MaterialTheme.typography.titleSmall)
                            Text(
                                "واتساب، SMS، إيميل، مكالمات. مقفول = ينفّذ على طول لما تطلب.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(checked = confirmSends, onCheckedChange = { vm.settings.setConfirmSends(it) })
                    }
                }
            }
        }
    }
}

@Composable
private fun PermRow(p: PermItem, granted: Boolean, onGrant: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
        Row(Modifier.padding(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(p.title, style = MaterialTheme.typography.titleSmall)
                Text(p.why, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.size(8.dp))
            if (granted) {
                Icon(Icons.Filled.CheckCircle, "مفعّلة", tint = MaterialTheme.colorScheme.tertiary)
            } else {
                FilledTonalButton(onClick = onGrant) { Text("فعّل") }
            }
        }
    }
}
