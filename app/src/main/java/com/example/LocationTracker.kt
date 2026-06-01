package com.example

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.util.Log
import com.google.android.gms.location.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

class LocationTracker(private val context: Context) {
    private val client = LocationServices.getFusedLocationProviderClient(context)
    private val TAG = "LocationTracker"

    @SuppressLint("MissingPermission")
    fun getLocationFlow(intervalMs: Long = 10000L): Flow<Location> = callbackFlow {
        Log.d(TAG, "Requesting continuous location updates at interval ${intervalMs}ms")
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, intervalMs)
            .setMinUpdateIntervalMillis(intervalMs / 2)
            .build()
        
        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let {
                    Log.d(TAG, "New GPS location received: Flat=${it.latitude}, Lng=${it.longitude}")
                    trySend(it)
                }
            }
        }

        try {
            client.requestLocationUpdates(request, callback, android.os.Looper.getMainLooper())
        } catch (e: Exception) {
            Log.e(TAG, "Error starting location updates", e)
            close(e)
        }

        awaitClose {
            Log.d(TAG, "Closing location flow and removing updates")
            client.removeLocationUpdates(callback)
        }
    }
}
