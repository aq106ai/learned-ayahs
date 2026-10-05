package com.quran.learnedplayer.ui.common

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.quran.learnedplayer.ui.theme.Panel

enum class SidePanelEdge { START, END }

/**
 * A drawer-like panel that slides in from [edge], opened/closed only by [visible] — never by an
 * edge drag. The player's central content owns every horizontal drag (swipe-to-advance an ayah),
 * so a panel that also claimed edge drags would starve one gesture or the other; button/handle
 * triggering sidesteps that entirely.
 */
@Composable
fun SidePanel(
    visible: Boolean,
    edge: SidePanelEdge,
    onDismiss: () -> Unit,
    widthFraction: Float = 0.86f,
    content: @Composable () -> Unit,
) {
    if (visible) {
        BackHandler(onBack = onDismiss)
    }
    Box(Modifier.fillMaxSize()) {
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(tween(200)),
            exit = fadeOut(tween(200)),
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.5f))
                    .clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() },
                        onClick = onDismiss,
                    ),
            )
        }
        AnimatedVisibility(
            visible = visible,
            enter = slideInHorizontally(tween(220)) { fullWidth ->
                if (edge == SidePanelEdge.START) -fullWidth else fullWidth
            },
            exit = slideOutHorizontally(tween(220)) { fullWidth ->
                if (edge == SidePanelEdge.START) -fullWidth else fullWidth
            },
            modifier = Modifier.align(if (edge == SidePanelEdge.START) Alignment.CenterStart else Alignment.CenterEnd),
        ) {
            Box(
                Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(widthFraction)
                    .background(Panel),
            ) {
                content()
            }
        }
    }
}
