package pt.ulisboa.tecnico.sharist.ui.home

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.fragment.app.Fragment
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import pt.ulisboa.tecnico.sharist.R

class RideMapFragment : Fragment() {

    private lateinit var mapView: MapView

    private val lisbon = GeoPoint(38.736946, -9.142685)

    private val routePoints = mapOf(
        "IST Alameda" to GeoPoint(38.7369, -9.1387),
        "Saldanha" to GeoPoint(38.7355, -9.1455),
        "Campo Grande" to GeoPoint(38.7602, -9.1584),
        "Oriente" to GeoPoint(38.7677, -9.0997)
    )

    private val favorites = setOf("IST Alameda", "Campo Grande")

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_ride_map, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        Configuration.getInstance().load(requireContext(), requireContext().getSharedPreferences("osmdroid", 0))

        mapView = view.findViewById(R.id.ride_map)
        mapView.setTileSource(TileSourceFactory.MAPNIK)
        mapView.setMultiTouchControls(true)
        mapView.controller.setZoom(13.0)
        mapView.controller.setCenter(lisbon)

        addRouteMarkers()

        val etSearch = view.findViewById<EditText>(R.id.et_location_search)
        view.findViewById<Button>(R.id.btn_search).setOnClickListener {
            val query = etSearch.text.toString().trim()
            val point = routePoints[query]
            if (point != null) {
                mapView.controller.animateTo(point)
                mapView.controller.setZoom(15.5)
            } else {
                Toast.makeText(requireContext(), "Unknown location in demo data", Toast.LENGTH_SHORT).show()
            }
        }

        view.findViewById<Button>(R.id.btn_center_lisbon).setOnClickListener {
            mapView.controller.animateTo(lisbon)
            mapView.controller.setZoom(13.0)
        }
    }

    private fun addRouteMarkers() {
        mapView.overlays.clear()
        routePoints.forEach { (name, point) ->
            val marker = Marker(mapView).apply {
                position = point
                title = if (favorites.contains(name)) "★ Favorite: $name" else name
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            }
            mapView.overlays.add(marker)
        }
        mapView.invalidate()
    }

    override fun onResume() {
        super.onResume()
        mapView.onResume()
    }

    override fun onPause() {
        mapView.onPause()
        super.onPause()
    }
}
