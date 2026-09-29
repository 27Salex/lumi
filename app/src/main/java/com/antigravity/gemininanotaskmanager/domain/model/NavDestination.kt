package com.antigravity.gemininanotaskmanager.domain.model

/**
 * Destino para «Cómo llegar». Con coordenadas si es un lugar guardado; si no, un texto de búsqueda
 * (dirección de una reunión, «Calle Mayor 5»…). Lo abre la app de mapas que elija el usuario.
 */
data class NavDestination(
    val label: String,
    val query: String,
    val lat: Double? = null,
    val lng: Double? = null
)
