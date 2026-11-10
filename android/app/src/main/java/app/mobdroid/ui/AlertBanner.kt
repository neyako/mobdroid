package app.mobdroid.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.mobdroid.stream.Alert
import app.mobdroid.stream.AlertLevel
import app.mobdroid.stream.StreamEngine

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun AlertBanner(engine: StreamEngine, modifier: Modifier) {
    val alert by engine.alert.collectAsStateWithLifecycle()
    val motion = MaterialTheme.motionScheme
    AnimatedContent(
        targetState = alert,
        transitionSpec = {
            (slideInVertically(motion.defaultSpatialSpec()) { -it } + fadeIn(motion.defaultEffectsSpec())) togetherWith
                (slideOutVertically(motion.fastSpatialSpec()) { -it } + fadeOut(motion.fastEffectsSpec()))
        },
        contentKey = { it?.id },
        modifier = modifier,
        label = "alert",
    ) { shown ->
        if (shown != null) AlertCard(shown, engine::dismissAlert)
    }
}

@Composable
private fun AlertCard(alert: Alert, onDismiss: () -> Unit) {
    val (container, content) = when (alert.level) {
        AlertLevel.ERROR -> Color(0xFFD32F2F) to Color.White
        AlertLevel.WARNING -> Color(0xFFFFCC00) to Color.Black
        AlertLevel.INFO -> MaterialTheme.colorScheme.surfaceContainerHighest to Color.White
    }
    Surface(
        onClick = onDismiss,
        color = container,
        contentColor = content,
        shape = RoundedCornerShape(24.dp),
        modifier = Modifier.padding(12.dp).widthIn(min = 200.dp, max = 440.dp),
    ) {
        Column(Modifier.padding(horizontal = 24.dp, vertical = 14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                alert.title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            alert.detail?.let {
                Text(it, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
