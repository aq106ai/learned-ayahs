package com.quran.learnedplayer.ui.common

import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlin.math.abs

private val SWIPE_THRESHOLD = 56.dp

/**
 * Swipe left/right to move to the next/previous item. Reading is always right-to-left here, so
 * dragging right (the way an Arabic page turns) advances and dragging left goes back. A no-op
 * when [enabled] is false, so it never competes with a child's own gestures.
 */
fun Modifier.swipeToAdvance(
    enabled: Boolean,
    onAdvance: () -> Unit,
    onGoBack: () -> Unit,
): Modifier {
    if (!enabled) return this
    return this.pointerInput(Unit) {
        val thresholdPx = SWIPE_THRESHOLD.toPx()
        var totalDrag = 0f
        detectHorizontalDragGestures(
            onDragStart = { totalDrag = 0f },
            onHorizontalDrag = { change, dragAmount ->
                change.consume()
                totalDrag += dragAmount
            },
            onDragEnd = {
                if (abs(totalDrag) < thresholdPx) return@detectHorizontalDragGestures
                val draggedRight = totalDrag > 0f
                if (draggedRight) onAdvance() else onGoBack()
            },
        )
    }
}
