package com.brandonthomas.oldground.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.brandonthomas.oldground.data.Pin
import com.brandonthomas.oldground.data.PinIcons
import com.brandonthomas.oldground.data.colorHex
import com.brandonthomas.oldground.data.iconId

/** An icon drawn in [color], filling [modifier]'s size. */
@Composable
fun PinIconGlyph(icon: String?, color: Color, modifier: Modifier) {
    Canvas(modifier) {
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { this.color = color.toArgb() }
        drawIntoCanvas { it.nativeCanvas.drawPath(PinIcons.path(icon, size.minDimension, (size.width - size.minDimension) / 2, (size.height - size.minDimension) / 2), paint) }
    }
}

/** A point as it looks on the map: its icon in white on its colour. */
@Composable
fun PinBadge(icon: String?, color: String, size: Dp = 26.dp) {
    Box(Modifier.size(size).clip(CircleShape).background(hexColor(color)), contentAlignment = Alignment.Center) {
        PinIconGlyph(icon, Color.White, Modifier.size(size * 0.55f))
    }
}

@Composable
fun PinBadge(p: Pin, size: Dp = 26.dp) = PinBadge(p.iconId, p.colorHex, size)

/** Icon grid and colour row for a point. */
@Composable
fun IconColorPicker(icon: String, color: String, onIcon: (String) -> Unit, onColor: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        LabeledField("Icon") {
            val cols = 6
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                for (rowIcons in PinIcons.ICONS.chunked(cols)) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (ic in rowIcons) {
                        val on = ic.id == icon
                        Box(
                            Modifier.weight(1f).aspectRatio(1.15f).clip(RoundedCornerShape(10.dp))
                                .background(if (on) hexColor(color) else C.soft)
                                .clickable(onClickLabel = ic.label) { onIcon(ic.id) },
                            contentAlignment = Alignment.Center,
                        ) { PinIconGlyph(ic.id, if (on) Color.White else Color(0xFF3C4945), Modifier.size(19.dp)) }
                    }
                    repeat(cols - rowIcons.size) { Box(Modifier.weight(1f)) }
                }
            }
        }
        LabeledField("Colour") {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                for (c in PinIcons.COLORS) {
                    val on = c.equals(color, ignoreCase = true)
                    Box(
                        Modifier.size(30.dp).then(if (on) Modifier.border(2.5.dp, C.ink, CircleShape) else Modifier).padding(if (on) 4.dp else 0.dp)
                            .clip(CircleShape).background(hexColor(c)).clickable { onColor(c) },
                    )
                }
            }
        }
    }
}
