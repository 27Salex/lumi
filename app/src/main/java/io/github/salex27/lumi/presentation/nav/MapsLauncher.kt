package io.github.salex27.lumi.presentation.nav

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import io.github.salex27.lumi.domain.model.NavDestination

/**
 * "Directions" in the maps app the user chose (Google Maps, Waze, Petal, HERE, OsmAnd…).
 * Google Maps and Waze open navigation directly; the rest get a standard `geo:` (they show the place with their
 * route button). With "Ask" the system chooser appears.
 */
object MapsLauncher {

    const val GOOGLE_MAPS = "com.google.android.apps.maps"
    const val WAZE = "com.waze"

    data class MapsApp(val packageName: String, val label: String)

    /** Installed apps that understand `geo:` (needs the manifest `<queries>` entry). */
    fun installedApps(context: Context): List<MapsApp> {
        val pm = context.packageManager
        val probe = Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=Madrid"))
        return pm.queryIntentActivities(probe, PackageManager.MATCH_DEFAULT_ONLY)
            .map { MapsApp(it.activityInfo.packageName, it.loadLabel(pm).toString()) }
            .distinctBy { it.packageName }
            .sortedWith(compareBy({ it.packageName != GOOGLE_MAPS && it.packageName != WAZE }, { it.label }))
    }

    /** Intent ready to launch. Empty or missing [packageName] → system chooser. */
    fun intent(context: Context, destination: NavDestination, packageName: String): Intent {
        val installed = packageName.isNotBlank() && runCatching { context.packageManager.getPackageInfo(packageName, 0) }.isSuccess
        val target = if (installed) packageName else ""
        val base = when (target) {
            GOOGLE_MAPS -> Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=${place(destination)}")).setPackage(GOOGLE_MAPS)
            WAZE -> Intent(Intent.ACTION_VIEW, Uri.parse(wazeUrl(destination))).setPackage(WAZE)
            "" -> Intent(Intent.ACTION_VIEW, Uri.parse(geoUri(destination)))
            else -> Intent(Intent.ACTION_VIEW, Uri.parse(geoUri(destination))).setPackage(target)
        }
        base.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return if (target.isEmpty()) Intent.createChooser(base, context.getString(io.github.salex27.lumi.R.string.maps_chooser_title)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) else base
    }

    fun open(context: Context, destination: NavDestination, packageName: String): Boolean =
        runCatching { context.startActivity(intent(context, destination, packageName)) }.isSuccess

    private fun place(d: NavDestination) =
        if (d.lat != null && d.lng != null) "${d.lat},${d.lng}" else Uri.encode(d.query)

    private fun wazeUrl(d: NavDestination) =
        if (d.lat != null && d.lng != null) "https://waze.com/ul?ll=${d.lat},${d.lng}&navigate=yes"
        else "https://waze.com/ul?q=${Uri.encode(d.query)}&navigate=yes"

    private fun geoUri(d: NavDestination) =
        if (d.lat != null && d.lng != null) "geo:${d.lat},${d.lng}?q=${d.lat},${d.lng}(${Uri.encode(d.label)})"
        else "geo:0,0?q=${Uri.encode(d.query)}"
}
