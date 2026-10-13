package com.brandonthomas.oldground.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.brandonthomas.oldground.Nav
import com.brandonthomas.oldground.data.Repo
import com.brandonthomas.oldground.map.OfflineAreas
import com.brandonthomas.oldground.Screen
import com.brandonthomas.oldground.community.Community
import kotlinx.coroutines.launch

/** Everything you set once: units, detector, screen, public land, offline areas, export. */
@Composable
fun SettingsScreen(nav: Nav) {
    val ctx = LocalContext.current
    val data by Repo.data.collectAsState()
    val s = data.settings
    var areas by remember { mutableStateOf<List<OfflineAreas.Area>>(emptyList()) }
    var areasTick by remember { mutableIntStateOf(0) }
    var editDetector by remember { mutableStateOf(false) }
    var editName by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<Pair<String, String>?>(null) }
    var deleting by remember { mutableStateOf<OfflineAreas.Area?>(null) }
    var deletingAccount by remember { mutableStateOf(false) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    LaunchedEffect(areasTick) { OfflineAreas.list(ctx) { areas = it } }

    Column(Modifier.fillMaxSize().background(C.bg)) {
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            RoundButton({ nav.back() }, "Back", size = 38.dp) { GlyphIcon(Glyph.BACK, C.ink, Modifier.size(20.dp)) }
            Text("Settings", fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, color = C.ink)
        }
        ScrollColumn(Modifier.padding(horizontal = 16.dp)) {
            Group("Help") {
                Item("How to use OldGround", "A guide to every feature", onClick = { nav.go(Screen.Help) }) {
                    GlyphIcon(Glyph.CHEVRON, C.faint, Modifier.size(18.dp))
                }
            }
            Group("In the field") {
                Item("Units") {
                    Segmented(s.metric, listOf(false to "Feet", true to "Meters"), { v -> Repo.updateSettings { it.copy(metric = v) } }, Modifier.width(170.dp))
                }
                Line()
                Item("Your name", s.myName.ifBlank { "For permission slips" }, onClick = { editName = true }) {
                    Text("Change", color = C.accent, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
                Line()
                Item("Detector", s.lastDetector.ifBlank { "Filled in on each new find" }, onClick = { editDetector = true }) {
                    Text("Change", color = C.accent, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
                Line()
                Item("Record my path on visits", "Draws where you've walked on the site's map") {
                    Toggle(s.recordPath) { v -> Repo.updateSettings { it.copy(recordPath = v) } }
                }
                Line()
                Item("Keep screen on", "While the map is showing") { Toggle(s.keepScreenOn) { v -> Repo.updateSettings { it.copy(keepScreenOn = v) } } }
            }
            Group("Maps") {
                Item("Public land", "Federal, state, county and city land from USGS") {
                    Toggle(s.publicLand) { v -> Repo.updateSettings { it.copy(publicLand = v) } }
                }
                Line()
                Item(
                    "Offline areas",
                    if (areas.isEmpty()) "None yet. On the map: Layers › Save this area for offline" else "${areas.size} saved on this device",
                )
                for (a in areas) {
                    Line()
                    Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 8.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(a.name, Modifier.weight(1f), color = C.ink, fontSize = 14.sp, maxLines = 1)
                        Pill("Delete", { deleting = a }, kind = "soft", small = true)
                    }
                }
            }
            Group("Community") {
                val me by Community.me.collectAsState()
                if (me == null) {
                    Item("Join the community", "Post finds, upvote and comment", onClick = { nav.go(Screen.JoinPage()) }) {
                        GlyphIcon(Glyph.CHEVRON, C.faint, Modifier.size(18.dp))
                    }
                } else {
                    Item("Signed in as ${me!!.username}")
                    Line()
                    Item("Sign out", onClick = { Community.signOut() })
                    Line()
                    Item("Delete my community account", "Removes your posts and comments", onClick = { deletingAccount = true })
                }
            }
            Group("Your data") {
                Item("Export pins and tracks", "A GPX file for other GPS units and apps", onClick = { message = exportGpx(ctx) }) {
                    GlyphIcon(Glyph.CHEVRON, C.faint, Modifier.size(18.dp))
                }
            }
            Text(
                "OldGround version ${com.brandonthomas.oldground.BuildConfig.VERSION_NAME}",
                color = C.muted, fontSize = 12.sp, modifier = Modifier.fillMaxWidth().padding(top = 16.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            Spacer(Modifier.height(80.dp))
        }
    }

    if (editName) NameDialog("Your name", s.myName, "As it should appear on permission slips", onCancel = { editName = false }) { v ->
        Repo.updateSettings { it.copy(myName = v.trim()) }
        editName = false
    }
    if (editDetector) NameDialog("Your detector", s.lastDetector, "e.g. XP Deus II · Program 4 · Sens 92", onCancel = { editDetector = false }) { v ->
        Repo.updateSettings { it.copy(lastDetector = v.trim()) }
        editDetector = false
    }
    deleting?.let { a ->
        ConfirmDialog("Delete this offline area?", "\"${a.name}\" will no longer work without signal.", "Delete", { deleting = null }) {
            OfflineAreas.delete(ctx, a.name) { areasTick++ }
            deleting = null
        }
    }
    if (deletingAccount) ConfirmDialog("Delete your community account?", "Your name, posts, photos and comments are removed for good. Your maps, sites and finds on this phone stay.", "Delete", { deletingAccount = false }) {
        deletingAccount = false
        scope.launch { runCatching { Community.deleteAccount() }.onFailure { message = "Couldn't delete it" to (it.message ?: "") } }
    }
    message?.let { (t, b) -> MessageDialog(t, b) { message = null } }
}

@Composable
private fun Group(title: String, content: @Composable () -> Unit) {
    Text(title.uppercase(), fontSize = 12.sp, fontWeight = FontWeight.ExtraBold, color = C.muted, letterSpacing = 0.6.sp, modifier = Modifier.padding(start = 4.dp, top = 14.dp, bottom = 6.dp))
    Column(Modifier.fillMaxWidth().card(16, 2)) { content() }
}

@Composable
private fun Item(title: String, sub: String? = null, onClick: (() -> Unit)? = null, trailing: @Composable () -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = C.ink)
            if (sub != null) Text(sub, fontSize = 12.5.sp, color = C.muted)
        }
        trailing()
    }
}

@Composable
private fun Line() = HorizontalDivider(color = Color(0xFFEEF1F0))

@Composable
private fun Toggle(on: Boolean, onChange: (Boolean) -> Unit) {
    Box(
        Modifier.size(width = 46.dp, height = 28.dp).clip(RoundedCornerShape(50)).background(if (on) C.accent else C.line)
            .clickable(role = Role.Switch, onClickLabel = if (on) "Turn off" else "Turn on") { onChange(!on) }.padding(3.dp),
        contentAlignment = if (on) Alignment.CenterEnd else Alignment.CenterStart,
    ) { Box(Modifier.size(22.dp).clip(CircleShape).background(Color.White)) }
}
