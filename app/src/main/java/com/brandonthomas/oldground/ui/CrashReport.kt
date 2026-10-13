package com.brandonthomas.oldground.ui

import android.content.Intent
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File

/** After a crash, shows what went wrong and lets you send it on. */
@Composable
fun CrashReport() {
    val ctx = LocalContext.current
    val file = remember { File(ctx.filesDir, "last-crash.txt") }
    var text by remember { mutableStateOf(runCatching { file.takeIf { it.exists() }?.readText() }.getOrNull()) }
    val t = text ?: return
    fun close() { file.delete(); text = null }
    AlertDialog(
        onDismissRequest = { close() },
        title = { Text("OldGround closed unexpectedly") },
        text = {
            Text(
                "Send this report so the problem can be fixed.\n\n" + t.take(4000),
                Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
                fontSize = 12.sp,
            )
        },
        confirmButton = {
            TextButton(onClick = {
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, "OldGround crash report")
                    putExtra(Intent.EXTRA_TEXT, t)
                }
                runCatching { ctx.startActivity(Intent.createChooser(send, "Send crash report")) }
                close()
            }) { Text("Share report") }
        },
        dismissButton = { TextButton(onClick = { close() }) { Text("Close") } },
        containerColor = C.panel,
    )
}
