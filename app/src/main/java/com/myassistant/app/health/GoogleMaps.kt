package com.myassistant.app.health

import android.content.Context
import android.content.Intent
import android.net.Uri
import java.util.Locale

/** Opens directions in the Google Maps app (or the browser if Maps isn't installed). */
object GoogleMaps {
    private const val MAPS = "com.google.android.apps.maps"

    private fun ll(lat: Double, lng: Double) = String.format(Locale.US, "%.6f,%.6f", lat, lng)

    private fun open(ctx: Context, uri: Uri): Boolean {
        val i = Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { ctx.startActivity(Intent(i).setPackage(MAPS)); true }.getOrElse {
            runCatching { ctx.startActivity(i); true }.getOrDefault(false)
        }
    }

    /** Directions from where you are now to [destination] (a name or address); [start] begins turn-by-turn. */
    fun directions(ctx: Context, destination: String, mode: String, start: Boolean): Boolean {
        val m = when (mode) { "walking" -> "w"; "bicycling" -> "b"; "two-wheeler" -> "l"; else -> "d" }
        if (start) return open(ctx, Uri.parse("google.navigation:q=" + Uri.encode(destination) + "&mode=$m"))
        return open(ctx, Uri.parse("https://www.google.com/maps/dir/").buildUpon()
            .appendQueryParameter("api", "1")
            .appendQueryParameter("destination", destination)
            .appendQueryParameter("travelmode", if (mode == "transit") "transit" else mode.ifBlank { "driving" })
            .build())
    }
}
