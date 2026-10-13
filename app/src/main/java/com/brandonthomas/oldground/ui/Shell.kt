package com.brandonthomas.oldground.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.brandonthomas.oldground.Nav
import com.brandonthomas.oldground.Screen
import com.brandonthomas.oldground.ShellState
import com.brandonthomas.oldground.Tab
import com.brandonthomas.oldground.data.Repo
import com.brandonthomas.oldground.data.Sites
import com.brandonthomas.oldground.location.LocationHub
import com.brandonthomas.oldground.location.TrackService

/**
 * The whole app: four tabs along the bottom (Map, Sites, Finds, Old maps). The map is
 * created once and stays alive under the other tabs, so it never loses your place.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AppShell(start: Pair<Tab, List<Screen>>?, startMap: Screen.Hunt?) {
    val data by Repo.data.collectAsState()
    val shell = remember {
        val last = data.settings.lastOldMap?.takeIf { id -> data.maps.any { it.id == id && it.aligned } }
        ShellState(Tab.MAP, last).apply {
            start?.let { (t, pages) ->
                if ((t == Tab.FINDS && pages.firstOrNull() is Screen.FindsRoot) || t == Tab.COMMUNITY) pages.forEach { go(it) } else {
                    go(when (t) { Tab.SITES -> Screen.SitesRoot; Tab.OLD -> Screen.OldMapsRoot; else -> Screen.SitesRoot })
                    pages.forEach { go(it) }
                }
            }
            startMap?.let { go(it) }
        }
    }
    val nav = remember { Nav(shell) }
    BackHandler(enabled = shell.canGoBack) { shell.back() }

    // Keep the screen awake while exploring on the map.
    val view = LocalView.current
    val onMap = shell.tab == Tab.MAP
    DisposableEffect(onMap, data.settings.keepScreenOn) {
        view.keepScreenOn = onMap && data.settings.keepScreenOn
        onDispose { view.keepScreenOn = false }
    }

    Column(Modifier.fillMaxSize().background(C.bg)) {
        Box(Modifier.weight(1f).fillMaxWidth().consumeWindowInsets(WindowInsets.navigationBars)) {
            key(shell.oldMapId) {
                HuntScreen(nav, shell.oldMapId, shell.request, visible = onMap, onPickOldMap = { shell.setOldMap(it) })
            }
            if (!onMap) {
                val stack = shell.stacks.getValue(shell.tab)
                val top = stack.last()
                // Leave room for the visit bar, so it never covers a page's own buttons.
                val barShown = data.visits.any { it.endedAt == null } || data.recording != null
                Box(Modifier.fillMaxSize().background(C.bg).padding(bottom = if (barShown) 64.dp else 0.dp)) {
                    key(shell.tab, stack.size, top) { Page(nav, top) }
                }
                RecordingBar(nav, Modifier.align(Alignment.BottomCenter).padding(horizontal = 10.dp, vertical = 10.dp))
            }
        }
        TabBar(shell.tab) { shell.select(it) }
    }
}

@Composable
private fun Page(nav: Nav, s: Screen) {
    when (s) {
        Screen.SitesRoot -> SitesRoot(nav)
        is Screen.FindsRoot -> FindsRoot(nav, s.sub)
        Screen.OldMapsRoot -> OldMapsScreen(nav)
        is Screen.Site -> SiteScreen(nav, s.id, s.debug)
        is Screen.Visit -> VisitScreen(nav, s.siteId, s.day)
        is Screen.Align -> AlignScreen(nav, s.mapId)
        Screen.Settings -> SettingsScreen(nav)
        Screen.Help -> HelpScreen(nav)
        is Screen.PermissionSlip -> PermissionSlipScreen(nav, s.siteId, s.debug)
        is Screen.HelpTopic -> HelpTopicScreen(nav, s.id)
        is Screen.CommunityRoot -> CommunityRoot(nav, s.debug)
        is Screen.CommunityPost -> CommunityPostScreen(nav, s.id, s.debug)
        is Screen.Compose -> ComposeScreen(nav, s.pinId, s.debug)
        is Screen.JoinPage -> JoinScreen(nav, s.debug)
        is Screen.Hunt -> {}
    }
}

@Composable
private fun TabBar(current: Tab, onSelect: (Tab) -> Unit) {
    Column(Modifier.fillMaxWidth().background(C.panel).navigationBarsPadding()) {
        HorizontalDivider(color = C.line)
        Row(Modifier.fillMaxWidth().height(58.dp).padding(horizontal = 6.dp)) {
            for ((t, label, glyph) in listOf(
                Triple(Tab.MAP, "Map", Glyph.MAP),
                Triple(Tab.SITES, "Sites", Glyph.OUTLINE),
                Triple(Tab.FINDS, "Finds", Glyph.COIN),
                Triple(Tab.COMMUNITY, "Community", Glyph.CHAT),
                Triple(Tab.OLD, "Old maps", Glyph.BOOK),
            )) {
                val on = t == current
                val color = if (on) C.accent else C.muted
                Column(
                    Modifier.weight(1f).height(58.dp).clickable(onClickLabel = label) { onSelect(t) }.semantics { selected = on; contentDescription = label },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Box(Modifier.size(width = 46.dp, height = 28.dp).clip(RoundedCornerShape(50)).background(if (on) C.accentSoft else Color.Transparent), contentAlignment = Alignment.Center) {
                        GlyphIcon(glyph, color, Modifier.size(21.dp))
                    }
                    Text(label, fontSize = 10.5.sp, maxLines = 1, fontWeight = if (on) FontWeight.ExtraBold else FontWeight.SemiBold, color = color)
                }
            }
        }
    }
}

/** Shown on every tab but the map while a visit is on, so it's never forgotten. */
@Composable
private fun RecordingBar(nav: Nav, modifier: Modifier) {
    val ctx = LocalContext.current
    val data by Repo.data.collectAsState()
    val fix by LocationHub.fix.collectAsState()
    val visit = data.visits.lastOrNull { it.endedAt == null }
    val since = visit?.startedAt ?: data.recording?.let { r -> data.tracks.firstOrNull { it.id == r }?.startedAt } ?: return
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(since) {
        while (true) { now = System.currentTimeMillis(); kotlinx.coroutines.delay(1000) }
    }
    val site = visit?.let { v -> data.sites.firstOrNull { it.id == v.siteId } } ?: fix?.let { f -> Sites.siteAt(data.sites, f.geo) }
    Row(
        modifier.fillMaxWidth().shadow(10.dp, RoundedCornerShape(16.dp)).clip(RoundedCornerShape(16.dp)).background(C.ink)
            .clickable(onClickLabel = "Back to the map") { nav.tab(Tab.MAP) }.padding(start = 14.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(Color(0xFFE5484D)))
        Text(
            (if (visit != null) "Visit ${clock(now - since)}" else "Recording ${clock(now - since)}") + (site?.let { " · ${it.name}" } ?: ""),
            Modifier.weight(1f), color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        Text(
            if (visit != null) "End visit" else "Stop",
            Modifier.clip(RoundedCornerShape(50)).background(Color(0x26FFFFFF)).clickable(onClickLabel = "End visit") { com.brandonthomas.oldground.location.VisitControl.end(ctx) }
                .padding(horizontal = 14.dp, vertical = 7.dp),
            color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.ExtraBold,
        )
    }
}

/** 2:05, then 1:02:05 past an hour. */
fun clock(ms: Long): String {
    val secs = (ms / 1000).coerceAtLeast(0)
    val h = secs / 3600
    val m = (secs % 3600) / 60
    val s = "%02d".format(secs % 60)
    return if (h > 0) "$h:${"%02d".format(m)}:$s" else "$m:$s"
}
