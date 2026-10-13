package com.brandonthomas.oldground.ui

import android.view.ViewGroup
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.brandonthomas.oldground.map.OgMap

// ---------- maps in Compose ----------

/** A map that follows the screen's lifecycle and is cleaned up when it leaves the screen. */
@Composable
fun rememberOgMap(key: Any, texture: Boolean = false, interactive: Boolean = true): OgMap {
    val ctx = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val m = remember(key) { OgMap(ctx, texture, interactive) }
    DisposableEffect(m, lifecycle) {
        var started = false
        var resumed = false
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_START -> if (!started) { m.onStart(); started = true }
                Lifecycle.Event.ON_RESUME -> if (!resumed) { m.onResume(); resumed = true }
                Lifecycle.Event.ON_PAUSE -> if (resumed) { m.onPause(); resumed = false }
                Lifecycle.Event.ON_STOP -> if (started) { m.onStop(); started = false }
                else -> {}
            }
        }
        lifecycle.addObserver(obs) // also brings the map up to the current state
        onDispose {
            lifecycle.removeObserver(obs)
            if (resumed) m.onPause()
            if (started) m.onStop()
            m.onDestroy()
        }
    }
    return m
}

@Composable
fun MapBox(m: OgMap, modifier: Modifier = Modifier) {
    AndroidView(
        factory = { (m.view.parent as? ViewGroup)?.removeView(m.view); m.view },
        modifier = modifier,
    )
}

// ---------- controls ----------

/** A white card with a soft shadow (the "Clean Trail" surface). */
fun Modifier.card(radius: Int = 16, elevation: Int = 3): Modifier =
    this.shadow(elevation.dp, RoundedCornerShape(radius.dp), ambientColor = Color(0x3314201D), spotColor = Color(0x3314201D))
        .clip(RoundedCornerShape(radius.dp))
        .background(C.panel)

/**
 * A rounded button. Kinds: primary (teal), danger, plain (white card), soft (light grey,
 * for use inside white panels), dark (white, floating over a map), green (= primary).
 */
@Composable
fun Pill(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, kind: String = "plain", enabled: Boolean = true, small: Boolean = false) {
    val (bg, fg) = when (kind) {
        "primary", "green" -> C.accent to Color.White
        "danger" -> C.bad to Color.White
        "soft" -> C.soft to C.ink
        "tint" -> C.accentSoft to C.accent
        else -> C.panel to C.ink
    }
    val shape = RoundedCornerShape(50)
    Box(
        modifier
            .then(if (kind == "plain" || kind == "dark") Modifier.shadow(3.dp, shape, ambientColor = Color(0x3314201D), spotColor = Color(0x3314201D)) else Modifier)
            .clip(shape)
            // Dimmed with the colour, not a layer: an alpha layer here sometimes drew the button blank.
            .background(if (enabled) bg else bg.copy(alpha = bg.alpha * 0.45f))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = if (small) 12.dp else 16.dp, vertical = if (small) 9.dp else 11.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (enabled) fg else fg.copy(alpha = 0.7f), fontSize = if (small) 13.sp else 15.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, maxLines = 1)
    }
}

@Composable
fun <T> Segmented(value: T, options: List<Pair<T, String>>, onChange: (T) -> Unit, modifier: Modifier = Modifier, dark: Boolean = false) {
    val shape = RoundedCornerShape(50)
    Row(
        modifier
            .then(if (dark) Modifier.shadow(4.dp, shape, ambientColor = Color(0x4014201D), spotColor = Color(0x4014201D)) else Modifier)
            .clip(shape)
            .background(if (dark) C.panel else C.soft)
            .padding(4.dp),
    ) {
        for ((v, label) in options) {
            val on = v == value
            Box(
                Modifier
                    .weight(1f)
                    .clip(shape)
                    .background(if (on) C.accent else Color.Transparent)
                    .clickable { onChange(v) }
                    .padding(vertical = 9.dp, horizontal = 2.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(label, color = if (on) Color.White else C.muted, fontWeight = FontWeight.Bold, fontSize = 13.sp, maxLines = 1, letterSpacing = (-0.2).sp, softWrap = false)
            }
        }
    }
}

/** A label on a map that opens a layer picker. */
@Composable
fun LayerTag(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(50)
    Box(
        modifier
            .shadow(3.dp, shape, ambientColor = Color(0x4014201D), spotColor = Color(0x4014201D))
            .clip(shape)
            .background(C.panel)
            .clickable(onClick = onClick)
            .padding(horizontal = 11.dp, vertical = 6.dp),
    ) { Text("$text ▾", color = C.ink, fontWeight = FontWeight.Bold, fontSize = 12.5.sp, maxLines = 1) }
}

/** A round white button floating over a map. */
@Composable
fun RoundButton(onClick: () -> Unit, label: String, modifier: Modifier = Modifier, size: androidx.compose.ui.unit.Dp = 42.dp, content: @Composable () -> Unit) {
    Box(
        modifier
            .size(size)
            .shadow(4.dp, CircleShape, ambientColor = Color(0x4014201D), spotColor = Color(0x4014201D))
            .clip(CircleShape)
            .background(C.panel)
            .clickable(onClickLabel = label, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) { content() }
}

/** Small line icons drawn in code (no icon library needed). */
enum class Glyph { BACK, LAYERS, PIN, RECORD, STOP, FOLLOW, MORE, CHEVRON, PLUS, LIST, CAMERA, IMAGE, CLOSE, MAP, OUTLINE, COIN, BOOK, SEARCH, GEAR, CHAT }

@Composable
fun GlyphIcon(g: Glyph, color: Color, modifier: Modifier = Modifier.size(22.dp)) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val sw = w * 0.08f
        val stroke = Stroke(sw, cap = StrokeCap.Round, join = StrokeJoin.Round)
        when (g) {
            Glyph.BACK -> drawPath(Path().apply { moveTo(w * 0.62f, h * 0.18f); lineTo(w * 0.32f, h * 0.5f); lineTo(w * 0.62f, h * 0.82f) }, color, style = stroke)
            Glyph.CHEVRON -> drawPath(Path().apply { moveTo(w * 0.38f, h * 0.2f); lineTo(w * 0.66f, h * 0.5f); lineTo(w * 0.38f, h * 0.8f) }, color, style = stroke)
            Glyph.LAYERS -> {
                drawPath(Path().apply { moveTo(w * 0.5f, h * 0.12f); lineTo(w * 0.92f, h * 0.33f); lineTo(w * 0.5f, h * 0.54f); lineTo(w * 0.08f, h * 0.33f); close() }, color, style = stroke)
                drawPath(Path().apply { moveTo(w * 0.08f, h * 0.5f); lineTo(w * 0.5f, h * 0.71f); lineTo(w * 0.92f, h * 0.5f) }, color, style = stroke)
                drawPath(Path().apply { moveTo(w * 0.08f, h * 0.67f); lineTo(w * 0.5f, h * 0.88f); lineTo(w * 0.92f, h * 0.67f) }, color, style = stroke)
            }
            Glyph.PIN -> {
                drawPath(Path().apply {
                    moveTo(w * 0.5f, h * 0.92f)
                    cubicTo(w * 0.5f, h * 0.92f, w * 0.8f, h * 0.58f, w * 0.8f, h * 0.38f)
                    cubicTo(w * 0.8f, h * 0.2f, w * 0.66f, h * 0.08f, w * 0.5f, h * 0.08f)
                    cubicTo(w * 0.34f, h * 0.08f, w * 0.2f, h * 0.2f, w * 0.2f, h * 0.38f)
                    cubicTo(w * 0.2f, h * 0.58f, w * 0.5f, h * 0.92f, w * 0.5f, h * 0.92f)
                    close()
                }, color, style = stroke)
                drawCircle(color, w * 0.1f, Offset(w * 0.5f, h * 0.37f))
            }
            Glyph.RECORD -> drawCircle(color, w * 0.27f, Offset(w / 2, h / 2))
            Glyph.STOP -> drawRoundRect(color, Offset(w * 0.28f, h * 0.28f), Size(w * 0.44f, h * 0.44f), CornerRadius(w * 0.06f))
            Glyph.FOLLOW -> drawPath(Path().apply { moveTo(w * 0.12f, h * 0.46f); lineTo(w * 0.88f, h * 0.12f); lineTo(w * 0.54f, h * 0.88f); lineTo(w * 0.46f, h * 0.54f); close() }, color)
            Glyph.MORE -> for (i in 0..2) drawCircle(color, w * 0.08f, Offset(w * (0.22f + i * 0.28f), h / 2))
            Glyph.PLUS -> {
                drawLine(color, Offset(w * 0.5f, h * 0.18f), Offset(w * 0.5f, h * 0.82f), sw, StrokeCap.Round)
                drawLine(color, Offset(w * 0.18f, h * 0.5f), Offset(w * 0.82f, h * 0.5f), sw, StrokeCap.Round)
            }
            Glyph.MAP -> drawPath(Path().apply {
                moveTo(w * 0.12f, h * 0.25f); lineTo(w * 0.37f, h * 0.15f); lineTo(w * 0.63f, h * 0.25f); lineTo(w * 0.88f, h * 0.15f)
                lineTo(w * 0.88f, h * 0.75f); lineTo(w * 0.63f, h * 0.85f); lineTo(w * 0.37f, h * 0.75f); lineTo(w * 0.12f, h * 0.85f); close()
                moveTo(w * 0.37f, h * 0.15f); lineTo(w * 0.37f, h * 0.75f); moveTo(w * 0.63f, h * 0.25f); lineTo(w * 0.63f, h * 0.85f)
            }, color, style = stroke)
            Glyph.OUTLINE -> {
                val pts = listOf(Offset(w * 0.17f, h * 0.29f), Offset(w * 0.67f, h * 0.17f), Offset(w * 0.83f, h * 0.63f), Offset(w * 0.29f, h * 0.83f))
                drawPath(Path().apply { moveTo(pts[0].x, pts[0].y); for (q in pts.drop(1)) lineTo(q.x, q.y); close() }, color, style = stroke)
                for (q in pts) drawCircle(color, w * 0.075f, q)
            }
            Glyph.COIN -> {
                drawCircle(color, w * 0.34f, Offset(w / 2, h / 2), style = stroke)
                drawCircle(color, w * 0.19f, Offset(w / 2, h / 2), style = Stroke(sw * 0.75f))
            }
            Glyph.BOOK -> {
                drawPath(Path().apply {
                    moveTo(w * 0.21f, h * 0.17f); lineTo(w * 0.79f, h * 0.17f); lineTo(w * 0.79f, h * 0.83f); lineTo(w * 0.29f, h * 0.83f)
                    quadraticTo(w * 0.21f, h * 0.83f, w * 0.21f, h * 0.75f); close()
                    moveTo(w * 0.21f, h * 0.73f); lineTo(w * 0.79f, h * 0.73f)
                    moveTo(w * 0.37f, h * 0.35f); lineTo(w * 0.63f, h * 0.35f); moveTo(w * 0.37f, h * 0.48f); lineTo(w * 0.55f, h * 0.48f)
                }, color, style = stroke)
            }
            Glyph.SEARCH -> {
                drawCircle(color, w * 0.25f, Offset(w * 0.44f, h * 0.44f), style = stroke)
                drawLine(color, Offset(w * 0.63f, h * 0.63f), Offset(w * 0.84f, h * 0.84f), sw * 1.1f, StrokeCap.Round)
            }
            Glyph.GEAR -> {
                // A cog: eight square teeth around a ring, and a hole in the middle.
                val cx = w / 2; val cy = h / 2
                val path = Path()
                for (k in 0 until 16) {
                    val outer = k % 2 == 0
                    val r0 = if (outer) w * 0.42f else w * 0.31f
                    val a0 = Math.toRadians(k * 22.5 - 8.0); val a1 = Math.toRadians(k * 22.5 + 8.0)
                    val x0 = cx + r0 * kotlin.math.cos(a0).toFloat(); val y0 = cy + r0 * kotlin.math.sin(a0).toFloat()
                    val x1 = cx + r0 * kotlin.math.cos(a1).toFloat(); val y1 = cy + r0 * kotlin.math.sin(a1).toFloat()
                    if (k == 0) path.moveTo(x0, y0) else path.lineTo(x0, y0)
                    path.lineTo(x1, y1)
                }
                path.close()
                drawPath(path, color, style = stroke)
                drawCircle(color, w * 0.13f, Offset(cx, cy), style = stroke)
            }
            Glyph.CLOSE -> {
                drawLine(color, Offset(w * 0.26f, h * 0.26f), Offset(w * 0.74f, h * 0.74f), sw, StrokeCap.Round)
                drawLine(color, Offset(w * 0.74f, h * 0.26f), Offset(w * 0.26f, h * 0.74f), sw, StrokeCap.Round)
            }
            Glyph.CAMERA -> {
                drawPath(Path().apply {
                    moveTo(w * 0.16f, h * 0.33f); lineTo(w * 0.3f, h * 0.33f); lineTo(w * 0.38f, h * 0.21f); lineTo(w * 0.62f, h * 0.21f)
                    lineTo(w * 0.7f, h * 0.33f); lineTo(w * 0.84f, h * 0.33f); lineTo(w * 0.84f, h * 0.79f); lineTo(w * 0.16f, h * 0.79f); close()
                }, color, style = stroke)
                drawCircle(color, w * 0.15f, Offset(w * 0.5f, h * 0.54f), style = stroke)
            }
            Glyph.IMAGE -> {
                drawRoundRect(color, Offset(w * 0.16f, h * 0.21f), Size(w * 0.68f, h * 0.58f), CornerRadius(w * 0.08f), style = stroke)
                drawPath(Path().apply { moveTo(w * 0.16f, h * 0.67f); lineTo(w * 0.37f, h * 0.46f); lineTo(w * 0.54f, h * 0.63f); lineTo(w * 0.67f, h * 0.5f); lineTo(w * 0.84f, h * 0.67f) }, color, style = stroke)
            }
            Glyph.CHAT -> drawPath(Path().apply {
                moveTo(w * 0.16f, h * 0.2f); lineTo(w * 0.84f, h * 0.2f); lineTo(w * 0.84f, h * 0.66f)
                lineTo(w * 0.4f, h * 0.66f); lineTo(w * 0.16f, h * 0.84f); close()
            }, color, style = stroke)
            Glyph.LIST -> for (i in 0..2) drawLine(color, Offset(w * 0.18f, h * (0.28f + i * 0.22f)), Offset(w * 0.82f, h * (0.28f + i * 0.22f)), sw, StrokeCap.Round)
        }
    }
}

/** A square-ish tool button with an icon over a label (hunt map panel). */
@Composable
fun ToolButton(label: String, glyph: Glyph, onClick: () -> Unit, modifier: Modifier = Modifier, kind: String = "soft") {
    val (bg, fg) = when (kind) {
        "primary" -> C.accent to Color.White
        "danger" -> C.badSoft to C.badInk
        else -> C.soft to C.ink
    }
    Column(
        modifier
            .height(64.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(bg)
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        GlyphIcon(glyph, if (kind == "danger") C.bad else fg)
        Spacer(Modifier.height(4.dp))
        Text(label, color = fg, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

/** A slim slider: thin track, small white thumb with a teal ring. */
@Composable
fun SlimSlider(value: Float, onChange: (Float) -> Unit, onDone: () -> Unit, modifier: Modifier = Modifier) {
    var width by remember { mutableStateOf(1f) }
    Canvas(
        modifier
            .height(28.dp)
            .onSizeChanged { width = it.width.toFloat().coerceAtLeast(1f) }
            .pointerInput(Unit) {
                detectTapGestures { o -> onChange((o.x / width).coerceIn(0f, 1f)); onDone() }
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(onDragEnd = onDone) { change, _ ->
                    change.consume()
                    onChange((change.position.x / width).coerceIn(0f, 1f))
                }
            }
            .semantics { contentDescription = "Fade ${(value * 100).toInt()} percent" },
    ) {
        val r = 10.dp.toPx()
        val y = size.height / 2
        val x = r + (size.width - 2 * r) * value
        drawLine(C.line, Offset(r, y), Offset(size.width - r, y), 4.dp.toPx(), StrokeCap.Round)
        drawLine(C.accent, Offset(r, y), Offset(x, y), 4.dp.toPx(), StrokeCap.Round)
        drawCircle(Color(0x2214201D), r + 1.5.dp.toPx(), Offset(x, y + 1.dp.toPx()))
        drawCircle(Color.White, r, Offset(x, y))
        drawCircle(C.accent, r - 1.dp.toPx(), Offset(x, y), style = Stroke(2.5.dp.toPx()))
    }
}

@Composable
fun Crosshair(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(48.dp)) {
        val c = Offset(size.width / 2, size.height / 2)
        val s = 2.dp.toPx()
        drawLine(color, Offset(0f, c.y), Offset(size.width, c.y), s)
        drawLine(color, Offset(c.x, 0f), Offset(c.x, size.height), s)
        drawCircle(color, 9.dp.toPx(), c, style = Stroke(s))
    }
}

@Composable
fun Toast(text: String, modifier: Modifier = Modifier, color: Color = C.panel) {
    val shape = RoundedCornerShape(50)
    Box(
        modifier
            .shadow(3.dp, shape, ambientColor = Color(0x4014201D), spotColor = Color(0x4014201D))
            .clip(shape)
            .background(color)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text(text, color = if (color == C.panel) C.ink else Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
    }
}

// ---------- sheets and dialogs ----------

data class Choice(val text: String, val selected: Boolean = false, val danger: Boolean = false, val onPick: () -> Unit)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChoiceSheet(title: String, choices: List<Choice>, onDismiss: () -> Unit) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = state, containerColor = C.panel, shape = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp)) {
        Column(Modifier.padding(horizontal = 16.dp).navigationBarsPadding().padding(bottom = 12.dp)) {
            Text(title, fontWeight = FontWeight.ExtraBold, fontSize = 19.sp, color = C.ink, modifier = Modifier.padding(start = 4.dp, bottom = 8.dp))
            for (c in choices) {
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(if (c.selected) C.accentSoft else Color.Transparent).clickable { onDismiss(); c.onPick() }.padding(vertical = 14.dp, horizontal = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(c.text, fontSize = 17.sp, color = if (c.danger) C.bad else if (c.selected) C.accent else C.ink, fontWeight = if (c.selected) FontWeight.Bold else FontWeight.Medium)
                    if (c.selected) Text("✓", color = C.accent, fontWeight = FontWeight.ExtraBold, fontSize = 17.sp)
                }
            }
        }
    }
}

@Composable
fun NameDialog(title: String, initial: String, placeholder: String = "", onCancel: () -> Unit, onSave: (String) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(title) },
        text = { OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true, placeholder = { Text(placeholder) }) },
        confirmButton = { TextButton(onClick = { onSave(name.trim()) }, enabled = name.isNotBlank()) { Text("Save") } },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
        containerColor = C.panel,
    )
}

@Composable
fun ConfirmDialog(title: String, text: String, confirm: String, onCancel: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirm, color = C.bad) } },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
        containerColor = C.panel,
    )
}

@Composable
fun MessageDialog(title: String, text: String, onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = onClose) { Text("OK") } },
        containerColor = C.panel,
    )
}

@Composable
fun ScrollColumn(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState())) { content() }
}

@Composable
fun Divider() = HorizontalDivider(color = C.line)

