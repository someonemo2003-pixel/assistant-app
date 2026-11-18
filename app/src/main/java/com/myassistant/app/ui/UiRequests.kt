package com.myassistant.app.ui

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Lets tools open a screen in the app (there's no settings page; Nova opens what's needed). */
object UiRequests {
    private val _open = MutableStateFlow<String?>(null)
    val open: StateFlow<String?> = _open.asStateFlow()
    fun open(section: String) { _open.value = section }
    fun consume() { _open.value = null }
}
