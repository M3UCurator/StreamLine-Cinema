package com.streamline.cinema.ui

import androidx.annotation.DrawableRes
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.streamline.cinema.R

/** Index order is persisted in `CinemaProfile.avatarIndex`; append, never reorder. */
data class ProfileAvatarOption(@DrawableRes val res: Int, val label: String)

val profileAvatarOptions: List<ProfileAvatarOption> = listOf(
    ProfileAvatarOption(R.drawable.avatar_01_gecko, "Gecko"),
    ProfileAvatarOption(R.drawable.avatar_02_alien, "Alien"),
    ProfileAvatarOption(R.drawable.avatar_03_sloth, "Sloth"),
    ProfileAvatarOption(R.drawable.avatar_04_koala, "Koala"),
    ProfileAvatarOption(R.drawable.avatar_05_ghost, "Ghost"),
    ProfileAvatarOption(R.drawable.avatar_06_teal_robot, "Teal robot"),
    ProfileAvatarOption(R.drawable.avatar_07_fox, "Fox"),
    ProfileAvatarOption(R.drawable.avatar_08_robot, "Robot"),
    ProfileAvatarOption(R.drawable.avatar_09_octopus, "Octopus"),
    ProfileAvatarOption(R.drawable.avatar_10_smiley, "Cool smiley"),
    ProfileAvatarOption(R.drawable.avatar_11_green_alien, "Green alien"),
    ProfileAvatarOption(R.drawable.avatar_12_cat, "White cat"),
    ProfileAvatarOption(R.drawable.avatar_13_owl, "Owl"),
    ProfileAvatarOption(R.drawable.avatar_14_puppy, "Puppy"),
    ProfileAvatarOption(R.drawable.avatar_15_tiger, "Grumpy tiger")
)

fun profileAvatarOption(index: Int): ProfileAvatarOption =
    profileAvatarOptions[index.coerceIn(0, profileAvatarOptions.lastIndex)]

@Composable
fun ProfileAvatar(
    index: Int,
    size: Dp,
    modifier: Modifier = Modifier,
    contentDescription: String? = null
) {
    Image(
        painter = painterResource(profileAvatarOption(index).res),
        contentDescription = contentDescription,
        contentScale = ContentScale.Crop,
        modifier = modifier.size(size).clip(CircleShape)
    )
}

/** Circular choices that wrap to the dialog width; the ring marks the current avatar. */
@Composable
fun AvatarPicker(
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val gap = 10.dp
    val cell = 60.dp
    BoxWithConstraints(modifier) {
        // The platform dialog width is narrower than its nominal size and
        // varies with density and font scale, so derive the columns from the
        // width actually granted instead of assuming a fixed count.
        val perRow = ((maxWidth + gap) / (cell + gap)).toInt().coerceAtLeast(1)
        Column(verticalArrangement = Arrangement.spacedBy(gap)) {
            profileAvatarOptions.chunked(perRow).forEachIndexed { rowIndex, row ->
                Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                    row.forEachIndexed { column, option ->
                        val index = rowIndex * perRow + column
                        AvatarChoice(
                            index = index,
                            option = option,
                            selected = index == selected,
                            onClick = { onSelect(index) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AvatarChoice(
    index: Int,
    option: ProfileAvatarOption,
    selected: Boolean,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val sounds = LocalUiSounds.current
    val scale by animateFloatAsState(
        targetValue = if (focused) 1.08f else 1f,
        animationSpec = spring(dampingRatio = 0.55f, stiffness = 650f),
        label = "avatarChoiceScale"
    )
    Box(
        modifier = Modifier
            .size(60.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) sounds.focus()
            }
            .selectable(
                selected = selected,
                role = Role.RadioButton,
                onClick = {
                    sounds.select()
                    onClick()
                }
            )
            .then(
                when {
                    selected -> Modifier.border(2.dp, Cinema.Gold, CircleShape)
                    focused -> Modifier.border(2.dp, Color.White.copy(alpha = 0.75f), CircleShape)
                    else -> Modifier
                }
            )
            .padding(5.dp),
        contentAlignment = Alignment.Center
    ) {
        ProfileAvatar(
            index = index,
            size = 50.dp,
            contentDescription = option.label
        )
    }
}
