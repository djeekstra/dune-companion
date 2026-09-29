package io.github.deepakgsingh.dunecompanion.ui

import android.util.Log
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.deepakgsingh.dunecompanion.Config.GLYPH_MIN_SIZE_DP
import io.github.deepakgsingh.dunecompanion.Config.GLYPH_STROKE_GAP_MS
import io.github.deepakgsingh.dunecompanion.GlyphPoint
import io.github.deepakgsingh.dunecompanion.GlyphVerdict
import io.github.deepakgsingh.dunecompanion.Steward
import io.github.deepakgsingh.dunecompanion.recogniseGlyph
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "DuneGlyph"

/**
 * The display as a writing surface: a letter drawn on it claims that space for whoever has the
 * turn. The trail is drawn as the finger moves and fades once the letter has been read — bright
 * if it was read as a letter, dim if not — so a refused letter is visibly refused.
 *
 * A stroke consumes its movement once past touch slop, which cancels the display's tap and
 * long-press detector: without that, the three strokes of a letter would be a triple tap.
 */
@Composable
fun GlyphPad(modifier: Modifier = Modifier) {
    // Strokes of the letter under way, and the one being drawn right now.
    val strokes = remember { mutableStateListOf<List<Offset>>() }
    val current = remember { mutableStateListOf<Offset>() }
    // The letter just read, fading out.
    var fading by remember { mutableStateOf<List<List<Offset>>>(emptyList()) }
    var fadeTint by remember { mutableStateOf(Palette.Fire) }
    // What it was read as, shown large while the trail fades. The claim can still be refused
    // (the space is already held), and without this a good read followed by the rejected cue
    // is indistinguishable from a misread.
    var fadeLetter by remember { mutableStateOf<Char?>(null) }
    val fade = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    var pending by remember { mutableStateOf<Job?>(null) }
    val minSizePx = with(LocalDensity.current) { GLYPH_MIN_SIZE_DP.dp.toPx() }

    fun finish() {
        val letter = strokes.toList()
        strokes.clear()
        scope.launch {
            val drawn = letter.map { s -> s.map { GlyphPoint(it.x, it.y) } }
            // ~20-40 ms on a desktop JVM; keep it off the frame.
            val verdict = withContext(Dispatchers.Default) { recogniseGlyph(drawn, minSizePx) }
            val nearest = verdict.nearest.joinToString { "%s %.2f".format(it.first, it.second) }
            val summary = when (verdict) {
                is GlyphVerdict.Recognised -> "${verdict.glyph.letter} (%.2f)".format(verdict.distance)
                is GlyphVerdict.Rejected -> "no: ${verdict.reason}"
            }
            Log.i(TAG, "$summary; ${letter.size} stroke(s); nearest $nearest")
            // The raw strokes, so a letter misread at the table can be replayed in GlyphsTest.
            Log.d(TAG, "strokes " + letter.joinToString(" | ") { s -> s.joinToString(" ") { "%.0f,%.0f".format(it.x, it.y) } })
            Steward.updateGestures { it.copy(lastGlyph = "$summary · $nearest") }
            if (verdict is GlyphVerdict.Recognised) Steward.requestGlyphClaim(verdict.glyph)

            fading = letter
            fadeTint = if (verdict is GlyphVerdict.Recognised) Palette.Fire else Palette.Ash
            fadeLetter = (verdict as? GlyphVerdict.Recognised)?.glyph?.letter
            fade.snapTo(1f)
            fade.animateTo(0f, tween(1_000))
            fading = emptyList()
            fadeLetter = null
        }
    }

    Box(modifier.fillMaxSize()) {
    Canvas(
        Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    // Another stroke of the same letter, like the second half of an M lifted
                    // halfway: hold off reading it.
                    pending?.cancel()
                    current.clear()
                    current += down.position
                    var drawing = false
                    while (true) {
                        val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break
                        current += change.position
                        if (!drawing && (change.position - down.position).getDistance() > viewConfiguration.touchSlop) {
                            drawing = true
                        }
                        if (drawing) change.consume()
                    }
                    if (drawing) strokes += current.toList()
                    current.clear()
                    if (strokes.isNotEmpty()) {
                        pending = scope.launch {
                            delay(GLYPH_STROKE_GAP_MS)
                            finish()
                        }
                    }
                }
            },
    ) {
        val width = 6.dp.toPx()
        fun trail(points: List<Offset>, color: Color) {
            if (points.size < 2) return
            val path = Path().apply {
                moveTo(points[0].x, points[0].y)
                for (i in 1 until points.size) lineTo(points[i].x, points[i].y)
            }
            drawPath(path, color, style = Stroke(width, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
        strokes.forEach { trail(it, Palette.Ember) }
        trail(current, Palette.Ember)
        if (fade.value > 0f) fading.forEach { trail(it, fadeTint.copy(alpha = fade.value)) }
    }
    fadeLetter?.let {
        Text(
            it.toString(),
            color = Palette.Fire.copy(alpha = fade.value),
            fontSize = 160.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Black,
            modifier = Modifier.align(Alignment.Center),
        )
    }
    }
}
