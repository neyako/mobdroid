package com.opirl.app.ui

import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals

class FormatTest {
    @Test
    fun numbersKeepTheDecimalPointInACommaLocale() {
        val before = Locale.getDefault()
        Locale.setDefault(Locale.forLanguageTag("vi"))
        try {
            assertEquals("6", formatMbps(6000))
            assertEquals("2.5", formatMbps(2500))
            assertEquals("6.1 Mbps", formatBitrate(6_100_000))
            assertEquals("0.5x", formatZoom(0.5f))
        } finally {
            Locale.setDefault(before)
        }
    }
}
