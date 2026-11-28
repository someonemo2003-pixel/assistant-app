package com.myassistant.app.ui

import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
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
        val t = raw.trim()
        if (t.isEmpty()) return
        // Words without a dot = search on Google.
        val url = when {
            t.startsWith("http") -> t
            '.' in t && ' ' !in t -> "https://$t"
            else -> "https://www.google.com/search?q=" + android.net.Uri.encode(t)
        }
        if (HiddenBrowser.isBlocked(ctx, url)) { notice = "الموقع ده محظور من /extension"; return }
        notice = null
        web.loadUrl(url)
    }

    LaunchedEffect(vm.webUrl) {
        val asked = vm.webUrl
        when {
            asked != null -> { address = asked; go(asked); vm.webUrl = null }
            // Nothing open yet: start at Google instead of a blank page.
            web.url.isNullOrBlank() || web.url == "about:blank" -> { address = "google.com"; go("https://www.google.com") }
            else -> address = web.url.orEmpty()
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            (web.parent as? ViewGroup)?.removeView(web)
            // Back to off-screen size for background use.
            web.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(2200, View.MeasureSpec.EXACTLY))
            web.layout(0, 0, 1080, 2200)
        }
    }

    // One slim bar like a normal browser: back · address · reload. The page gets the rest of the screen.
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                IconButton(onClick = { vm.screen = Screen.Chat }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "رجوع") }
                    androidx.compose.foundation.text.BasicTextField(
                        value = address, onValueChange = { address = it }, singleLine = true,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface, textDirection = androidx.compose.ui.text.style.TextDirection.Ltr),
                        cursorBrush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                        keyboardActions = KeyboardActions(onGo = { go(address) }),
                        modifier = Modifier.weight(1f)
                            .clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.surfaceContainerHigh)
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                    )
                IconButton(onClick = { web.reload() }) { Icon(Icons.Filled.Refresh, "تحديث") }
            }
            notice?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 16.dp)) }
            AndroidView(
                factory = {
                    (web.parent as? ViewGroup)?.removeView(web)
                    web.layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                    web.setBackgroundColor(android.graphics.Color.WHITE)
                    web.requestLayout()
                    web
                },
                modifier = Modifier.fillMaxSize(),
            )
    }
}
