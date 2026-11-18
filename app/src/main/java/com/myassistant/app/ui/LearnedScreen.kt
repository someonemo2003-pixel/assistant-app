package com.myassistant.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LearnedScreen(vm: AppViewModel) {
    LaunchedEffect(Unit) { vm.loadExperiences() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("اللي العقل السريع اتعلمه (${vm.experiences.size})", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = { vm.screen = Screen.Chat }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "رجوع") }
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
                Text(
                    "كل جملة هنا بتتنفّذ فوراً ومن غير نت. 👤 = إنت علّمته، 🤖 = اتعلمه من العقل الكبير.\n" +
                        "عشان تعلّمه اختصار، قول في الشات مثلاً: \"لما أقول وضع النوم اعمل منبه 7 الصبح\".",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (vm.experiences.isEmpty()) {
                item {
                    Box(Modifier.fillMaxWidth().padding(top = 48.dp), contentAlignment = Alignment.Center) {
                        Text("لسه ما اتعلمش حاجة", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            items(vm.experiences, key = { it.id }) { e ->
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
                    Row(Modifier.padding(start = 16.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                (if (e.source == "user") "👤 " else "🤖 ") + "\"${e.phrase}\"",
                                style = MaterialTheme.typography.titleSmall,
                            )
                            Text(
                                "${vm.tools[e.tool]?.title ?: e.tool} · ${e.args} · استُخدم ${e.uses} مرة",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                            )
                        }
                        IconButton(onClick = { vm.deleteExperience(e.id) }) {
                            Icon(Icons.Filled.Delete, "حذف", tint = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }
    }
}
