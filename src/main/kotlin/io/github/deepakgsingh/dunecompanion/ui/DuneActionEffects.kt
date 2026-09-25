package io.github.deepakgsingh.dunecompanion.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import io.github.deepakgsingh.dunecompanion.DuneActionEffectEvent
import kotlin.math.cos
import kotlin.math.sin

/**
 * Code-native Dune action effects. The caller passes one unique action id for each completed
 * agent/reveal action; a state refresh with the same id must not replay the effect.
 */
@Composable
fun DuneActionEffects(
    event: DuneActionEffectEvent?,
    modifier: Modifier = Modifier,
) {
    val progress = remember { Animatable(1f) }
    LaunchedEffect(event?.id) {
        if (event == null) return@LaunchedEffect
        progress.snapTo(0f)
        progress.animateTo(
            1f,
            animationSpec = tween(760, easing = FastOutSlowInEasing),
        )
    }

    if (event == null || progress.value >= 1f) return

    val t = progress.value
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) { drawRevealComplete(t) }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawRevealComplete(t: Float) {
    val eased = FastOutSlowInEasing.transform(t)
    val center = Offset(size.width * 0.5f, size.height * 0.46f)
    val cardSize = Size(size.minDimension * 0.19f, size.minDimension * 0.27f)
    drawRoundRect(
        color = Palette.Fire.copy(alpha = (1f - t).coerceIn(0f, 1f)),
        topLeft = center - Offset(cardSize.width, cardSize.height) * (0.5f + 0.2f * (1f - eased)),
        size = cardSize * (0.75f + 0.25f * eased),
        cornerRadius = CornerRadius(12f, 12f),
        style = Stroke(width = 5f),
    )
    repeat(7) { index ->
        val angle = (index * 0.9f).toDouble()
        val radius = size.minDimension * (0.10f + 0.24f * eased)
        drawCircle(
            color = if (index % 2 == 0) Palette.Fire else Palette.Ash,
            alpha = (1f - t).coerceIn(0f, 1f),
            radius = size.minDimension * 0.012f,
            center = center + Offset(cos(angle).toFloat() * radius, sin(angle).toFloat() * radius),
        )
    }
}
