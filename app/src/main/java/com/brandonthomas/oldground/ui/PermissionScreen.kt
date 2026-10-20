package com.brandonthomas.oldground.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.brandonthomas.oldground.Nav
import com.brandonthomas.oldground.data.CONTACT_KINDS
import com.brandonthomas.oldground.data.ContactNote
import com.brandonthomas.oldground.data.PERMISSION_CONDITIONS
import com.brandonthomas.oldground.data.Permission
import com.brandonthomas.oldground.data.Permissions
import com.brandonthomas.oldground.data.Pin
import com.brandonthomas.oldground.data.Repo
import com.brandonthomas.oldground.data.Site
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val BODY = Color(0xFF2A3733)
private val AMBER = Color(0xFFB45309)
private val AMBER_SOFT = Color(0xFFFEF3E2)

// ======================= on the site's page =======================

/** The landowner card on a site's page: the slip, their contact details, a contact log and a finds report. */
@Composable
fun PermissionCard(nav: Nav, site: Site, finds: List<Pin>, metric: Boolean, modifier: Modifier = Modifier.fillMaxWidth().card(16, 2)) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val st = Permissions.status(site)
    val p = site.permission
    var logging by remember { mutableStateOf(false) }
    var showAll by remember { mutableStateOf(false) }
    Column(modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Permission", Modifier.weight(1f), fontSize = 15.sp, fontWeight = FontWeight.ExtraBold, color = C.ink)
            val (bg, fg) = when (st.level) {
                Permissions.Level.OK -> C.accentSoft to C.accent
                Permissions.Level.SOON -> AMBER_SOFT to AMBER
                Permissions.Level.EXPIRED -> C.badSoft to C.badInk
                Permissions.Level.NONE -> C.soft to C.muted
            }
            Text(
                when (st.level) { Permissions.Level.OK -> "Signed"; Permissions.Level.SOON -> "Expiring"; Permissions.Level.EXPIRED -> "Expired"; else -> "Verbal" },
                Modifier.clip(RoundedCornerShape(50)).background(bg).padding(horizontal = 10.dp, vertical = 4.dp),
                fontSize = 12.sp, fontWeight = FontWeight.ExtraBold, color = fg,
            )
        }
        Text(st.text, fontSize = 13.5.sp, color = if (st.level == Permissions.Level.SOON) AMBER else if (st.level == Permissions.Level.EXPIRED) C.badInk else C.muted)
        if (p == null) {
            Text("Have the owner sign a simple permission slip on your phone. You both get a PDF copy.", fontSize = 13.5.sp, color = BODY, lineHeight = 19.sp)
            Pill("Get it in writing", { nav.go(com.brandonthomas.oldground.Screen.PermissionSlip(site.id)) }, Modifier.fillMaxWidth(), kind = "primary")
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill("View slip", { Permissions.view(ctx, Permissions.file(ctx, p.pdf)) }, Modifier.weight(1f), kind = "soft", small = true)
                Pill("Send a copy", { Permissions.share(ctx, Permissions.file(ctx, p.pdf), "Permission: ${site.name}", p.ownerEmail) }, Modifier.weight(1f), kind = "soft", small = true)
                Pill("Renew", { nav.go(com.brandonthomas.oldground.Screen.PermissionSlip(site.id)) }, Modifier.weight(1f), kind = if (st.level == Permissions.Level.OK) "soft" else "primary", small = true)
            }
            // The landowner.
            Divider()
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(p.ownerName, fontSize = 14.5.sp, fontWeight = FontWeight.Bold, color = C.ink)
                    Text(listOf(p.ownerPhone, p.ownerEmail).filter { it.isNotBlank() }.joinToString(" · ").ifBlank { "No phone or email saved" }, fontSize = 12.5.sp, color = C.muted)
                }
                if (p.ownerPhone.isNotBlank()) {
                    Pill("Call", { runCatching { ctx.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:${p.ownerPhone}"))) } }, kind = "tint", small = true)
                    Pill("Text", { runCatching { ctx.startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:${p.ownerPhone}"))) } }, kind = "tint", small = true)
                }
            }
        }
        // Contact log.
        Divider()
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Contact log", Modifier.weight(1f), fontSize = 13.5.sp, fontWeight = FontWeight.Bold, color = C.ink)
            Text("+ Log", Modifier.clip(RoundedCornerShape(50)).clickable { logging = true }.padding(horizontal = 8.dp, vertical = 4.dp), fontSize = 13.sp, fontWeight = FontWeight.Bold, color = C.accent)
        }
        val log = site.contacts.sortedByDescending { it.at }
        if (log.isEmpty()) Text("Note each call, text or chat with the landowner, so you remember what was said.", fontSize = 12.5.sp, color = C.muted)
        for (c in if (showAll) log else log.take(3)) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(java.text.SimpleDateFormat("MMM d", java.util.Locale.getDefault()).format(java.util.Date(c.at)), Modifier.width(56.dp), fontSize = 12.5.sp, color = C.muted, maxLines = 1)
            Text(c.how + if (c.note.isNotBlank()) " · ${c.note}" else "", Modifier.weight(1f), fontSize = 13.sp, color = BODY)
        }
        if (log.size > 3 && !showAll) Text("Show all ${log.size}", Modifier.clickable { showAll = true }, fontSize = 12.5.sp, fontWeight = FontWeight.Bold, color = C.accent)
        // Finds report.
        if (finds.isNotEmpty()) {
            Divider()
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(Modifier.weight(1f)) {
                    Text("Finds report", fontSize = 13.5.sp, fontWeight = FontWeight.Bold, color = C.ink)
                    Text("Pick the finds, check the PDF, then send it to the owner.", fontSize = 12.5.sp, color = C.muted)
                }
                Pill("Preview", { nav.go(com.brandonthomas.oldground.Screen.Report(site.id)) }, kind = "tint", small = true)
            }
        }
    }
    if (logging) ContactDialog(onCancel = { logging = false }) { how, note ->
        Repo.updateSite(site.id) { it.copy(contacts = it.contacts + ContactNote(System.currentTimeMillis(), how, note.trim())) }
        logging = false
    }
}

@Composable
private fun ContactDialog(onCancel: () -> Unit, onSave: (String, String) -> Unit) {
    var how by remember { mutableStateOf(CONTACT_KINDS.first()) }
    var note by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Log a contact") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (k in CONTACT_KINDS.take(4)) Chip(k, how == k) { how = k }
                }
                OutlinedTextField(note, { note = it.take(300) }, placeholder = { Text("What was said (optional)") }, minLines = 2)
            }
        },
        confirmButton = { TextButton(onClick = { onSave(how, note) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
        containerColor = C.panel,
    )
}

@Composable
private fun Chip(label: String, on: Boolean, onClick: () -> Unit) {
    Text(
        label,
        Modifier.clip(RoundedCornerShape(50)).background(if (on) C.accent else C.soft).clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 6.dp),
        fontSize = 12.5.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.SemiBold, color = if (on) Color.White else C.ink, maxLines = 1,
    )
}

// ======================= filling in and signing =======================

private enum class Step { FORM, SIGN, DONE }

/** Fill in the slip, hand the phone to the owner to sign, then send them a copy. */
@Composable
fun PermissionSlipScreen(nav: Nav, siteId: String, debug: String? = null) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val data by Repo.data.collectAsState()
    val site = data.sites.firstOrNull { it.id == siteId } ?: run { LaunchedEffect(Unit) { nav.back() }; return }
    val old = site.permission
    var step by rememberSaveable { mutableStateOf(when (debug) { "sign" -> Step.SIGN; else -> Step.FORM }) }
    var owner by rememberSaveable { mutableStateOf(old?.ownerName ?: if (debug != null) "John Miller" else "") }
    var phone by rememberSaveable { mutableStateOf(old?.ownerPhone ?: "") }
    var email by rememberSaveable { mutableStateOf(old?.ownerEmail ?: "") }
    var hunter by rememberSaveable { mutableStateOf(old?.hunterName ?: data.settings.myName) }
    var term by rememberSaveable { mutableIntStateOf(12) } // months; 0 = until withdrawn
    val picked = remember { mutableStateListOf<String>().apply { addAll(old?.conditions ?: PERMISSION_CONDITIONS.take(2)) } }
    var extra by rememberSaveable { mutableStateOf(old?.extra ?: "") }
    val strokes = remember { mutableStateListOf<List<Offset>>() }
    var padSize by remember { mutableStateOf(IntSize.Zero) }
    var saving by remember { mutableStateOf(false) }
    val startsAt = remember { System.currentTimeMillis() }
    val expiresAt = remember(term) { if (term == 0) null else Permissions.monthsFromNow(term) }

    Column(Modifier.fillMaxSize().background(C.bg).imePadding()) {
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            RoundButton({ if (step == Step.SIGN) step = Step.FORM else nav.back() }, "Back", size = 38.dp) { GlyphIcon(Glyph.BACK, C.ink, Modifier.size(20.dp)) }
            Text(when (step) { Step.FORM -> "Permission slip"; Step.SIGN -> "Read and sign"; Step.DONE -> "Signed" }, Modifier.weight(1f), fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, color = C.ink)
        }
        when (step) {
            Step.FORM -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(site.name, fontSize = 14.sp, color = C.muted)
                SlipField("Landowner's name", owner, { owner = it.take(80) }, "e.g. John Miller")
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.weight(1f)) { SlipField("Their phone", phone, { phone = it.take(30) }, "Optional", KeyboardType.Phone) }
                    Box(Modifier.weight(1f)) { SlipField("Their email", email, { email = it.trim().take(80) }, "Optional", KeyboardType.Email) }
                }
                SlipField("Your name", hunter, { hunter = it.take(80) }, "As it should appear on the slip")
                Text("Good for", fontSize = 12.5.sp, fontWeight = FontWeight.Bold, color = C.muted)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for ((m, label) in listOf(6 to "6 months", 12 to "1 year", 0 to "Until they say")) Chip(label, term == m) { term = m }
                }
                Text("Conditions", fontSize = 12.5.sp, fontWeight = FontWeight.Bold, color = C.muted)
                Column(Modifier.fillMaxWidth().card(14, 2)) {
                    for ((i, c) in PERMISSION_CONDITIONS.withIndex()) {
                        if (i > 0) Divider()
                        val on = c in picked
                        Row(Modifier.fillMaxWidth().clickable { if (on) picked.remove(c) else picked.add(c) }.padding(horizontal = 12.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Box(Modifier.size(22.dp).clip(RoundedCornerShape(6.dp)).background(if (on) C.accent else C.panel).border(1.5.dp, if (on) C.accent else C.line, RoundedCornerShape(6.dp)), contentAlignment = Alignment.Center) {
                                if (on) Text("✓", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.ExtraBold)
                            }
                            Text(c, Modifier.weight(1f), fontSize = 14.sp, color = C.ink, lineHeight = 19.sp)
                        }
                    }
                }
                SlipField("Anything else", extra, { extra = it.take(400) }, "e.g. Park by the barn, not the house", lines = 2)
                Text("This is a plain-language permission slip, not a contract. Laws on detecting and trespass vary by state, and the owner can withdraw permission at any time.", fontSize = 12.sp, color = C.muted, lineHeight = 17.sp)
                Pill("Hand the phone to ${owner.ifBlank { "the owner" }}", { strokes.clear(); step = Step.SIGN }, Modifier.fillMaxWidth(), kind = "primary", enabled = owner.isNotBlank() && hunter.isNotBlank())
                Spacer(Modifier.height(40.dp))
            }

            Step.SIGN -> Column(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(Modifier.weight(1f).fillMaxWidth().card(14, 2).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Permission to search", fontSize = 19.sp, fontWeight = FontWeight.ExtraBold, color = C.ink)
                    for (para in Permissions.wording(site.name, owner, hunter, startsAt, expiresAt, picked.toList(), extra)) Text(para, fontSize = 16.sp, color = BODY, lineHeight = 23.sp)
                }
                Text("${owner.ifBlank { "Owner" }}, sign with your finger:", fontSize = 13.5.sp, fontWeight = FontWeight.Bold, color = C.ink)
                Box(
                    Modifier.fillMaxWidth().height(150.dp).clip(RoundedCornerShape(14.dp)).background(Color.White).border(1.5.dp, C.line, RoundedCornerShape(14.dp))
                        .onSizeChanged { padSize = it }
                        .pointerInput(Unit) {
                            var cur = mutableListOf<Offset>()
                            detectDragGestures(
                                onDragStart = { o -> cur = mutableListOf(o); strokes.add(cur.toList()) },
                                onDrag = { ch, _ -> cur.add(ch.position); strokes[strokes.lastIndex] = cur.toList() },
                            )
                        },
                ) {
                    Canvas(Modifier.fillMaxSize()) {
                        drawLine(C.line, Offset(20f, size.height - 34f), Offset(size.width - 20f, size.height - 34f), 2f)
                        for (s in strokes) if (s.size > 1) drawPath(
                            Path().apply { moveTo(s[0].x, s[0].y); for (q in s.drop(1)) lineTo(q.x, q.y) },
                            C.ink, style = Stroke(5f, cap = StrokeCap.Round, join = StrokeJoin.Round),
                        )
                    }
                    if (strokes.isEmpty()) Text("Sign here", Modifier.align(Alignment.Center), color = C.faint, fontSize = 15.sp)
                }
                Row(Modifier.navigationBarsPadding().padding(bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Pill("Clear", { strokes.clear() }, Modifier.weight(1f), kind = "soft", enabled = strokes.isNotEmpty())
                    Pill(if (saving) "Saving…" else "Sign", {
                        if (saving) return@Pill
                        saving = true
                        scope.launch {
                            val id = Repo.newId()
                            val sig = "sig-$id.png"; val pdf = "permission-$id.pdf"
                            val perm = Permission(owner.trim(), phone.trim(), email.trim(), hunter.trim(), startsAt, expiresAt, picked.toList(), extra.trim(), System.currentTimeMillis(), sig, pdf)
                            withContext(Dispatchers.IO) {
                                Permissions.saveSignature(ctx, renderSignature(strokes.toList(), padSize), sig)
                                Permissions.slipPdf(ctx, site, perm, data.settings.metric)
                            }
                            // Replace any earlier slip (its files go too).
                            old?.let { o -> Permissions.file(ctx, o.signature).delete(); Permissions.file(ctx, o.pdf).delete() }
                            Repo.updateSite(site.id) { it.copy(permission = perm, access = "Permission") }
                            if (data.settings.myName.isBlank()) Repo.updateSettings { it.copy(myName = hunter.trim()) }
                            saving = false
                            step = Step.DONE
                        }
                    }, Modifier.weight(1.6f), kind = "primary", enabled = strokes.sumOf { it.size } > 8 && !saving)
                }
            }

            Step.DONE -> Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Spacer(Modifier.height(30.dp))
                Box(Modifier.size(64.dp).clip(CircleShape).background(C.accentSoft), contentAlignment = Alignment.Center) { Text("✓", fontSize = 30.sp, color = C.accent, fontWeight = FontWeight.ExtraBold) }
                Text("Signed and saved", fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, color = C.ink)
                Text("The slip is saved with ${site.name} on this phone. Send ${owner.ifBlank { "the owner" }} a copy, and one to yourself as a backup.", fontSize = 14.5.sp, color = C.muted, lineHeight = 21.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                val p = site.permission
                Pill("Send a copy", { p?.let { Permissions.share(ctx, Permissions.file(ctx, it.pdf), "Permission: ${site.name}", it.ownerEmail) } }, Modifier.fillMaxWidth(), kind = "primary")
                Pill("Done", { nav.back() }, Modifier.fillMaxWidth(), kind = "soft")
            }
        }
    }
}

/** The finger strokes as a white-backed image, trimmed to the signature. */
private fun renderSignature(strokes: List<List<Offset>>, size: IntSize): android.graphics.Bitmap {
    val pts = strokes.flatten()
    val minX = (pts.minOfOrNull { it.x } ?: 0f) - 12f; val maxX = (pts.maxOfOrNull { it.x } ?: size.width.toFloat()) + 12f
    val minY = (pts.minOfOrNull { it.y } ?: 0f) - 12f; val maxY = (pts.maxOfOrNull { it.y } ?: size.height.toFloat()) + 12f
    val w = (maxX - minX).toInt().coerceIn(40, 2000); val h = (maxY - minY).toInt().coerceIn(20, 1000)
    val bmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
    val c = android.graphics.Canvas(bmp)
    c.drawColor(android.graphics.Color.WHITE)
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.rgb(20, 32, 29); style = android.graphics.Paint.Style.STROKE; strokeWidth = 5f
        strokeCap = android.graphics.Paint.Cap.ROUND; strokeJoin = android.graphics.Paint.Join.ROUND
    }
    for (s in strokes) if (s.size > 1) {
        val path = android.graphics.Path().apply { moveTo(s[0].x - minX, s[0].y - minY); for (q in s.drop(1)) lineTo(q.x - minX, q.y - minY) }
        c.drawPath(path, paint)
    }
    return bmp
}

@Composable
private fun SlipField(label: String, value: String, onChange: (String) -> Unit, placeholder: String, keyboard: KeyboardType = KeyboardType.Text, lines: Int = 1) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, fontSize = 12.5.sp, fontWeight = FontWeight.Bold, color = C.muted)
        OutlinedTextField(
            value, onChange, Modifier.fillMaxWidth(),
            placeholder = { Text(placeholder, color = C.faint, fontSize = 14.5.sp) },
            singleLine = lines == 1, minLines = lines, shape = RoundedCornerShape(12.dp),
            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 15.sp, fontFamily = Jakarta, color = C.ink),
            keyboardOptions = KeyboardOptions(keyboardType = keyboard),
            colors = OutlinedTextFieldDefaults.colors(unfocusedBorderColor = C.line, focusedBorderColor = C.accent, unfocusedContainerColor = C.panel, focusedContainerColor = C.panel),
        )
    }
}
