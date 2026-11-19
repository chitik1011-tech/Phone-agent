package dev.phoneagent.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.phoneagent.R

enum class Destination(val labelResource: Int) {
    AGENTS(R.string.nav_agents),
    WORKSPACE(R.string.nav_workspace),
    TERMINAL(R.string.nav_terminal),
    HOME(R.string.nav_home),
    SETTINGS(R.string.nav_settings),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhoneAgentShell(
    destination: Destination,
    onNavigate: (Destination) -> Unit,
    modifier: Modifier = Modifier,
    animateNavigation: Boolean = true,
    haptics: Boolean = false,
    content: @Composable (Destination) -> Unit,
) {
    val stateHolder = rememberSaveableStateHolder()
    val hapticFeedback = LocalHapticFeedback.current
    val navigate: (Destination) -> Unit = { next ->
        if (next != destination) {
            if (haptics) hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
            onNavigate(next)
        }
    }
    BoxWithConstraints(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        val wide = maxWidth >= 720.dp
        val renderedContent: @Composable () -> Unit = {
            if (animateNavigation) {
                AnimatedContent(
                    targetState = destination,
                    transitionSpec = {
                        fadeIn(tween(160, delayMillis = 65)) togetherWith fadeOut(tween(100))
                    },
                    label = "workspace navigation",
                ) { visible ->
                    stateHolder.SaveableStateProvider(visible.name) { content(visible) }
                }
            } else {
                stateHolder.SaveableStateProvider(destination.name) { content(destination) }
            }
        }
        if (wide) {
            Row(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                NavigationRail(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    windowInsets = WindowInsets(0, 0, 0, 0),
                ) {
                    Destination.entries.forEach { item ->
                        NavigationRailItem(
                            selected = item == destination,
                            onClick = { navigate(item) },
                            icon = { DestinationIcon(item) },
                            label = { Text(stringResource(item.labelResource), maxLines = 1) },
                            colors = NavigationRailItemDefaults.colors(
                                indicatorColor = MaterialTheme.colorScheme.secondaryContainer,
                            ),
                        )
                    }
                }
                Box(Modifier.weight(1f).fillMaxSize()) { renderedContent() }
            }
        } else {
            Scaffold(
                containerColor = MaterialTheme.colorScheme.background,
                contentWindowInsets = WindowInsets.safeDrawing.only(
                    WindowInsetsSides.Top + WindowInsetsSides.Horizontal,
                ),
                bottomBar = {
                    NavigationBar(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                        tonalElevation = 0.dp,
                    ) {
                        Destination.entries.forEach { item ->
                            NavigationBarItem(
                                selected = item == destination,
                                onClick = { navigate(item) },
                                icon = { DestinationIcon(item) },
                                label = {
                                    Text(
                                        stringResource(item.labelResource),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        style = MaterialTheme.typography.labelSmall,
                                    )
                                },
                                alwaysShowLabel = true,
                                colors = NavigationBarItemDefaults.colors(
                                    indicatorColor = MaterialTheme.colorScheme.secondaryContainer,
                                ),
                            )
                        }
                    }
                },
            ) { insets ->
                Box(Modifier.fillMaxSize().padding(insets).consumeWindowInsets(insets).imePadding()) {
                    renderedContent()
                }
            }
        }
    }
}

@Composable
internal fun DestinationIcon(destination: Destination, modifier: Modifier = Modifier) {
    val tint = LocalContentColor.current
    Canvas(modifier.size(24.dp)) {
        val unit = size.minDimension / 24
        val width = 1.7f * unit
        fun point(x: Number, y: Number) = Offset(x.toFloat() * unit, y.toFloat() * unit)
        fun line(x1: Number, y1: Number, x2: Number, y2: Number) {
            drawLine(tint, point(x1, y1), point(x2, y2), width, StrokeCap.Round)
        }
        fun roundBox(left: Number, top: Number, w: Number, h: Number, radius: Number) {
            drawRoundRect(
                color = tint,
                topLeft = point(left, top),
                size = Size(w.toFloat() * unit, h.toFloat() * unit),
                cornerRadius = CornerRadius(radius.toFloat() * unit),
                style = Stroke(width),
            )
        }
        when (destination) {
            Destination.HOME -> {
                line(3, 11, 12, 3)
                line(12, 3, 21, 11)
                line(5, 9.5, 5, 20)
                line(5, 20, 19, 20)
                line(19, 20, 19, 9.5)
                line(10, 20, 10, 14)
                line(10, 14, 14, 14)
                line(14, 14, 14, 20)
            }
            Destination.WORKSPACE -> {
                roundBox(3, 6, 18, 14, 2)
                line(4, 10, 20, 10)
                line(7, 4, 11, 4)
                line(11, 4, 13, 6)
            }
            Destination.TERMINAL -> {
                roundBox(3, 4, 18, 16, 2.5)
                line(7, 9, 10, 12)
                line(10, 12, 7, 15)
                line(13, 15, 17, 15)
            }
            Destination.AGENTS -> {
                line(12, 8, 12, 12)
                line(6, 12, 18, 12)
                line(6, 12, 6, 16)
                line(18, 12, 18, 16)
                roundBox(9, 3, 6, 5, 1.5)
                roundBox(3, 16, 6, 5, 1.5)
                roundBox(15, 16, 6, 5, 1.5)
            }
            Destination.SETTINGS -> {
                line(4, 6, 9, 6)
                line(13, 6, 20, 6)
                line(4, 12, 15, 12)
                line(19, 12, 20, 12)
                line(4, 18, 7, 18)
                line(11, 18, 20, 18)
                listOf(point(11, 6), point(17, 12), point(9, 18)).forEach { center ->
                    drawCircle(tint, radius = 2 * unit, center = center, style = Stroke(width))
                }
            }
        }
    }
}
