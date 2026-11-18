package com.streamline.cinema.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.streamline.cinema.ui.Cinema
import com.streamline.cinema.ui.CinemaDimens
import com.streamline.cinema.ui.CinemaType
import com.streamline.cinema.ui.Glass
import com.streamline.cinema.ui.LocalUiSounds
import com.streamline.cinema.ui.glassSurface
import kotlinx.coroutines.delay

/** Country and genre share one picker; the band and Settings both open it. */
@Composable
internal fun ChartPickerDialog(
    title: String,
    selectedKey: String,
    /** Label to key, in the order the catalogue lists them. */
    options: List<Pair<String, String>>,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val listState = rememberLazyListState()
    val selectedIndex = remember(options, selectedKey) {
        options.indexOfFirst { it.second == selectedKey }.takeIf { it >= 0 }
    }
    // Focus alone cannot scroll in touch mode, where the window has no view
    // focus after a tap; scroll explicitly so the current value is on screen.
    LaunchedEffect(selectedIndex) {
        if (selectedIndex != null) listState.scrollToItem(selectedIndex)
    }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Column(
            Modifier
                .width(460.dp)
                .heightIn(max = 600.dp)
                .glassSurface(
                    shape = RoundedCornerShape(CinemaDimens.CardRadius),
                    base = Glass.Sheet
                )
                .padding(horizontal = 18.dp, vertical = 16.dp)
        ) {
            Text(title, color = Cinema.TextPrimary, style = CinemaType.SectionTitle)
            Spacer(Modifier.height(10.dp))
            LazyColumn(
                state = listState,
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                itemsIndexed(options, key = { _, option -> option.second }) { index, option ->
                    PickerRow(
                        label = option.first,
                        selected = option.second == selectedKey,
                        focusOnStart = option.second == selectedKey ||
                            (selectedKey.isBlank() && index == 0),
                        onClick = { onPick(option.second) }
                    )
                }
            }
        }
    }
}

@Composable
private fun PickerRow(
    label: String,
    selected: Boolean,
    focusOnStart: Boolean,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val sounds = LocalUiSounds.current
    val rowFocus = remember { FocusRequester() }

    // Opening the picker lands on the current value, so a tap on the trigger
    // is always one press away from the state it reflects.
    LaunchedEffect(Unit) {
        if (focusOnStart) {
            delay(160)
            runCatching { rowFocus.requestFocus() }
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .focusRequester(rowFocus)
            .glassSurface(
                shape = RoundedCornerShape(10.dp),
                focused = focused,
                base = if (selected) Cinema.Gold else Glass.Control,
                tint = if (focused && !selected) Glass.AccentWash else Color.Transparent
            )
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) sounds.focus()
            }
            .semantics {
                role = Role.RadioButton
                this.selected = selected
            }
            .clickable {
                sounds.select()
                onClick()
            }
            .padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            color = when {
                selected -> Cinema.Ink
                focused -> Cinema.TextPrimary
                else -> Cinema.TextSecondary
            },
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        if (selected) {
            Text("✓", color = Cinema.Ink, fontSize = 12.sp)
        }
    }
}
