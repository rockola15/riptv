package com.riptv.tv

/**
 * [number] es la posición del canal en la lista (1, 2, 3…). [key] identifica al canal para favoritos
 * y ocultos: no usa la URL porque los proveedores le añaden tokens que cambian en cada descarga.
 */
data class Channel(
    val name: String,
    val group: String,
    val url: String,
    val number: Int = 0,
    val logo: String = "",
) {
    val key: String = "$group|$name"
}
