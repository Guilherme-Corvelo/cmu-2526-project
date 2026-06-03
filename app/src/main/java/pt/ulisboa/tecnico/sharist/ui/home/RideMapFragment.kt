package pt.ulisboa.tecnico.sharist.ui.home

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Polygon
import org.osmdroid.views.overlay.Marker
import pt.ulisboa.tecnico.sharist.R
import pt.ulisboa.tecnico.sharist.SharISTApp
import pt.ulisboa.tecnico.sharist.data.model.FavoriteLocation
import pt.ulisboa.tecnico.sharist.data.model.Ride
import pt.ulisboa.tecnico.sharist.data.model.RideRequest
import pt.ulisboa.tecnico.sharist.ui.map.MapDemoData
import android.graphics.Color
import org.osmdroid.util.BoundingBox

class RideMapFragment : Fragment() {

    private val viewModel: RideMapViewModel by viewModels {
        object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val app = requireActivity().application as SharISTApp
                @Suppress("UNCHECKED_CAST")
                return RideMapViewModel(
                    app.rideRepository,
                    app.requestRepository,
                    app.favoriteLocationRepository,
                    app.userRepository.currentUid
                ) as T
            }
        }
    }

    private lateinit var mapView: MapView
    
    // In a real app, these would come from ViewModels
    private var displayedRides: List<Ride> = emptyList()
    private var displayedRequests: List<RideRequest> = emptyList()
    private var userFavorites: List<FavoriteLocation> = emptyList()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_ride_map, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        Configuration.getInstance().setUserAgentValue(requireContext().packageName)
        Configuration.getInstance().load(requireContext(), requireContext().getSharedPreferences("osmdroid", 0))

        mapView = view.findViewById(R.id.ride_map)
        mapView.setTileSource(TileSourceFactory.MAPNIK)
        mapView.setMultiTouchControls(true)
        mapView.isHorizontalMapRepetitionEnabled = false
        mapView.isVerticalMapRepetitionEnabled = false
        mapView.setScrollableAreaLimitDouble(BoundingBox(85.0, 180.0, -85.0, -180.0))
        mapView.minZoomLevel = 3.0

        mapView.controller.setZoom(13.0)
        mapView.controller.setCenter(MapDemoData.lisbon)

        observeViewModel()

        val etSearch = view.findViewById<EditText>(R.id.et_location_search)
        view.findViewById<Button>(R.id.btn_search).setOnClickListener {
            val query = etSearch.text.toString().trim().lowercase()
            val allPoints = mutableMapOf<String, GeoPoint>()
            displayedRides.forEach { allPoints[it.origin.lowercase()] = GeoPoint(it.originLat, it.originLng) }
            displayedRequests.forEach { allPoints[it.origin.lowercase()] = GeoPoint(it.originLat, it.originLng) }
            userFavorites.forEach { allPoints[it.name.lowercase()] = GeoPoint(it.latitude, it.longitude) }

            val point = allPoints[query]
            if (point != null) {
                mapView.controller.animateTo(point)
                mapView.controller.setZoom(15.5)
            } else {
                Toast.makeText(requireContext(), "Location not found on map", Toast.LENGTH_SHORT).show()
            }
        }

        // Use a MapEventsOverlay to handle long press properly in OSMDroid
        val eventsOverlay = org.osmdroid.views.overlay.MapEventsOverlay(object : org.osmdroid.events.MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: org.osmdroid.util.GeoPoint?): Boolean = false
            override fun longPressHelper(p: org.osmdroid.util.GeoPoint?): Boolean {
                p?.let { showAddFavoriteDialog(it) }
                return true
            }
        })
        mapView.overlays.add(eventsOverlay)

        view.findViewById<Button>(R.id.btn_center_lisbon).setOnClickListener {
            mapView.controller.animateTo(MapDemoData.lisbon)
            mapView.controller.setZoom(13.0)
        }
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.rides.collect { rides ->
                        displayedRides = rides
                        addRouteMarkers()
                    }
                }
                launch {
                    viewModel.requests.collect { requests ->
                        displayedRequests = requests
                        addRouteMarkers()
                    }
                }
                launch {
                    viewModel.favorites.collect { favorites ->
                        userFavorites = favorites
                        addRouteMarkers()
                    }
                }
            }
        }
    }

    private fun addRouteMarkers() {
        mapView.overlays.clear()
        
        // Visualize Favorites
        userFavorites.forEach { fav ->
            val point = GeoPoint(fav.latitude, fav.longitude)
            val marker = Marker(mapView).apply {
                position = point
                title = "★ Favorite: ${fav.name}"
                subDescription = fav.address
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                icon = androidx.core.content.res.ResourcesCompat.getDrawable(resources, R.drawable.ic_favorite_24, null)?.apply {
                    setTint(Color.MAGENTA)
                }
            }
            mapView.overlays.add(marker)
        }

        // Visualize Rides
        displayedRides.forEach { ride ->
            addPointWithRadius(
                GeoPoint(ride.originLat, ride.originLng),
                500.0, // Default radius if not in model for Ride
                "Ride Start: ${ride.origin}",
                Color.BLUE
            )
        }

        // Visualize Ride Requests with their specific radii
        displayedRequests.forEach { req ->
            addPointWithRadius(
                GeoPoint(req.originLat, req.originLng),
                req.originRadius,
                "Request Origin: ${req.origin}",
                Color.GREEN
            )
            addPointWithRadius(
                GeoPoint(req.destinationLat, req.destinationLng),
                req.destinationRadius,
                "Request Destination: ${req.destination}",
                Color.RED
            )
        }

        mapView.invalidate()
    }

    private fun showAddFavoriteDialog(point: GeoPoint) {
        val editText = EditText(requireContext()).apply {
            hint = "Home, Work, etc."
        }
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle("Add Favorite Location")
            .setMessage("Enter a name for this location:")
            .setView(editText)
            .setPositiveButton("Save") { _, _ ->
                val name = editText.text.toString()
                if (name.isNotBlank()) {
                    viewModel.addFavorite(name, "Map Location", point.latitude, point.longitude)
                    Toast.makeText(requireContext(), "Favorite added!", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun addPointWithRadius(center: GeoPoint, radiusMeters: Double, title: String, color: Int) {
        val marker = Marker(mapView).apply {
            position = center
            this.title = title
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
        }
        mapView.overlays.add(marker)

        val circle = Polygon(mapView).apply {
            points = Polygon.pointsAsCircle(center, radiusMeters)
            fillPaint.color = color
            fillPaint.alpha = 30
            outlinePaint.color = color
            outlinePaint.strokeWidth = 2f
        }
        mapView.overlays.add(circle)
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
