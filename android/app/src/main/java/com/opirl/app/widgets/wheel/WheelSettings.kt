package com.opirl.app.widgets.wheel

import kotlinx.serialization.Serializable

@Serializable
data class WheelOption(val text: String = "", val weight: Int = 1)

@Serializable
data class WheelSettings(
    val advanced: Boolean = false,
    val onlyShowWhenSpinning: Boolean = false,
    val options: List<WheelOption> = listOf(WheelOption("Yes"), WheelOption("No"), WheelOption("Later"), WheelOption("Spin again")),
)

val WHEEL_WEIGHTS = listOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 20, 40, 60, 80, 100)

fun optionsFromText(text: String): List<WheelOption> = text.trim().split('\n').map { WheelOption(it.trim()) }

fun optionsToText(options: List<WheelOption>): String = options.joinToString("\n") { it.text }

fun totalWeight(options: List<WheelOption>): Int = maxOf(options.sumOf { it.weight }, 1)

fun weightPercent(options: List<WheelOption>, weight: Int): Int = 100 * weight / totalWeight(options)

fun optionAtPointer(options: List<WheelOption>, rotation: Double): WheelOption? {
    val total = options.sumOf { it.weight }
    if (total <= 0) return null
    val pointer = ((90 - rotation) % 360 + 360) % 360
    var end = 0.0
    for (option in options) {
        end += 360.0 * option.weight / total
        if (pointer < end) return option
    }
    return options.last()
}
