package io.github.deepakgsingh.dunecompanion.ui

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import android.os.SystemClock
import io.github.deepakgsingh.dunecompanion.Config.TRIPLE_TAP_GAP_MS
import io.github.deepakgsingh.dunecompanion.TapAction
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import io.github.deepakgsingh.dunecompanion.DuneActionEffectEvent
import io.github.deepakgsingh.dunecompanion.GestureDebug
import io.github.deepakgsingh.dunecompanion.Steward
import io.github.deepakgsingh.dunecompanion.service.quitApp
import io.github.deepakgsingh.dunecompanion.TapCounter

@Composable
fun DisplayScreen() {
    val session by Steward.dune.collectAsState()
    val urls by Steward.panelUrls.collectAsState()
    val gestures by Steward.gestures.collectAsState()
    val effect by Steward.duneEffects.collectAsState(initial = null as DuneActionEffectEvent?)
    val showDubbleg by Steward.dubblegSettings.collectAsState()
    var showDebug by remember { mutableStateOf(false) }
    var taps by remember { mutableStateOf(TapCounter()) }
    var pendingTap by remember { mutableStateOf<Job?>(null) }

    Box(
        Modifier
            .fillMaxSize()
            .background(Palette.Ink)
            // Clear of the camera hole: on a Pixel 4a it sits over the top-left corner, and ate
            // the start of "ROUND 2".
            .windowInsetsPadding(WindowInsets.displayCutout)
            .pointerInput(session.active, session.round, session.currentPlayer, session.phase, session.paused, showDebug, showDubbleg) {
                taps = TapCounter()
                val gestureScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.currentCoroutineContext())
                detectTapGestures(
                    onTap = tap@{
                        if (showDebug || showDubbleg) return@tap
                        pendingTap?.cancel()
                        taps = taps.observe(SystemClock.uptimeMillis()).first
                        pendingTap = gestureScope.launch {
                            delay(TRIPLE_TAP_GAP_MS)
                            when (taps.resolve(SystemClock.uptimeMillis())) {
                                TapAction.AGENT -> Steward.requestTapTurnEnd()
                                TapAction.REVEAL -> Steward.requestTapReveal()
                                null -> Unit
                            }
                            taps = TapCounter()
                        }
                    },
                    onLongPress = {
                        pendingTap?.cancel()
                        taps = TapCounter()
                        showDebug = !showDebug
                    },
                )
            },
    ) {
        if (session.active) {
            DuneScene(session)
            DuneActionEffects(effect, Modifier.fillMaxSize())
            GlyphPad()
            urls.firstOrNull()?.let {
                Text(
                    it,
                    color = Palette.Ash,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp),
                )
            }
        } else {
            IdleCard(urls, onExit = { quitApp(endGame = false) })
        }
        if (showDebug) DebugOverlay(gestures, gameRunning = session.active, onDubbleg = { Steward.showDubblegSettings() })
        if (showDubbleg) DubblegSettings(onDismiss = { Steward.showDubblegSettings(false) })
    }
}

@Composable
private fun IdleCard(urls: List<String>, onExit: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("GAME COMPANION", color = Palette.Fire, fontSize = 26.sp,
            fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Black, letterSpacing = 3.sp)
        Text("DUNE: IMPERIUM", color = Palette.Ash, fontSize = 13.sp,
            fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
        Spacer(Modifier.height(36.dp))
        val url = urls.firstOrNull()
        if (url == null) {
            Text("NOT ON A NETWORK\nJOIN WI-FI OR START A HOTSPOT", color = Palette.Ember,
                fontSize = 15.sp, textAlign = TextAlign.Center, fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold)
        } else {
            val qr = remember(url) { qrBitmap(url, 600) }
            Image(qr.asImageBitmap(), contentDescription = "Panel address", modifier = Modifier.size(240.dp))
            Spacer(Modifier.height(20.dp))
            Text("SCAN WITH THE ADMIN PHONE", color = Palette.Ember, fontSize = 13.sp,
                fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            Spacer(Modifier.height(8.dp))
            urls.forEach {
                Text(it, color = Palette.Fire, fontSize = 18.sp, fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Black)
            }
        }
        Spacer(Modifier.height(36.dp))
        Text("SPIN FLAT = END AGENT TURN · SHAKE = REVEAL\nTRIPLE TAP = END AGENT TURN · HOLD = DEBUG / EXIT\n" +
            "DRAW S = SWORDMASTER · C = HIGH COUNCIL · M = MENTAT",
            color = Palette.Ash, fontSize = 11.sp, textAlign = TextAlign.Center,
            fontFamily = FontFamily.Monospace)
        Spacer(Modifier.height(28.dp))
        ExitButton("EXIT", onExit)
    }
}

@Composable
private fun DebugOverlay(g: GestureDebug, gameRunning: Boolean, onDubbleg: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().background(Color(0xE0000000)).padding(16.dp),
    ) {
        val lines = buildList {
            add(if (g.armed) "ARMED" else "NOT ARMED (start a game)")
            add("gravity %.1f %.1f %.1f  %s".format(g.gravity.first, g.gravity.second, g.gravity.third,
                if (g.flat) "FLAT" else "not flat"))
            add("spin %4.0f°   jolts %d".format(g.spinDegrees, g.shakeJolts))
            add("spins ${g.spins}  refused ${g.spinsRejected}  shakes ${g.shakes}  taps ${g.taps}  letters ${g.glyphs}")
            if (g.lastGlyph.isNotEmpty()) add("last letter: ${g.lastGlyph}")
            add("")
            addAll(g.sensors.ifEmpty { listOf("no sensors reported") })
        }
        lines.forEach {
            Text(it, color = Color.White, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
        }
        Spacer(Modifier.height(16.dp))
        ExitButton("DUBBLEG STATS", onDubbleg)
        Spacer(Modifier.height(12.dp))
        if (!gameRunning) {
            ExitButton("EXIT", onExit = { quitApp(endGame = false) })
        } else {
            // During a game this is the only way off the display without the admin panel.
            // Keeping it freezes the clocks, so reopening does not find a turn that ran on
            // while the app was shut. Ending it throws the game away, so it takes a second tap.
            var confirmEnd by remember { mutableStateOf(false) }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ExitButton("EXIT · KEEP GAME", onExit = { quitApp(endGame = false) })
                ExitButton(
                    if (confirmEnd) "TAP AGAIN TO END" else "EXIT · END GAME",
                    onExit = { if (confirmEnd) quitApp(endGame = true) else confirmEnd = true },
                )
            }
        }
    }
}

@Composable
private fun ExitButton(label: String, onExit: () -> Unit) {
    Text(
        label,
        color = Palette.Fire,
        fontSize = 14.sp,
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Black,
        letterSpacing = 2.sp,
        modifier = Modifier
            .border(1.dp, Palette.Ash, RoundedCornerShape(10.dp))
            .clickable(onClick = onExit)
            .padding(horizontal = 20.dp, vertical = 12.dp),
    )
}

private fun qrBitmap(text: String, size: Int): Bitmap {
    val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size)
    // Plain black on white: phone camera apps are far less reliable reading an inverted or
    // low-contrast code, and this is scanned once per game night, not looked at.
    val pixels = IntArray(size * size) { i ->
        if (matrix.get(i % size, i / size)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
    }
    return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
}
