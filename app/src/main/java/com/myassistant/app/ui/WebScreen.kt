package com.myassistant.app.ui

import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.myassistant.app.web.HiddenBrowser

/**
 * "/web": shows Nova's background browser so the user can see the page or sign in to a site
 * themselves (passwords are typed by the user here, never by Nova). Sign-ins stay for later.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WebScreen(vm: AppViewModel) {
    val ctx = LocalContext.current
    val web = remember { HiddenBrowser.view(ctx) }
    var address by remember { mutableStateOf(vm.webUrl ?: web.url.orEmpty()) }
    var notice by remember { mutableStateOf<String?>(null) }

    fun go(raw: String) {
        val url = raw.trim().let { if (it.startsWith("http")) it else "https://$it" }
        if (HiddenBrowser.isBlocked(ctx, url)) { notice = "الموقع ده محظور من /extension"; return }
        notice = null
        web.loadUrl(url)
    }

    LaunchedEffect(vm.webUrl) {
        vm.webUrl?.let { address = it; go(it); vm.webUrl = null }
    }
    DisposableEffect(Unit) {
        onDispose {
            (web.parent as? ViewGroup)?.removeView(web)
            // Back to off-screen size for background use.
            web.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(2200, View.MeasureSpec.EXACTLY))
            web.layout(0, 0, 1080, 2200)
        }
    }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text("متصفح Nova") },
                navigationIcon = { IconButton(onClick = { vm.screen = Screen.Chat }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "رجوع") } },
                actions = { IconButton(onClick = { web.reload() }) { Icon(Icons.Filled.Refresh, "تحديث") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Text(
                "تسجيلات الدخول هنا خاصة بـ Nova، ومنفصلة عن Chrome. سجّل دخولك بنفسك مرة، وبعدها Nova يقدر يستخدم الموقع في الخلفية.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
                    OutlinedTextField(
                        value = address, onValueChange = { address = it }, singleLine = true,
                        placeholder = { Text("example.com") }, shape = RoundedCornerShape(24.dp),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                        keyboardActions = KeyboardActions(onGo = { go(address) }),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            notice?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 16.dp)) }
            AndroidView(
                factory = {
                    (web.parent as? ViewGroup)?.removeView(web)
                    web.layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                    web
                },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
