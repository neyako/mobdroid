package app.mobdroid.chat

import android.net.Uri
import app.mobdroid.settings.TwitchAccount
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request

const val TWITCH_CLIENT_ID = "qv6bnocuwapqigeqjoamfhif0cv2xn"
const val TWITCH_REDIRECT_HOST = "localhost"

fun twitchAuthUrl(state: String): String = Uri.parse("https://id.twitch.tv/oauth2/authorize").buildUpon()
    .appendQueryParameter("client_id", TWITCH_CLIENT_ID)
    .appendQueryParameter("force_verify", "true")
    .appendQueryParameter("redirect_uri", "https://$TWITCH_REDIRECT_HOST")
    .appendQueryParameter("response_type", "token")
    .appendQueryParameter("scope", "user:read:chat user:write:chat")
    .appendQueryParameter("state", state)
    .build().toString()

fun twitchAccessToken(fragment: String?, state: String): String? {
    val parameters = fragment.orEmpty().split('&').mapNotNull {
        val index = it.indexOf('=')
        if (index < 0) null else it.substring(0, index) to it.substring(index + 1)
    }.toMap()
    return parameters["access_token"]?.takeIf { it.isNotEmpty() && parameters["state"] == state }
}

fun validateTwitchToken(http: OkHttpClient, accessToken: String): TwitchAccount? = runCatching {
    val request = Request.Builder().url("https://id.twitch.tv/oauth2/validate").header("Authorization", "OAuth $accessToken").build()
    http.newCall(request).execute().use { response ->
        if (!response.isSuccessful) return null
        val body = Json.parseToJsonElement(response.body.string()).jsonObject
        TwitchAccount(
            login = body.getValue("login").jsonPrimitive.content,
            userId = body.getValue("user_id").jsonPrimitive.content,
            accessToken = accessToken,
        )
    }
}.getOrNull()
