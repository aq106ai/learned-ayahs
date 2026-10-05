package com.quran.learnedplayer.ui.common

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
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

/**
 * Tap anywhere to step forward — a tap, not the end of a drag. `clickable` fires when any press
 * is released in bounds, however far it travelled, unless something else consumed the drag; with
 * swiping turned off nothing does, so a swipe across the reader stepped it as if it were a tap.
 * Unlike `clickable` this also leaves the children's semantics unmerged, so the reader's own
 * nodes stay visible to accessibility services and tests.
 */
fun Modifier.advanceOnTap(enabled: Boolean, label: String, onTap: () -> Unit): Modifier {
    if (!enabled) return this
    return composed {
        val currentOnTap by rememberUpdatedState(onTap)
        semantics {
            onClick(label = label) {
                currentOnTap()
                true
            }
        }.pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown()
                val slop = viewConfiguration.touchSlop
                while (true) {
                    val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                    // Taken by a child (the pager scrolling, a word's own tap), or moved far
                    // enough to be a swipe: either way it is not a tap.
                    if (change.isConsumed) break
                    if ((change.position - down.position).getDistance() > slop) break
                    if (change.changedToUp()) {
                        change.consume()
                        currentOnTap()
                        break
                    }
                }
            }
        }
    }
}
