package io.grovs.example.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.grovs.example.AppGraph
import io.grovs.example.actions.LogEntry
import io.grovs.example.actions.LogState
import io.grovs.example.data.formatClock
import io.grovs.example.ui.controls.LocalSheetHost
import io.grovs.example.ui.controls.SheetHost
import io.grovs.example.ui.controls.SheetHostView
import io.grovs.example.ui.controls.StatusDot
import io.grovs.example.ui.events.EventsScreen
import io.grovs.example.ui.home.HomeScreen
import io.grovs.example.ui.inapp.InAppScreen
import io.grovs.example.ui.links.LinksScreen
import io.grovs.example.ui.logs.LogsPanel
import io.grovs.example.ui.theme.AppTheme
import io.grovs.example.ui.theme.AppType

enum class Tab(val title: String, val icon: ImageVector) {
    HOME("Home", TabIcons.Home),
    LINKS("Links", TabIcons.Links),
    EVENTS("Events", TabIcons.Events),
    IN_APP("In-app", TabIcons.InApp),
}

@Composable
fun AppScaffold() {
    val c = AppTheme.colors
    var tab by rememberSaveable { mutableStateOf(Tab.HOME) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    val sheetHost = remember { SheetHost() }
    val entries by AppGraph.log.entries.collectAsState()
    val unseenError by AppGraph.log.unseenError.collectAsState()
    val panel by AppGraph.panel.collectAsState()

    CompositionLocalProvider(LocalSheetHost provides sheetHost) {
        Surface(color = c.background, contentColor = c.text) {
            Box(Modifier.fillMaxSize()) {
                if (showSettings) {
                    BackHandler { showSettings = false }
                    Box(Modifier.fillMaxSize().statusBarsPadding().imePadding()) {
                        PlaceholderScreen("Settings")
                    }
                } else {
                    Column(Modifier.fillMaxSize()) {
                        Box(Modifier.weight(1f).statusBarsPadding().imePadding()) {
                            when (tab) {
                                Tab.HOME -> HomeScreen(onOpenSettings = { showSettings = true })
                                Tab.LINKS -> LinksScreen()
                                Tab.EVENTS -> EventsScreen()
                                Tab.IN_APP -> InAppScreen()
                            }
                        }
                        ConsoleStrip(entries.firstOrNull(), unseenError, onOpen = { AppGraph.openPanel() })
                        TabBar(tab, onSelect = { tab = it })
                    }
                }
                LogsPanel(panel, entries, onClose = AppGraph::closePanel)
                SheetHostView(sheetHost)
            }
        }
    }
}

@Composable
fun PlaceholderScreen(title: String) {
    Text(title, style = AppType.title, color = AppTheme.colors.text, modifier = Modifier.padding(16.dp))
}

@Composable
private fun ConsoleStrip(latest: LogEntry?, unseenError: Boolean, onOpen: () -> Unit) {
    val c = AppTheme.colors
    val open by rememberUpdatedState(onOpen)
    Column {
        Divider(color = c.border, thickness = 1.dp)
        Row(
            Modifier.fillMaxWidth().height(36.dp).background(c.surface)
                .clickable(onClick = onOpen)
                .pointerInput(Unit) { detectVerticalDragGestures { _, dragAmount -> if (dragAmount < -8f) open() } }
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            StatusDot(if (unseenError) LogState.ERROR else latest?.state ?: LogState.NEUTRAL)
            Text(
                latest?.title ?: "No events yet",
                style = AppType.caption, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (latest != null) Text(formatClock(latest.time), style = AppType.caption, color = c.textFaint)
            Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Open logs", tint = c.textFaint, modifier = Modifier.size(16.dp))
        }
    }
}

@Composable
private fun TabBar(selected: Tab, onSelect: (Tab) -> Unit) {
    val c = AppTheme.colors
    Column {
        Divider(color = c.border, thickness = 1.dp)
        NavigationBar(containerColor = c.background, tonalElevation = 0.dp) {
            Tab.values().forEach { tab ->
                NavigationBarItem(
                    selected = tab == selected,
                    onClick = { onSelect(tab) },
                    icon = { Icon(tab.icon, contentDescription = null) },
                    label = { Text(tab.title, style = AppType.label) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = c.accent,
                        selectedTextColor = c.accent,
                        indicatorColor = c.accentSoft,
                        unselectedIconColor = c.textMuted,
                        unselectedTextColor = c.textMuted,
                    ),
                )
            }
        }
    }
}
