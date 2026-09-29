package io.github.deepakgsingh.dunecompanion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.random.Random

/**
 * Synthetic handwriting: each letter is drawn in a shape deliberately unlike its templates
 * (lopsided, slanted, rounded where the template is sharp), then placed on a 1080-px-wide
 * screen at a random size, rotation and position, with a shaky hand and uneven touch sampling.
 */
class GlyphsTest {
    private val minSizePx = 150f

    private fun arc(cx: Float, cy: Float, rx: Float, ry: Float, from: Float, to: Float): GlyphStroke =
        (0..30).map {
            val a = (from + (to - from) * it / 30) * PI / 180.0
            GlyphPoint(cx + rx * cos(a).toFloat(), cy + ry * sin(a).toFloat())
        }

    private fun line(vararg xy: Float): GlyphStroke = xy.toList().chunked(2).map { GlyphPoint(it[0], it[1]) }

    // Small top loop, big bottom one, leaning right: how an S tends to come out in a hurry.
    private val lopsidedS = listOf(
        arc(0.55f, 0.2f, 0.3f, 0.2f, -20f, -270f).map { GlyphPoint(it.x + 0.1f * (1 - it.y), it.y) } +
            arc(0.5f, 0.7f, 0.45f, 0.3f, -90f, 160f),
    )
    private val squatS = listOf(arc(0.5f, 0.2f, 0.5f, 0.2f, -40f, -270f) + arc(0.5f, 0.6f, 0.5f, 0.2f, -90f, 150f))
    // Rounded shoulders and a V that stops short of the baseline.
    private val roundM = listOf(
        line(0f, 1f, 0.02f, 0.2f) + arc(0.14f, 0.2f, 0.12f, 0.12f, 180f, 360f) + line(0.26f, 0.2f, 0.5f, 0.6f, 0.74f, 0.2f) +
            arc(0.86f, 0.2f, 0.12f, 0.12f, 180f, 360f) + line(0.98f, 0.2f, 1f, 1f),
    )
    private val sharpM = listOf(line(0f, 1f, 0.1f, 0f, 0.45f, 0.8f, 0.9f, 0.05f, 1f, 0.95f))
    // Egg-shaped and closing further at the top than the bottom.
    private val eggC = listOf(arc(0.5f, 0.5f, 0.42f, 0.5f, -70f, -320f))
    private val openC = listOf(arc(0.5f, 0.5f, 0.5f, 0.45f, -75f, -285f))

    private fun place(
        shape: List<GlyphStroke>,
        rnd: Random,
        rotationDeg: Float = rnd.nextFloat() * 360f,
        sizePx: Float = 250f + rnd.nextFloat() * 400f,
        shake: Float = 0.02f,
    ): List<GlyphStroke> {
        val r = rotationDeg * PI / 180.0
        val c = cos(r).toFloat()
        val s = sin(r).toFloat()
        val ox = 300f + rnd.nextFloat() * 400f
        val oy = 600f + rnd.nextFloat() * 800f
        return shape.map { stroke ->
            // Touch events arrive every few pixels, not evenly: resample the stroke at random steps.
            val dense = stroke.zipWithNext().flatMap { (a, b) ->
                val n = (hypot(b.x - a.x, b.y - a.y) * 40).toInt().coerceAtLeast(1)
                (0 until n).map { i ->
                    val t = (i + rnd.nextFloat() * 0.8f) / n
                    GlyphPoint(a.x + t * (b.x - a.x), a.y + t * (b.y - a.y))
                }
            } + stroke.last()
            dense.map { p ->
                val x = (p.x - 0.5f + (rnd.nextFloat() - 0.5f) * shake) * sizePx
                val y = (p.y - 0.5f + (rnd.nextFloat() - 0.5f) * shake) * sizePx
                GlyphPoint(ox + x * c - y * s, oy + x * s + y * c)
            }
        }
    }

    private fun recognised(strokes: List<GlyphStroke>): Glyph? =
        (recogniseGlyph(strokes, minSizePx) as? GlyphVerdict.Recognised)?.glyph

    // Never misread; allowed to refuse a rare ambiguous one, since that only costs a redraw.
    private fun assertAlwaysRead(shape: List<GlyphStroke>, expected: Glyph, seed: Int) {
        val rnd = Random(seed)
        var refused = 0
        repeat(60) { trial ->
            when (val verdict = recogniseGlyph(place(shape, rnd), minSizePx)) {
                is GlyphVerdict.Recognised -> assertEquals("trial $trial: $verdict", expected, verdict.glyph)
                is GlyphVerdict.Rejected -> refused++
            }
        }
        assertTrue("refused $refused of 60", refused <= 3)
    }

    @Test
    fun anSIsReadFromAnySideOfTheTable() {
        assertAlwaysRead(lopsidedS, Glyph.SWORDMASTER, seed = 1)
        assertAlwaysRead(squatS, Glyph.SWORDMASTER, seed = 2)
    }

    @Test
    fun anSDrawnBottomUpIsStillAnS() {
        assertAlwaysRead(listOf(lopsidedS[0].reversed()), Glyph.SWORDMASTER, seed = 3)
    }

    @Test
    fun anMIsReadFromAnySideOfTheTable() {
        assertAlwaysRead(roundM, Glyph.MENTAT, seed = 4)
        assertAlwaysRead(sharpM, Glyph.MENTAT, seed = 5)
    }

    @Test
    fun aCIsReadFromAnySideOfTheTable() {
        assertAlwaysRead(eggC, Glyph.HIGH_COUNCIL, seed = 6)
        assertAlwaysRead(openC, Glyph.HIGH_COUNCIL, seed = 7)
        assertAlwaysRead(listOf(eggC[0].reversed()), Glyph.HIGH_COUNCIL, seed = 8)
    }

    @Test
    fun aBackwardsSCountsAsAnS() {
        assertAlwaysRead(listOf(lopsidedS[0].map { GlyphPoint(1f - it.x, it.y) }), Glyph.SWORDMASTER, seed = 9)
    }

    @Test
    fun strayStrokesAreRejected() {
        val rnd = Random(10)
        val strays = listOf(
            "swipe" to listOf(line(0f, 0f, 1f, 0.1f)),
            "curved swipe" to listOf(arc(0.5f, 1f, 0.6f, 0.3f, 190f, 350f)),
            "circle" to listOf(arc(0.5f, 0.5f, 0.5f, 0.45f, 0f, 350f)),
            "scribble" to listOf(line(0f, 0f, 0.2f, 0.9f, 0.35f, 0.1f, 0.5f, 1f, 0.7f, 0f, 0.85f, 0.9f, 1f, 0.1f)),
            "two swipes" to listOf(line(0f, 0f, 1f, 0f), line(0f, 0.2f, 1f, 0.25f)),
        )
        strays.forEach { (name, shape) ->
            repeat(30) { trial ->
                val verdict = recogniseGlyph(place(shape, rnd), minSizePx)
                assertTrue("$name trial $trial: $verdict", verdict is GlyphVerdict.Rejected)
            }
        }
    }

    @Test
    fun aSmallMarkIsNotALetterHoweverShaped() {
        val verdict = recogniseGlyph(place(lopsidedS, Random(11), sizePx = 100f), minSizePx)
        assertTrue(verdict.toString(), verdict is GlyphVerdict.Rejected)
    }

    @Test
    fun aTapIsNotALetter() {
        val verdict = recogniseGlyph(listOf(listOf(GlyphPoint(500f, 500f))), minSizePx)
        assertTrue(verdict is GlyphVerdict.Rejected)
    }

    private fun tableLetters(file: String): List<Pair<Char, List<GlyphStroke>>> =
        javaClass.getResourceAsStream("/glyphs/$file")!!.bufferedReader().readLines()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .map { line ->
                val (letter, strokes) = line.split('\t')
                letter.single() to strokes.split(" | ").map { stroke ->
                    stroke.trim().split(' ').map { p -> p.split(',').let { GlyphPoint(it[0].toFloat(), it[1].toFloat()) } }
                }
            }

    // GLYPH_MIN_SIZE_DP at the Pixel 4a's 2.75 px per dp.
    private val pixel4aMinSize = 60f * 2.75f

    // Real handwriting from the table: every letter here was read on the phone and must stay
    // read. Add letters from DuneGlyph "strokes" log lines as they turn up.
    @Test
    fun lettersDrawnAtTheTableAreRead() {
        val letters = tableLetters("pixel4a-2026-09-29.txt") + tableLetters("pixel4a-2026-09-29b.txt")
        val failures = letters.mapIndexedNotNull { i, (letter, drawn) ->
            val verdict = recogniseGlyph(drawn, pixel4aMinSize)
            "letter $i ($letter): $verdict".takeIf { (verdict as? GlyphVerdict.Recognised)?.glyph?.letter != letter }
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    // The ones it cannot read yet. Refusing costs a redraw; reading them as another letter would
    // hand out the wrong space.
    @Test
    fun lettersNotYetReadAreRefusedNotMisread() {
        tableLetters("pixel4a-2026-09-29-missed.txt").forEachIndexed { i, (letter, drawn) ->
            val verdict = recogniseGlyph(drawn, pixel4aMinSize)
            if (verdict is GlyphVerdict.Recognised) assertEquals("letter $i: $verdict", letter, verdict.glyph.letter)
        }
    }
}
