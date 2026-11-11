package com.opirl.app.ui

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun fadeThrough(): ContentTransform {
    val motion = MaterialTheme.motionScheme
    return (fadeIn(motion.defaultEffectsSpec()) + scaleIn(motion.defaultSpatialSpec(), initialScale = 0.92f)) togetherWith
        fadeOut(motion.fastEffectsSpec())
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun Appear(visible: Boolean, vertical: Boolean, content: @Composable () -> Unit) {
    val motion = MaterialTheme.motionScheme
    AnimatedVisibility(
        visible,
        enter = fadeIn(motion.defaultEffectsSpec()) + if (vertical) {
            expandVertically(motion.defaultSpatialSpec(), Alignment.Top)
        } else {
            expandHorizontally(motion.defaultSpatialSpec(), Alignment.Start)
        },
        exit = fadeOut(motion.fastEffectsSpec()) + if (vertical) {
            shrinkVertically(motion.fastSpatialSpec(), Alignment.Top)
        } else {
            shrinkHorizontally(motion.fastSpatialSpec(), Alignment.Start)
        },
    ) { content() }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun backProgress(enabled: Boolean, onBack: () -> Unit): Animatable<Float, AnimationVector1D> {
    val progress = remember { Animatable(0f) }
    val settle = MaterialTheme.motionScheme.fastSpatialSpec<Float>()
    PredictiveBackHandler(enabled) { events ->
        try {
            events.collect { progress.snapTo(it.progress) }
            onBack()
            progress.animateTo(0f, settle)
        } catch (e: CancellationException) {
            withContext(NonCancellable) { progress.animateTo(0f, settle) }
            throw e
        }
    }
    return progress
}

fun Modifier.backGesture(progress: Animatable<Float, AnimationVector1D>): Modifier = graphicsLayer {
    val amount = progress.value.coerceIn(0f, 1f)
    scaleX = 1f - 0.08f * amount
    scaleY = scaleX
    alpha = 1f - 0.35f * amount
}
