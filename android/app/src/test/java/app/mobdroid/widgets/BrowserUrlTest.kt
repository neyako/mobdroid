package app.mobdroid.widgets

import kotlin.test.Test
import kotlin.test.assertEquals

class BrowserUrlTest {
    @Test
    fun anAddressWithoutASchemeIsLoadedOverHttps() {
        assertEquals("https://streamelements.com/overlay/1", browserUrl(" streamelements.com/overlay/1 "))
        assertEquals("http://10.0.0.5:8080/alerts", browserUrl("http://10.0.0.5:8080/alerts"))
        assertEquals("about:blank", browserUrl("  "))
    }
}
