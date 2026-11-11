package app.mobdroid.chat

import android.graphics.Color
import androidx.core.graphics.ColorUtils
import app.mobdroid.engine.chat.ChatMessage
import kotlin.math.absoluteValue

private val NameColors = intArrayOf(
    0xFFFF8A65.toInt(),
    0xFFB39DFF.toInt(),
    0xFF7FD4FF.toInt(),
    0xFFC7F08A.toInt(),
    0xFFFFD166.toInt(),
    0xFFFF9ECF.toInt(),
    0xFF8EF0D0.toInt(),
)

fun chatNameColor(message: ChatMessage): Int {
    val color = message.userColor?.let { 0xFF000000.toInt() or it }
        ?: return NameColors[message.user.hashCode().absoluteValue % NameColors.size]
    return if (ColorUtils.calculateLuminance(color) < 0.3) ColorUtils.blendARGB(color, Color.WHITE, 0.5f) else color
}
