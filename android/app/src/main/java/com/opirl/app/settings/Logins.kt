package com.opirl.app.settings

import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class TwitchAccount(val login: String, val userId: String, val accessToken: String, val scopes: List<String> = emptyList())

@Serializable
data class KickAccount(val username: String, val sessionToken: String)

@Serializable
data class Logins(
    val twitch: TwitchAccount? = null,
    val kick: KickAccount? = null,
    val youtube: Boolean = false,
    val elevenLabsKey: String = "",
    val ttsMonsterToken: String = "",
)

class LoginStore(private val file: File) {
    private val json = Json { ignoreUnknownKeys = true }
    private val _logins = MutableStateFlow(
        runCatching { json.decodeFromString(Logins.serializer(), file.readText()) }.getOrDefault(Logins()),
    )
    val logins: StateFlow<Logins> = _logins.asStateFlow()

    @Synchronized
    fun update(transform: (Logins) -> Logins) {
        val next = transform(_logins.value)
        _logins.value = next
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeText(json.encodeToString(Logins.serializer(), next))
        tmp.renameTo(file)
    }
}
