package io.github.salex27.lumi.domain.model

/**
 * Destination for "Directions". Has coordinates when it is a saved place; otherwise a search query
 * (a meeting's address, "Calle Mayor 5"…). Opened in the maps app the user picked.
 */
data class NavDestination(
    val label: String,
    val query: String,
    val lat: Double? = null,
    val lng: Double? = null
)
