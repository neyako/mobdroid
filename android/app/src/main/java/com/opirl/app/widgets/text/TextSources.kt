package com.opirl.app.widgets.text

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Address
import android.location.Geocoder
import android.location.Location
import android.util.Log
import com.opirl.app.settings.AppSettings
import com.opirl.app.settings.Scene
import com.opirl.app.settings.Widget
import com.opirl.app.settings.WidgetType
import com.opirl.app.stream.LiveState
import com.opirl.app.widgets.WidgetRuntime
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Request

data class TextDemand(
    val weather: Boolean = false,
    val geography: Boolean = false,
    val gForce: Boolean = false,
    val stats: Boolean = false,
    val muted: Boolean = false,
    val resetWhenGoingLive: Boolean = false,
)

fun placedTextWidgets(settings: AppSettings): List<Widget> {
    val found = LinkedHashMap<String, Widget>()
    val seen = HashSet<String>()
    fun text(id: String?) = settings.widgets.firstOrNull { it.id == id && it.type == WidgetType.TEXT }
    fun visit(scene: Scene) {
        if (!seen.add(scene.id)) return
        for (placement in scene.widgets) {
            if (!placement.enabled) continue
            val widget = settings.widgets.firstOrNull { it.id == placement.widgetId } ?: continue
            when (widget.type) {
                WidgetType.TEXT -> found[widget.id] = widget
                WidgetType.SCENE -> settings.scenes.firstOrNull { it.id == widget.sceneId }?.let(::visit)
                WidgetType.SLIDESHOW -> widget.slideshow.slides.forEach { slide -> text(slide.widgetId)?.let { found[it.id] = it } }
                else -> Unit
            }
        }
    }
    settings.selectedScene?.let(::visit)
    return found.values.toList()
}

fun textDemand(settings: AppSettings): TextDemand {
    val widgets = placedTextWidgets(settings)
    val parts = widgets.map { parseTextFormat(it.text) }
    return TextDemand(
        weather = parts.any { it.needsWeather },
        geography = parts.any { it.needsGeography },
        gForce = parts.any { it.needsGForce },
        stats = parts.any { it.needsStats },
        muted = parts.any { it.needsMuted },
        resetWhenGoingLive = parts.indices.any { parts[it].needsTrip && widgets[it].textOptions.resetWhenGoingLive },
    )
}

fun parseWeather(json: String): Weather? {
    val current = Json.parseToJsonElement(json).jsonObject["current"]?.jsonObject ?: return null
    val number = { key: String -> current[key]?.jsonPrimitive?.doubleOrNull }
    val temperature = number("temperature_2m") ?: return null
    return Weather(
        code = number("weather_code")?.toInt() ?: return null,
        temperature = temperature,
        feelsLike = number("apparent_temperature") ?: temperature,
        wind = number("wind_speed_10m") ?: return null,
        gust = number("wind_gusts_10m"),
    )
}

fun weatherUrl(latitude: Double, longitude: Double): String =
    "https://api.open-meteo.com/v1/forecast?latitude=${"%.4f".format(Locale.ROOT, latitude)}" +
        "&longitude=${"%.4f".format(Locale.ROOT, longitude)}" +
        "&current=temperature_2m,apparent_temperature,weather_code,wind_speed_10m,wind_gusts_10m&wind_speed_unit=ms"

internal fun WidgetRuntime.textSources(): TextSources = synchronized(this) { state("text-sources") { TextSources(this) } }

internal class TextSources(private val runtime: WidgetRuntime) {
    private val trip = Trip(System.currentTimeMillis())
    private var lastFix = 0L
    val gForce = GForceTracker()

    @Volatile
    var weather: Weather? = null
        private set

    @Volatile
    var place: Place? = null
        private set

    private val scope = runtime.scope
    private val demand = runtime.store.settings.map(::textDemand).distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, TextDemand())

    init {
        val wanted = { select: (TextDemand) -> Boolean ->
            combine(runtime.active, demand.map(select)) { active, wanted -> active && wanted }.distinctUntilChanged()
        }
        scope.launch { wanted { it.weather }.collectLatest { if (it) pollWeather() } }
        scope.launch { wanted { it.geography }.collectLatest { if (it) pollGeography() } }
        scope.launch { wanted { it.gForce }.collectLatest { if (it) listenGForce() } }
        scope.launch { runtime.stats.collect { if (demand.value.stats) runtime.invalidate() } }
        scope.launch {
            var wasLive = false
            runtime.ui.map { it.muted to (it.live != LiveState.OFFLINE) }.distinctUntilChanged().collect { (_, live) ->
                if (live && !wasLive && demand.value.resetWhenGoingLive) resetTrip(System.currentTimeMillis())
                wasLive = live
                if (demand.value.stats || demand.value.muted || demand.value.resetWhenGoingLive) runtime.invalidate()
            }
        }
    }

    @Synchronized
    fun track(fix: Fix, id: Long) {
        if (id == lastFix) return
        lastFix = id
        trip.update(fix)
    }

    @Synchronized
    fun tripSnapshot(now: Long) = trip.snapshot(now)

    @Synchronized
    fun splitTrip() = trip.split()

    @Synchronized
    fun resetTrip(now: Long) = trip.reset(now)

    private suspend fun pollWeather() {
        var wait = 5_000L
        while (true) {
            delay(wait)
            val location = runtime.location.value ?: continue
            val fetched = withContext(Dispatchers.IO) { fetchWeather(location) }
            if (fetched != null) {
                weather = fetched
                wait = 600_000
                runtime.invalidate()
            }
        }
    }

    private fun fetchWeather(location: Location): Weather? = runCatching {
        val request = Request.Builder().url(weatherUrl(location.latitude, location.longitude)).build()
        runtime.http.newCall(request).execute().use { response ->
            if (response.isSuccessful) response.body.string().let(::parseWeather) else null
        }
    }.onFailure { Log.w(TAG, "weather failed", it) }.getOrNull()

    private suspend fun pollGeography() {
        var wait = 5_000L
        var geocoded: Location? = null
        val geocoder = Geocoder(runtime.context, Locale.getDefault())
        while (true) {
            delay(wait)
            val location = runtime.location.value
            val previous = geocoded
            if (location != null && (previous == null || previous.distanceTo(location) > 1000)) {
                val found = withContext(Dispatchers.IO) { geocode(geocoder, location) }
                if (found != null) {
                    place = found
                    geocoded = location
                    runtime.invalidate()
                }
            }
            if (place != null) wait = 300_000
        }
    }

    @Suppress("DEPRECATION")
    private fun geocode(geocoder: Geocoder, location: Location): Place? = runCatching {
        geocoder.getFromLocation(location.latitude, location.longitude, 1)?.firstOrNull()?.toPlace()
    }.onFailure { Log.w(TAG, "geocoder failed", it) }.getOrNull()

    private fun Address.toPlace() = Place(countryName, countryCode, adminArea, subAdminArea, locality, subLocality)

    private suspend fun listenGForce() {
        val manager = runtime.context.getSystemService(SensorManager::class.java)
        val sensor = manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                gForce.onAcceleration(
                    event.values[0] / SensorManager.GRAVITY_EARTH.toDouble(),
                    event.values[1] / SensorManager.GRAVITY_EARTH.toDouble(),
                    event.values[2] / SensorManager.GRAVITY_EARTH.toDouble(),
                    event.timestamp,
                )
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        gForce.restart()
        manager.registerListener(listener, sensor, 100_000)
        try {
            awaitCancellation()
        } finally {
            manager.unregisterListener(listener)
        }
    }

    private companion object {
        const val TAG = "TextSources"
    }
}
