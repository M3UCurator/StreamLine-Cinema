package com.streamline.cinema.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Process-scoped store for scroll positions and screen-local selections.
 *
 * Tab content is disposed by [androidx.compose.animation.AnimatedContent] whenever
 * the viewer leaves a tab, so `rememberScrollState` / `rememberLazy*State` inside a
 * screen would reset on return. Keeping the state objects here lets every screen
 * come back exactly where it was.
 */
@Stable
class ScreenStateStore {

    private val lists = mutableMapOf<String, LazyListState>()
    private val grids = mutableMapOf<String, LazyGridState>()
    private val scrolls = mutableMapOf<String, ScrollState>()
    private val values = mutableStateMapOf<String, String>()

    fun list(key: String): LazyListState = lists.getOrPut(key) { LazyListState() }

    fun grid(key: String): LazyGridState = grids.getOrPut(key) { LazyGridState() }

    fun scroll(key: String): ScrollState = scrolls.getOrPut(key) { ScrollState(0) }

    fun value(key: String): String? = values[key]

    fun setValue(key: String, value: String) {
        if (values[key] != value) values[key] = value
    }
}

val LocalScreenState = staticCompositionLocalOf { ScreenStateStore() }

@Composable
fun rememberScreenListState(key: String): LazyListState {
    val store = LocalScreenState.current
    return remember(store, key) { store.list(key) }
}

@Composable
fun rememberScreenGridState(key: String): LazyGridState {
    val store = LocalScreenState.current
    return remember(store, key) { store.grid(key) }
}

@Composable
fun rememberScreenScrollState(key: String): ScrollState {
    val store = LocalScreenState.current
    return remember(store, key) { store.scroll(key) }
}
