package com.streamline.cinema.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.streamline.cinema.ui.Cinema
import com.streamline.cinema.ui.CinemaDimens
import com.streamline.cinema.ui.CinemaType
import com.streamline.cinema.ui.Glass
import com.streamline.cinema.ui.PinDisplay
import com.streamline.cinema.ui.PinPad
import com.streamline.cinema.ui.TvButton
import com.streamline.cinema.ui.TvButtonStyle
import com.streamline.cinema.ui.glassSurface
import kotlinx.coroutines.delay

/**
 * Leanback parental PIN gate. Used for every Kids Mode lockout: leaving a Kids
 * profile, opening Search / Imports / Settings and changing approved content.
 */
@Composable
fun PinDialog(
    title: String,
    message: String,
    onDismiss: () -> Unit,
    onSubmit: (String) -> Boolean
) {
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val firstKey = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(180)
        runCatching { firstKey.requestFocus() }
    }
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .width(420.dp)
                .glassSurface(
                    shape = RoundedCornerShape(CinemaDimens.CardRadius),
                    base = Glass.Sheet
                )
                .padding(24.dp)
        ) {
            Text(title, color = Cinema.TextPrimary, style = CinemaType.SectionTitle)
            Spacer(Modifier.height(6.dp))
            Text(message, color = Cinema.TextMuted, fontSize = 12.sp, lineHeight = 16.sp)
            Spacer(Modifier.height(16.dp))
            PinDisplay(label = "Parental PIN", value = pin, active = true, onClick = {})
            Spacer(Modifier.height(14.dp))
            PinPad(
                value = pin,
                onValueChange = {
                    pin = it.take(4)
                    error = null
                },
                firstFocus = firstKey
            )
            if (error != null) {
                Spacer(Modifier.height(8.dp))
                Text(error!!, color = Cinema.Offline, fontSize = 12.sp)
            }
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TvButton(
                    label = "UNLOCK",
                    glyph = "▶",
                    style = TvButtonStyle.PRIMARY,
                    enabled = pin.length == 4,
                    onClick = {
                        if (onSubmit(pin)) {
                            onDismiss()
                        } else {
                            error = "That PIN is not correct"
                            pin = ""
                        }
                    }
                )
                TvButton("CANCEL", onDismiss, style = TvButtonStyle.GHOST)
            }
        }
    }
}
