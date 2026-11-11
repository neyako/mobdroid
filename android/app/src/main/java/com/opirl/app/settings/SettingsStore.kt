package com.opirl.app.settings

import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

class SettingsStore(private val file: File, scope: CoroutineScope) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        coerceInputValues = true
    }
    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    init {
        _settings.drop(1).debounce(300).onEach(::write).launchIn(CoroutineScope(scope.coroutineContext + Dispatchers.IO))
    }

    fun update(transform: (AppSettings) -> AppSettings) = _settings.update(transform)

    fun updateProfile(profile: StreamProfile) = update { s ->
        s.copy(profiles = s.profiles.map { if (it.id == profile.id) profile else it })
    }

    fun exportJson(): String = json.encodeToString(AppSettings.serializer(), _settings.value)

    fun importJson(text: String): Boolean = runCatching { _settings.value = decode(text) }.isSuccess

    private fun load(): AppSettings = runCatching {
        decode(file.readText())
    }.getOrDefault(AppSettings())

    internal fun decode(text: String): AppSettings {
        val tree = json.parseToJsonElement(text).jsonObject
        val decoded = json.decodeFromJsonElement(AppSettings.serializer(), tree)
        val setUp = if ("setupDone" in tree) decoded else decoded.copy(setupDone = true)
        val oldMic = (tree["mic"] as? JsonPrimitive)?.content
        val settings = if ("micKey" in tree) setUp else setUp.copy(micKey = OLD_MICS[oldMic])
        return if (settings.profiles.isEmpty()) settings.copy(profiles = AppSettings().profiles) else settings
    }

    private companion object {
        val OLD_MICS = mapOf("BUILT_IN" to "PHONE", "USB" to "USB", "WIRED" to "WIRED")
    }

    private fun write(settings: AppSettings) {
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeText(json.encodeToString(AppSettings.serializer(), settings))
        tmp.renameTo(file)
    }
}
