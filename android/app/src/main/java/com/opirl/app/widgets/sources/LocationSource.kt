package com.opirl.app.widgets.sources

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class LocationSource(private val context: Context) {
    private val manager = context.getSystemService(LocationManager::class.java)
    private val _location = MutableStateFlow<Location?>(null)
    val location: StateFlow<Location?> = _location.asStateFlow()
    private var listener: LocationListener? = null

    val allowed: Boolean
        get() = context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    @Synchronized
    @SuppressLint("MissingPermission")
    fun setEnabled(enabled: Boolean) {
        val wanted = enabled && allowed
        if (wanted == (listener != null)) return
        listener?.let(manager::removeUpdates)
        listener = null
        if (!wanted) return
        val providers = manager.allProviders
        val chosen = if (Build.VERSION.SDK_INT >= 31 && LocationManager.FUSED_PROVIDER in providers) {
            listOf(LocationManager.FUSED_PROVIDER)
        } else {
            listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).filter { it in providers }
        }
        val next = object : LocationListener {
            override fun onLocationChanged(fix: Location) {
                val current = _location.value
                if (current == null || fix.provider != LocationManager.NETWORK_PROVIDER || fix.time - current.time > 10_000) {
                    _location.value = fix
                }
            }

            override fun onProviderEnabled(provider: String) = Unit

            override fun onProviderDisabled(provider: String) = Unit

            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
        }
        listener = next
        for (provider in chosen) {
            runCatching { manager.requestLocationUpdates(provider, 1000, 0f, next, Looper.getMainLooper()) }
        }
    }
}
