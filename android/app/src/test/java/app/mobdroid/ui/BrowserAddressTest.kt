package app.mobdroid.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class BrowserAddressTest {
    @Test
    fun addressesGetASchemeAndOtherTextIsSearched() {
        assertEquals("https://twitch.tv/dashboard", browserAddress(" twitch.tv/dashboard "))
        assertEquals("http://example.com/a b", browserAddress("http://example.com/a b"))
        assertEquals("http://10.60.2.113:8000/overlay.html", browserAddress("10.60.2.113:8000/overlay.html"))
        assertEquals("http://localhost:3000", browserAddress("localhost:3000"))
        assertEquals("https://www.google.com/search?q=obs+scene+%26+mic", browserAddress("obs scene & mic"))
        assertEquals("https://www.google.com/search?q=what+is+1.5x+zoom", browserAddress("what is 1.5x zoom"))
        assertEquals("https://www.google.com/search?q=moblin", browserAddress("moblin"))
        assertEquals("about:blank", browserAddress("  "))
    }

    @Test
    fun theAddressFieldHidesTheSecureSchemeOnly() {
        assertEquals("twitch.tv/dashboard", shortAddress("https://www.twitch.tv/dashboard/"))
        assertEquals("http://10.60.2.113:8000", shortAddress("http://10.60.2.113:8000/"))
    }
}
