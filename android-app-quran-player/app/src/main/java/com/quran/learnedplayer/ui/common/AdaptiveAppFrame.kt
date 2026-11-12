package com.quran.learnedplayer.ui.common

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import com.quran.learnedplayer.ui.theme.findActivity

/** Keeps interactive content in a usable pane when a hinge separates the window. */
@Composable
fun AdaptiveAppFrame(content: @Composable () -> Unit) {
    val activity = LocalView.current.context.findActivity()
    var fold by remember { mutableStateOf<FoldingFeature?>(null) }
    LaunchedEffect(activity) {
        if (activity != null) WindowInfoTracker.getOrCreate(activity).windowLayoutInfo(activity).collect { info ->
            fold = info.displayFeatures.filterIsInstance<FoldingFeature>().firstOrNull {
                it.isSeparating || it.occlusionType == FoldingFeature.OcclusionType.FULL
            }
        }
    }
    var origin by remember { mutableStateOf(Offset.Zero) }
    val density = LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxSize().onGloballyPositioned { origin = it.positionInWindow() }) {
        var left = 0.dp
        var top = 0.dp
        var width = maxWidth
        var height = maxHeight
        fold?.let { feature ->
            with(density) {
                if (feature.orientation == FoldingFeature.Orientation.VERTICAL) {
                    val start = (feature.bounds.left - origin.x).toDp().coerceIn(0.dp, maxWidth)
                    val end = (feature.bounds.right - origin.x).toDp().coerceIn(0.dp, maxWidth)
                    if (start > 0.dp && end < maxWidth) {
                        if (start >= maxWidth - end) width = start
                        else { left = end; width = maxWidth - end }
                    }
                } else {
                    val end = (feature.bounds.bottom - origin.y).toDp().coerceIn(0.dp, maxHeight)
                    if (end > 0.dp && end < maxHeight) { top = end; height = maxHeight - end }
                }
            }
        }
        Box(Modifier.absoluteOffset(left, top).width(width).height(height), contentAlignment = Alignment.TopCenter) {
            Box(Modifier.widthIn(max = 840.dp).fillMaxSize()) { content() }
        }
    }
}
