package com.brandonthomas.oldground.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.brandonthomas.oldground.Nav
import com.brandonthomas.oldground.Screen
import com.brandonthomas.oldground.data.Help
import com.brandonthomas.oldground.data.HelpBlock

private val BODY = Color(0xFF2A3733)

/** **bold** → bold spans. */
private fun rich(t: String): AnnotatedString = buildAnnotatedString {
    val parts = t.split("**")
    for ((i, part) in parts.withIndex()) {
        if (i % 2 == 1) withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = C.ink)) { append(part) } else append(part)
    }
}

@Composable
private fun HelpHeader(nav: Nav, title: String) {
    Row(
        Modifier.fillMaxWidth().statusBarsPadding().padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        RoundButton({ nav.back() }, "Back", size = 38.dp) { GlyphIcon(Glyph.BACK, C.ink, Modifier.size(20.dp)) }
        Text(title, Modifier.weight(1f), fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, color = C.ink, maxLines = 1)
    }
}

/** Settings › Help: the guide's topics. */
@Composable
fun HelpScreen(nav: Nav) {
    val ctx = LocalContext.current
    val doc by Help.doc.collectAsState()
    LaunchedEffect(Unit) { Help.load(ctx) }
    Column(Modifier.fillMaxSize().background(C.bg)) {
        HelpHeader(nav, "Help")
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("How to use OldGround, from lining up your first old map to sharing a find.", fontSize = 14.sp, color = C.muted, lineHeight = 20.sp)
            for ((i, t) in doc.topics.withIndex()) {
                Row(
                    Modifier.fillMaxWidth().card(14, 2).clickable { nav.go(Screen.HelpTopic(t.id)) }.padding(14.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Box(Modifier.size(26.dp).clip(CircleShape).background(C.accentSoft), contentAlignment = Alignment.Center) {
                        Text("${i + 1}", fontSize = 13.sp, fontWeight = FontWeight.ExtraBold, color = C.accent)
                    }
                    Column(Modifier.weight(1f)) {
                        Text(t.title, fontSize = 15.5.sp, fontWeight = FontWeight.Bold, color = C.ink)
                        Text(rich(t.summary), fontSize = 13.sp, color = C.muted, lineHeight = 18.sp)
                    }
                    GlyphIcon(Glyph.CHEVRON, C.faint, Modifier.size(18.dp).align(Alignment.CenterVertically))
                }
            }
            Text("Still stuck? Email support@oldgroundapp.com", Modifier.padding(vertical = 8.dp), fontSize = 13.sp, color = C.muted)
            Spacer(Modifier.height(60.dp))
        }
    }
}

/** One topic of the guide. */
@Composable
fun HelpTopicScreen(nav: Nav, id: String) {
    val ctx = LocalContext.current
    val doc by Help.doc.collectAsState()
    LaunchedEffect(Unit) { if (doc.topics.isEmpty()) Help.load(ctx) }
    val i = doc.topics.indexOfFirst { it.id == id }
    val t = doc.topics.getOrNull(i)
    Column(Modifier.fillMaxSize().background(C.bg)) {
        HelpHeader(nav, t?.title ?: "Help")
        if (t == null) return@Column
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            Column(Modifier.fillMaxWidth().card(16, 2).padding(start = 18.dp, end = 18.dp, top = 16.dp, bottom = 18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(rich(t.summary), fontSize = 15.sp, color = C.muted, lineHeight = 21.sp)
                for (b in t.blocks) Block(b)
            }
            Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                doc.topics.getOrNull(i - 1)?.let { p -> Text("‹ ${p.title}", Modifier.clickable { nav.replace(Screen.HelpTopic(p.id)) }.padding(4.dp), color = C.accent, fontWeight = FontWeight.Bold, fontSize = 14.sp) } ?: Spacer(Modifier)
                doc.topics.getOrNull(i + 1)?.let { n -> Text("${n.title} ›", Modifier.clickable { nav.replace(Screen.HelpTopic(n.id)) }.padding(4.dp), color = C.accent, fontWeight = FontWeight.Bold, fontSize = 14.sp) }
            }
            Spacer(Modifier.height(60.dp))
        }
    }
}

@Composable
private fun Block(b: HelpBlock) {
    when {
        b.h != null -> Text(rich(b.h), Modifier.padding(top = 8.dp), fontSize = 17.sp, fontWeight = FontWeight.ExtraBold, color = C.ink)
        b.p != null -> Text(rich(b.p), fontSize = 15.sp, color = BODY, lineHeight = 22.sp)
        b.steps != null -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for ((n, s) in b.steps.withIndex()) Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.size(22.dp).clip(CircleShape).background(C.accent), contentAlignment = Alignment.Center) {
                    Text("${n + 1}", fontSize = 12.sp, fontWeight = FontWeight.ExtraBold, color = Color.White)
                }
                Text(rich(s), Modifier.weight(1f), fontSize = 15.sp, color = BODY, lineHeight = 22.sp)
            }
        }
        b.list != null -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            for (s in b.list) Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.padding(top = 9.dp).size(6.dp).clip(CircleShape).background(C.accent))
                Text(rich(s), Modifier.weight(1f), fontSize = 15.sp, color = BODY, lineHeight = 22.sp)
            }
        }
        b.tip != null -> Text(
            rich("**Tip** " + b.tip),
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(C.accentSoft).padding(12.dp),
            fontSize = 14.5.sp, color = Color(0xFF0B4F4A), lineHeight = 21.sp,
        )
    }
}
