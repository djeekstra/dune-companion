package io.github.deepakgsingh.dunecompanion.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import io.github.deepakgsingh.dunecompanion.DubblegConnection
import io.github.deepakgsingh.dunecompanion.service.verifyDubblegCredential
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun DubblegSettings(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val connection = remember { DubblegConnection(context.applicationContext) }
    val scope = rememberCoroutineScope()
    var existing by remember { mutableStateOf(connection.credential()) }
    var token by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf("") }
    MaterialTheme(colorScheme = darkColorScheme(
        primary = Palette.Fire, surface = Palette.Char, surfaceContainerHigh = Palette.Char,
        onSurface = Color(0xFFFFE0CA), onSurfaceVariant = Color(0xFFD2A88A), outline = Palette.Ash,
    )) {
        AlertDialog(
            onDismissRequest = { if (!busy) onDismiss() },
            title = { Text("DubbleG stats") },
            text = {
                Column {
                    Text(existing?.let { "Connected as ${it.name}." } ?: "Ask the DubbleG admin for a phone credential from Stats devices.")
                    Text("Check Share finished stats on DubbleG in game setup, or share a past game from the panel’s STATS tab.")
                    OutlinedTextField(value = token, onValueChange = { token = it }, enabled = !busy,
                        label = { Text("Phone credential") }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                    if (note.isNotEmpty()) Text(note)
                }
            },
            confirmButton = {
                TextButton(enabled = !busy && token.isNotBlank(), onClick = {
                    busy = true
                    note = "Connecting…"
                    scope.launch {
                        try {
                            val verified = verifyDubblegCredential(token.trim())
                            withContext(Dispatchers.IO) { connection.save(verified) }
                            token = ""
                            existing = verified
                            note = "Connected. Choose sharing for each game in the panel."
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (failure: Exception) { note = failure.message ?: "Could not connect. Check internet access and try again." }
                        finally { busy = false }
                    }
                }) { Text(if (busy) "Connecting…" else "Connect") }
            },
            dismissButton = {
                TextButton(enabled = !busy, onClick = onDismiss) { Text("Close") }
                if (existing != null) TextButton(enabled = !busy, onClick = {
                    scope.launch {
                        busy = true
                        try {
                            withContext(Dispatchers.IO) { connection.disconnect() }
                            existing = null
                            token = ""
                            note = "Disconnected. Queued uploads wait until you reconnect this phone."
                        } finally { busy = false }
                    }
                }) { Text("Disconnect") }
            },
        )
    }
}
