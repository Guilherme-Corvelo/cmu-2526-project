package pt.ulisboa.tecnico.sharist.ui.map

import org.osmdroid.util.GeoPoint

object MapDemoData {
    val lisbon = GeoPoint(38.736946, -9.142685)

    private val routePoints = mapOf(
        "IST Alameda" to GeoPoint(38.7369, -9.1387),
        "Saldanha" to GeoPoint(38.7355, -9.1455),
        "Campo Grande" to GeoPoint(38.7602, -9.1584),
        "Oriente" to GeoPoint(38.7677, -9.0997),
        "IST Taguspark" to GeoPoint(38.7372, -9.3023),
        "Marquês de Pombal" to GeoPoint(38.7253, -9.1500),
        "Cais do Sodré" to GeoPoint(38.7061, -9.1450),
        "Aeroporto" to GeoPoint(38.7742, -9.1342),
        "Belém" to GeoPoint(38.6971, -9.2064),
        "Entrecampos" to GeoPoint(38.7479, -9.1481),
        "Sete Rios" to GeoPoint(38.7402, -9.1661),
        "Rossio" to GeoPoint(38.7138, -9.1394)
    )

    fun pointFor(name: String): GeoPoint? = routePoints[name]
    fun allPoints(): Map<String, GeoPoint> = routePoints
}
