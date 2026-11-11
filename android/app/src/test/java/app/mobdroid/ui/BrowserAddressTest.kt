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
        assertEquals("http://10.60.2.113:8000?view=chat", browserAddress("10.60.2.113:8000?view=chat"))
        assertEquals("HTTPS://Example.com", browserAddress("HTTPS://Example.com"))
        assertEquals("about:blank", browserAddress("about:blank"))
        assertEquals("https://www.google.com/search?q=obs+scene+%26+mic", browserAddress("obs scene & mic"))
        assertEquals("https://www.google.com/search?q=what+is+1.5x+zoom", browserAddress("what is 1.5x zoom"))
        assertEquals("https://www.google.com/search?q=moblin", browserAddress("moblin"))
        assertEquals("about:blank", browserAddress("  "))
    }

    @Test
    fun onlyWebSchemesAreLoaded() {
        assertEquals("https://www.google.com/search?q=chrome%3A%2F%2Fcrash", browserAddress("chrome://crash"))
        assertEquals("https://www.google.com/search?q=file%3A%2F%2F%2Fdata%2Fx", browserAddress("file:///data/x"))
        assertEquals("https://www.google.com/search?q=javascript%3Aalert%281%29", browserAddress("javascript:alert(1)"))
        assertEquals("https://www.google.com/search?q=about%3Acrash", browserAddress("about:crash"))
    }

    @Test
    fun theAddressFieldHidesTheSecureSchemeOnly() {
        assertEquals("www.twitch.tv/dashboard/", shortAddress("https://www.twitch.tv/dashboard/"))
        assertEquals("http://10.60.2.113:8000/", shortAddress("http://10.60.2.113:8000/"))
    }
}
