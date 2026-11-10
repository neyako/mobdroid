package app.mobdroid.chat

import kotlin.test.Test
import kotlin.test.assertEquals

class TwitchAuthTest {
    @Test
    fun `the token is taken from the redirect only when the state matches`() {
        val fragment = "access_token=abc123&scope=user%3Aread%3Achat&state=s1&token_type=bearer"
        assertEquals("abc123", twitchAccessToken(fragment, "s1"))
        assertEquals(null, twitchAccessToken(fragment, "other"))
        assertEquals(null, twitchAccessToken("error=access_denied&state=s1", "s1"))
        assertEquals(null, twitchAccessToken(null, "s1"))
    }

    @Test
    fun `no session token in the cookies means not logged in`() {
        assertEquals(null, kickSessionToken("cf_clearance=abc; other=1"))
        assertEquals(null, kickSessionToken(null))
    }
}
