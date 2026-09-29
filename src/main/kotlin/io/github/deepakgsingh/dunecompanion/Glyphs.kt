package io.github.deepakgsingh.dunecompanion

import io.github.deepakgsingh.dunecompanion.Config.GLYPH_MAX_DISTANCE
import io.github.deepakgsingh.dunecompanion.Config.GLYPH_MIN_MARGIN
import io.github.deepakgsingh.dunecompanion.Config.GLYPH_POINTS
import io.github.deepakgsingh.dunecompanion.Config.GLYPH_ROTATION_STEP_DEGREES
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Letters drawn on the display to claim a board space for whoever has the turn.
 *
 * Chosen so that no two are rotations of each other: the phone lies in the middle of the table
 * and is drawn on from every side, so the recogniser accepts any rotation. A W or a Z would be
 * an M or an N from across the table, and a U is a C from the side.
 *
 * [strokes] is how many strokes the letter can be written in. The point cloud deliberately
 * forgets that, which is what lets an H come in any order, but it is still worth knowing: an S
 * is one stroke, and without the check two parallel swipes read as an S.
 */
enum class Glyph(val letter: Char, val strokes: IntRange) {
    SWORDMASTER('S', 1..1),
    MENTAT('M', 1..4),
    HIGH_COUNCIL('C', 1..1),
}

data class GlyphPoint(val x: Float, val y: Float)

/** One finger-down-to-finger-up stroke, in screen pixels. */
typealias GlyphStroke = List<GlyphPoint>

sealed interface GlyphVerdict {
    // The nearest few templates and their distances, for the log line that explains a verdict.
    val nearest: List<Pair<String, Float>>

    data class Recognised(
        val glyph: Glyph,
        val distance: Float,
        override val nearest: List<Pair<String, Float>> = emptyList(),
    ) : GlyphVerdict

    data class Rejected(
        val reason: String,
        override val nearest: List<Pair<String, Float>> = emptyList(),
    ) : GlyphVerdict
}

/**
 * Recognises a drawn letter with the $P point-cloud recogniser (Vatavu, Anthony & Wobbrock,
 * 2012), plus a sweep over rotations.
 *
 * $P compares the gesture as a cloud of points, so how many strokes it took, in what order and
 * which way each was drawn do not matter: an S drawn bottom-up, or an M lifted halfway, are
 * the same cloud — provided the letter can be written in that many strokes. $P is not rotation-invariant, and nothing tells the phone which side of the
 * table the player is sitting on, so the gesture is tried at every [GLYPH_ROTATION_STEP_DEGREES]
 * and the best fit wins.
 *
 * Decoys — a swipe, an arc, a circle, a scribble — are templates that win for the strokes a
 * phone on a table actually collects by accident, and a win for one is a rejection. Without
 * them the nearest letter is always *something*.
 *
 * A letter that only narrowly beats something else is refused too: a refusal costs a redraw,
 * a misread costs a correction from the panel.
 *
 * Mirror images are not guarded against. None of S, C and M turns into another when mirrored
 * (a mirrored C is a C turned round), so a backwards S is taught as an S.
 */
fun recogniseGlyph(strokes: List<GlyphStroke>, minSizePx: Float): GlyphVerdict {
    val drawn = strokes.filter { it.isNotEmpty() }
    if (drawn.isEmpty()) return GlyphVerdict.Rejected("nothing drawn")
    val all = drawn.flatten()
    val size = max(all.maxOf { it.x } - all.minOf { it.x }, all.maxOf { it.y } - all.minOf { it.y })
    if (size < minSizePx) return GlyphVerdict.Rejected("too small (%.0f px)".format(size))

    val ranked = rankTemplates(drawn)
    val nearest = ranked.take(3).map { (t, d) -> t.name to d }
    val (match, bestDistance) = ranked.firstOrNull() ?: return GlyphVerdict.Rejected("no templates")
    val glyph = match.glyph
        ?: return GlyphVerdict.Rejected("looks like ${match.name}", nearest)
    if (bestDistance > GLYPH_MAX_DISTANCE) {
        return GlyphVerdict.Rejected("closest is ${glyph.letter} but too far", nearest)
    }
    ranked.firstOrNull { it.first.glyph != glyph }?.let { (rival, d) ->
        if (d - bestDistance < GLYPH_MIN_MARGIN) {
            return GlyphVerdict.Rejected("${glyph.letter} or ${rival.name}?", nearest)
        }
    }
    return GlyphVerdict.Recognised(glyph, bestDistance, nearest)
}

// Every eligible template's best distance over all rotations, nearest first.
private fun rankTemplates(strokes: List<GlyphStroke>): List<Pair<GlyphTemplate, Float>> {
    if (strokes.isEmpty()) return emptyList()
    val rotations = (0 until (360f / GLYPH_ROTATION_STEP_DEGREES).toInt())
        .map { rotate(normalise(strokes), it * GLYPH_ROTATION_STEP_DEGREES) }
    // Decoys take any number of strokes: they are there to catch whatever was not a letter.
    val eligible = TEMPLATES.filter { it.glyph == null || strokes.size in it.glyph.strokes }
    return eligible.map { template ->
        var best = Float.MAX_VALUE
        for (rotated in rotations) best = min(best, greedyCloudMatch(rotated, template.points, best))
        template to best
    }.sortedBy { it.second }
}

private class CloudPoint(val x: Float, val y: Float, val stroke: Int)

/** A template's letter, or null for a decoy that exists only to be rejected. */
private class GlyphTemplate(val name: String, val glyph: Glyph?, val points: List<CloudPoint>)

// --- $P ------------------------------------------------------------------------------------

private fun normalise(strokes: List<GlyphStroke>): List<CloudPoint> {
    val points = strokes.flatMapIndexed { id, s -> s.map { CloudPoint(it.x, it.y, id) } }
    return normaliseCloud(resample(points, GLYPH_POINTS))
}

// Spaces n points evenly along the drawn path. The gap between strokes is not path: a new
// point is only interpolated between two points of the same stroke.
private fun resample(points: List<CloudPoint>, n: Int): List<CloudPoint> {
    val length = points.zipWithNext().sumOf { (a, b) ->
        if (a.stroke == b.stroke) hypot(b.x - a.x, b.y - a.y).toDouble() else 0.0
    }.toFloat()
    // A single dot has no length to space along.
    if (length == 0f) return List(n) { points.first() }
    val interval = length / (n - 1)
    val out = mutableListOf(points.first())
    var travelled = 0f
    var prev = points.first()
    var i = 1
    while (i < points.size) {
        val p = points[i]
        if (p.stroke == prev.stroke) {
            val d = hypot(p.x - prev.x, p.y - prev.y)
            if (travelled + d >= interval && d > 0f) {
                val t = (interval - travelled) / d
                val q = CloudPoint(prev.x + t * (p.x - prev.x), prev.y + t * (p.y - prev.y), p.stroke)
                out += q
                prev = q
                travelled = 0f
                continue // q is the new prev; measure on to the same p
            }
            travelled += d
        }
        prev = p
        i++
    }
    // Rounding can leave the last point off; it is always the end of the path.
    while (out.size < n) out += points.last()
    return out.take(n)
}

private fun normaliseCloud(points: List<CloudPoint>): List<CloudPoint> = scale(translateToOrigin(points))

private fun translateToOrigin(points: List<CloudPoint>): List<CloudPoint> {
    val cx = points.sumOf { it.x.toDouble() }.toFloat() / points.size
    val cy = points.sumOf { it.y.toDouble() }.toFloat() / points.size
    return points.map { CloudPoint(it.x - cx, it.y - cy, it.stroke) }
}

// Uniform scale, so a squat S and a tall one stay different, and by the RMS distance from the
// centroid rather than $P's bounding box: a letter's bounding box grows as it is turned, so a
// box-scaled M drawn at 45 degrees came out ~30% smaller than its upright template and no
// amount of rotating afterwards could make them match.
private fun scale(points: List<CloudPoint>): List<CloudPoint> {
    val rms = sqrt(points.sumOf { (it.x * it.x + it.y * it.y).toDouble() } / points.size).toFloat()
        .takeIf { it > 0f } ?: 1f
    return points.map { CloudPoint(it.x / rms, it.y / rms, it.stroke) }
}

// About the centroid, which normaliseCloud has put at 0,0.
private fun rotate(points: List<CloudPoint>, degrees: Float): List<CloudPoint> {
    if (degrees == 0f) return points
    val r = degrees * PI / 180.0
    val c = cos(r).toFloat()
    val s = sin(r).toFloat()
    return points.map { CloudPoint(it.x * c - it.y * s, it.x * s + it.y * c, it.stroke) }
}

private fun greedyCloudMatch(points: List<CloudPoint>, template: List<CloudPoint>, bound: Float): Float {
    val n = points.size
    val step = floor(sqrt(n.toDouble())).toInt()
    var best = Float.MAX_VALUE
    for (start in 0 until n step step) {
        best = min(best, cloudDistance(points, template, start, min(best, bound)))
        best = min(best, cloudDistance(template, points, start, min(best, bound)))
    }
    return best
}

// Matches each point of [a], from [start] round, to its nearest unmatched point of [b].
// Earlier matches weigh more, being the ones made with the most choice. Gives up once the sum
// passes [bound], as it can no longer be the best.
private fun cloudDistance(a: List<CloudPoint>, b: List<CloudPoint>, start: Int, bound: Float): Float {
    val n = a.size
    val matched = BooleanArray(n)
    var sum = 0f
    var i = start
    do {
        var index = -1
        var nearest = Float.MAX_VALUE
        for (j in 0 until n) {
            if (matched[j]) continue
            val d = hypot(a[i].x - b[j].x, a[i].y - b[j].y)
            if (d < nearest) {
                nearest = d
                index = j
            }
        }
        matched[index] = true
        val weight = 1f - ((i - start + n) % n).toFloat() / n
        sum += weight * nearest
        if (sum >= bound) return sum
        i = (i + 1) % n
    } while (i != start)
    return sum
}

// --- Templates -----------------------------------------------------------------------------
//
// Drawn in a y-down unit box, as on screen. A handful of variants per letter covers the ways
// people actually write it; recording real strokes from the table is the next step if these
// miss.

private fun polyline(vararg xy: Float): GlyphStroke = xy.toList().chunked(2).map { GlyphPoint(it[0], it[1]) }

private fun arc(cx: Float, cy: Float, rx: Float, ry: Float, fromDeg: Float, toDeg: Float, steps: Int = 24): GlyphStroke =
    (0..steps).map {
        val a = (fromDeg + (toDeg - fromDeg) * it / steps) * PI / 180.0
        GlyphPoint(cx + rx * cos(a).toFloat(), cy + ry * sin(a).toFloat())
    }

// An S is two arcs meeting at the middle: the top one opens to the right, the bottom one to the
// left. Angles are y-down, so going from -30 to -270 (i.e. anticlockwise on screen) sweeps over
// the top, down the left, into the middle.
// [waist] is where they meet: hurried S's are rarely symmetric.
private fun sShape(width: Float, waist: Float = 0.5f): GlyphStroke =
    arc(0.5f, waist / 2, width / 2, waist / 2, -30f, -270f) +
        arc(0.5f, (1 + waist) / 2, width / 2, (1 - waist) / 2, -90f, 150f)

private fun mirrored(stroke: GlyphStroke): GlyphStroke = stroke.map { GlyphPoint(1f - it.x, it.y) }

private fun template(name: String, glyph: Glyph?, vararg strokes: GlyphStroke) =
    GlyphTemplate(name, glyph, normalise(strokes.toList()))

private val TEMPLATES: List<GlyphTemplate> = listOf(
    template("S", Glyph.SWORDMASTER, sShape(1f)),
    template("S narrow", Glyph.SWORDMASTER, sShape(0.6f)),
    template("S small top", Glyph.SWORDMASTER, sShape(0.8f, waist = 0.4f)),
    template("S small bottom", Glyph.SWORDMASTER, sShape(0.8f, waist = 0.6f)),
    template("S angular", Glyph.SWORDMASTER, polyline(0.9f, 0.05f, 0.1f, 0.1f, 0.1f, 0.45f, 0.9f, 0.55f, 0.9f, 0.9f, 0.1f, 0.95f)),

    template("S backwards", Glyph.SWORDMASTER, mirrored(sShape(1f))),
    template("S backwards small top", Glyph.SWORDMASTER, mirrored(sShape(0.8f, waist = 0.4f))),

    template("M", Glyph.MENTAT, polyline(0f, 1f, 0f, 0f, 0.5f, 0.6f, 1f, 0f, 1f, 1f)),
    // Handwriting as taught: start at the top, down the first leg and back up over it, then
    // the V and the second leg. The retraced leg carries twice the points, so it needs its
    // own templates (Pixel 4a, 2026-09-29: all three of one player's M's were written so, and
    // none came within reach of an untraced M).
    template("M retraced", Glyph.MENTAT, polyline(0f, 0f, 0f, 1f, 0f, 0f, 0.5f, 0.6f, 1f, 0f, 1f, 1f)),
    template("M retraced deep", Glyph.MENTAT, polyline(0f, 0f, 0f, 1f, 0f, 0f, 0.5f, 0.9f, 1f, 0f, 1f, 1f)),
    template("M retraced splayed", Glyph.MENTAT, polyline(0.2f, 0f, 0f, 1f, 0.2f, 0f, 0.5f, 0.7f, 0.8f, 0f, 1f, 1f)),
    // The same player's M's were lopsided too: a narrow left half, the V well left of centre, a
    // wide right half whose leg splays out.
    template("M retraced lopsided", Glyph.MENTAT, polyline(0.05f, 0.3f, 0f, 1f, 0.05f, 0f, 0.35f, 0.55f, 0.65f, 0f, 0.95f, 0.85f)),
    template("M deep", Glyph.MENTAT, polyline(0f, 1f, 0f, 0f, 0.5f, 1f, 1f, 0f, 1f, 1f)),
    template("M splayed", Glyph.MENTAT, polyline(0f, 1f, 0.2f, 0f, 0.5f, 0.7f, 0.8f, 0f, 1f, 1f)),
    template("M rounded", Glyph.MENTAT, polyline(0f, 1f, 0.02f, 0.15f) + arc(0.14f, 0.15f, 0.12f, 0.12f, 180f, 360f) +
        polyline(0.26f, 0.15f, 0.5f, 0.6f, 0.74f, 0.15f) + arc(0.86f, 0.15f, 0.12f, 0.12f, 180f, 360f) + polyline(0.98f, 0.15f, 1f, 1f)),
    template("M shallow", Glyph.MENTAT, polyline(0f, 1f, 0f, 0f, 0.5f, 0.35f, 1f, 0f, 1f, 1f)),

    // A C is an arc that goes most of the way round: much less and it is a curved swipe (the
    // arc decoy), much more and it is a circle.
    template("C", Glyph.HIGH_COUNCIL, arc(0.5f, 0.5f, 0.5f, 0.5f, -60f, -300f)),
    template("C tight", Glyph.HIGH_COUNCIL, arc(0.5f, 0.5f, 0.5f, 0.5f, -40f, -320f)),
    template("C narrow", Glyph.HIGH_COUNCIL, arc(0.5f, 0.5f, 0.35f, 0.5f, -55f, -305f)),

    template("a swipe", null, polyline(0f, 0f, 1f, 0f)),
    template("an arc", null, arc(0.5f, 1f, 0.5f, 0.4f, 180f, 360f)),
    template("a circle", null, arc(0.5f, 0.5f, 0.5f, 0.5f, 0f, 360f, steps = 36)),
    template("a scribble", null, polyline(0f, 0f, 0.15f, 1f, 0.3f, 0f, 0.45f, 1f, 0.6f, 0f, 0.75f, 1f, 0.9f, 0f)),
)
