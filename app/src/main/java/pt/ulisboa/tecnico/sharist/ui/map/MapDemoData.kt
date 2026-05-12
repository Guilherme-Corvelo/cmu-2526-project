package pt.ulisboa.tecnico.sharist.ui.map

import org.osmdroid.util.GeoPoint

object MapDemoData {
    val lisbon = GeoPoint(38.736946, -9.142685)

    private val routePoints = mapOf(
        "IST Alameda" to GeoPoint(38.7369, -9.1387),
        "Saldanha" to GeoPoint(38.7355, -9.1455),
        "Campo Grande" to GeoPoint(38.7602, -9.1584),
        "Oriente" to GeoPoint(38.7677, -9.0997)
    )

    fun pointFor(name: String): GeoPoint? = routePoints[name]
    fun allPoints(): Map<String, GeoPoint> = routePoints
}
