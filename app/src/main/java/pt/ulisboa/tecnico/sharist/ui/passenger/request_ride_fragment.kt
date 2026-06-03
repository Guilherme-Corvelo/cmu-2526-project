package pt.ulisboa.tecnico.sharist.ui.passenger

import android.app.TimePickerDialog
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.fragment.app.Fragment
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import androidx.navigation.fragment.findNavController
import pt.ulisboa.tecnico.sharist.R
import pt.ulisboa.tecnico.sharist.SharISTApp
import pt.ulisboa.tecnico.sharist.data.model.*
import pt.ulisboa.tecnico.sharist.data.repository.RideRequestRepository
import pt.ulisboa.tecnico.sharist.ui.map.MapDemoData
import pt.ulisboa.tecnico.sharist.utils.PriceCalculator
import pt.ulisboa.tecnico.sharist.utils.WeatherService
import pt.ulisboa.tecnico.sharist.utils.WeatherWarning
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

class RequestRideFragment : Fragment() {
    private lateinit var mapView: MapView
    private lateinit var requestRepo: RideRequestRepository
    private var userFavorites: List<FavoriteLocation> = emptyList()

    private data class LocationItem(val name: String, val isFavorite: Boolean) {
        override fun toString(): String = name
    }

    private inner class LocationAdapter(context: android.content.Context, items: MutableList<LocationItem>) :
        ArrayAdapter<LocationItem>(context, R.layout.item_dropdown_location, items) {

        private var allItems: List<LocationItem> = ArrayList(items)

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: LayoutInflater.from(context).inflate(R.layout.item_dropdown_location, parent, false)
            val item = getItem(position)
            val tvName = view.findViewById<TextView>(R.id.tv_location_name)
            val ivStar = view.findViewById<ImageView>(R.id.iv_favorite_star)

            tvName.text = item?.name
            ivStar.visibility = if (item?.isFavorite == true) View.VISIBLE else View.GONE
            ivStar.setColorFilter(android.graphics.Color.MAGENTA)

            return view
        }

        fun updateItems(newItems: List<LocationItem>) {
            clear()
            addAll(newItems)
            allItems = ArrayList(newItems)
            notifyDataSetChanged()
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_request_ride, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val app = requireActivity().application as SharISTApp
        requestRepo = app.requestRepository
        val session = app.sessionManager

        // View Initializations
        val etOrigin = view.findViewById<AutoCompleteTextView>(R.id.et_origin)
        val etDestination = view.findViewById<AutoCompleteTextView>(R.id.et_destination)
        val tvEstimatedPrice = view.findViewById<TextView>(R.id.tv_estimated_price)
        val tvSelectedTime = view.findViewById<TextView>(R.id.tv_selected_time)
        val tvMapStatus = view.findViewById<TextView>(R.id.tv_map_status)
        val spinnerWeather = view.findViewById<Spinner>(R.id.spinner_weather)
        val layoutThreshold = view.findViewById<View>(R.id.layout_threshold)
        val etThreshold = view.findViewById<EditText>(R.id.et_threshold)
        val cvWeatherWarning = view.findViewById<View>(R.id.cv_weather_warning)
        val tvWeatherWarningText = view.findViewById<TextView>(R.id.tv_weather_warning_text)
        val btnPickTime = view.findViewById<Button>(R.id.btn_pick_time)
        val btnRequest = view.findViewById<Button>(R.id.btn_request)
        mapView = view.findViewById(R.id.request_map)
        mapView.setTileSource(TileSourceFactory.MAPNIK)
        mapView.setMultiTouchControls(true)
        mapView.isHorizontalMapRepetitionEnabled = false
        mapView.isVerticalMapRepetitionEnabled = false
        mapView.setScrollableAreaLimitDouble(org.osmdroid.util.BoundingBox(85.0, 180.0, -85.0, -180.0))
        mapView.minZoomLevel = 3.0

        mapView.controller.setZoom(13.0)
        mapView.controller.setCenter(MapDemoData.lisbon)

        val selectedTime = Calendar.getInstance()
        val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

        // Weather check function
        fun performWeatherCheck() {
            val origin = etOrigin.text.toString().trim()
            if (origin.isBlank()) {
                cvWeatherWarning.visibility = View.GONE
                return
            }
            val type = WeatherType.values()[spinnerWeather.selectedItemPosition]
            val threshold = etThreshold.text.toString().toDoubleOrNull()
            val condition = WeatherCondition(type, threshold)

            viewLifecycleOwner.lifecycleScope.launch {
                val warning = app.weatherService.checkWeatherViolation(
                    origin,
                    selectedTime.time,
                    condition
                )

                if (warning == WeatherWarning.WILL_CANCEL) {
                    val thresholdValue = condition.threshold ?: (if (condition.type == WeatherType.TOO_HOT) 35.0 else 5.0)
                    val conditionDetail = when (condition.type) {
                        WeatherType.TOO_HOT -> "high temperatures (Threshold: ${thresholdValue}°C)"
                        WeatherType.TOO_COLD -> "low temperatures (Threshold: ${thresholdValue}°C)"
                        WeatherType.RAIN -> "rain"
                        else -> "weather conditions"
                    }
                    tvWeatherWarningText.text = "⚠ Safety Note: This request currently violates weather safety rules for $conditionDetail. It will remain active until departure time in case the forecast improves."
                    cvWeatherWarning.visibility = View.VISIBLE
                } else {
                    cvWeatherWarning.visibility = View.GONE
                }
            }
        }

        fun updateRoutePreview() {
            val originStr = etOrigin.text.toString().trim()
            val destinationStr = etDestination.text.toString().trim()
            
            val originPoint = MapDemoData.pointFor(originStr) ?: userFavorites.find { it.name == originStr }?.let { org.osmdroid.util.GeoPoint(it.latitude, it.longitude) }
            val destinationPoint = MapDemoData.pointFor(destinationStr) ?: userFavorites.find { it.name == destinationStr }?.let { org.osmdroid.util.GeoPoint(it.latitude, it.longitude) }
            
            mapView.overlays.clear()

            // Re-add favorites markers
            userFavorites.forEach { fav ->
                val point = org.osmdroid.util.GeoPoint(fav.latitude, fav.longitude)
                val marker = Marker(mapView).apply {
                    position = point
                    title = "★ Favorite: ${fav.name}"
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                    icon = androidx.core.content.res.ResourcesCompat.getDrawable(resources, R.drawable.ic_favorite_24, null)?.apply {
                        setTint(android.graphics.Color.MAGENTA)
                    }
                    setOnMarkerClickListener { _, _ ->
                        if (etOrigin.isFocused) {
                            etOrigin.setText(fav.name)
                            etOrigin.clearFocus()
                        } else {
                            etDestination.setText(fav.name)
                            etDestination.clearFocus()
                        }
                        true
                    }
                }
                mapView.overlays.add(marker)
            }

            if (originPoint != null) {
                mapView.overlays.add(Marker(mapView).apply { position = originPoint; title = "Pickup" })
            }
            if (destinationPoint != null) {
                mapView.overlays.add(Marker(mapView).apply { position = destinationPoint; title = "Destination" })
            }

            if (originPoint != null && destinationPoint != null) {
                val line = Polyline().apply {
                    setPoints(listOf(originPoint, destinationPoint))
                    outlinePaint.strokeWidth = 10f
                }
                mapView.overlays.add(line)
                mapView.controller.animateTo(originPoint)
                mapView.controller.setZoom(14.0)
                tvMapStatus.text = "Route preview ready."

                val price = PriceCalculator.estimate(originStr, destinationStr)
                tvEstimatedPrice.text = "Estimated Price: €${String.format("%.2f", price)}"
                tvEstimatedPrice.visibility = View.VISIBLE
            } else if (originPoint != null) {
                mapView.controller.animateTo(originPoint)
                mapView.controller.setZoom(15.0)
                tvMapStatus.text = "Origin selected. Choose destination."
                tvEstimatedPrice.visibility = View.GONE
            } else if (destinationPoint != null) {
                mapView.controller.animateTo(destinationPoint)
                mapView.controller.setZoom(15.0)
                tvMapStatus.text = "Destination selected. Choose origin."
                tvEstimatedPrice.visibility = View.GONE
            } else {
                tvMapStatus.text = "Select origin and destination from suggestions or favorites on map."
                tvEstimatedPrice.visibility = View.GONE
            }
            mapView.invalidate()
        }

        fun renderSelectedTime() {
            tvSelectedTime.text = "Requested time: ${timeFormat.format(selectedTime.time)}"
        }

        // Setup Locations Adapters and Favorites
        val demoLocations = MapDemoData.allPoints().keys.map { LocationItem(it, false) }
        val originAdapter = LocationAdapter(requireContext(), ArrayList(demoLocations))
        val destAdapter = LocationAdapter(requireContext(), ArrayList(demoLocations))

        etOrigin.setAdapter(originAdapter)
        etDestination.setAdapter(destAdapter)

        fun updateFavoriteIcons() {
            val originText = etOrigin.text.toString()
            val destText = etDestination.text.toString()
            
            val isOriginFav = userFavorites.any { it.name.equals(originText, ignoreCase = true) }
            val isDestFav = userFavorites.any { it.name.equals(destText, ignoreCase = true) }
            
            view.findViewById<com.google.android.material.textfield.TextInputLayout>(R.id.til_origin).findViewById<ImageView>(com.google.android.material.R.id.text_input_end_icon).isActivated = isOriginFav
            view.findViewById<com.google.android.material.textfield.TextInputLayout>(R.id.til_destination).findViewById<ImageView>(com.google.android.material.R.id.text_input_end_icon).isActivated = isDestFav
        }

        view.findViewById<com.google.android.material.textfield.TextInputLayout>(R.id.til_origin).setEndIconOnClickListener {
            val name = etOrigin.text.toString().trim()
            if (name.isEmpty()) return@setEndIconOnClickListener
            
            val existing = userFavorites.find { it.name.equals(name, ignoreCase = true) }
            if (existing != null) {
                viewLifecycleOwner.lifecycleScope.launch {
                    app.favoriteLocationRepository.deleteFavorite(existing.id)
                    Toast.makeText(context, "Removed from favorites", Toast.LENGTH_SHORT).show()
                }
            } else {
                val point = MapDemoData.pointFor(name)
                if (point == null) {
                    Toast.makeText(context, "Cannot favorite unknown location", Toast.LENGTH_SHORT).show()
                    return@setEndIconOnClickListener
                }
                viewLifecycleOwner.lifecycleScope.launch {
                    val fav = FavoriteLocation(
                        id = UUID.randomUUID().toString(),
                        userId = session.uid ?: "",
                        name = name,
                        address = name,
                        latitude = point.latitude,
                        longitude = point.longitude
                    )
                    app.favoriteLocationRepository.addFavorite(fav)
                    Toast.makeText(context, "Saved to favorites", Toast.LENGTH_SHORT).show()
                }
            }
        }

        view.findViewById<com.google.android.material.textfield.TextInputLayout>(R.id.til_destination).setEndIconOnClickListener {
            val name = etDestination.text.toString().trim()
            if (name.isEmpty()) return@setEndIconOnClickListener
            
            val existing = userFavorites.find { it.name.equals(name, ignoreCase = true) }
            if (existing != null) {
                viewLifecycleOwner.lifecycleScope.launch {
                    app.favoriteLocationRepository.deleteFavorite(existing.id)
                    Toast.makeText(context, "Removed from favorites", Toast.LENGTH_SHORT).show()
                }
            } else {
                val point = MapDemoData.pointFor(name)
                if (point == null) {
                    Toast.makeText(context, "Cannot favorite unknown location", Toast.LENGTH_SHORT).show()
                    return@setEndIconOnClickListener
                }
                viewLifecycleOwner.lifecycleScope.launch {
                    val fav = FavoriteLocation(
                        id = UUID.randomUUID().toString(),
                        userId = session.uid ?: "",
                        name = name,
                        address = name,
                        latitude = point.latitude,
                        longitude = point.longitude
                    )
                    app.favoriteLocationRepository.addFavorite(fav)
                    Toast.makeText(context, "Saved to favorites", Toast.LENGTH_SHORT).show()
                }
            }
        }

        // Show dropdown on click
        etOrigin.setOnClickListener { etOrigin.showDropDown() }
        etDestination.setOnClickListener { etDestination.showDropDown() }

        viewLifecycleOwner.lifecycleScope.launch {
            val uid = session.uid
            if (uid != null) {
                app.favoriteLocationRepository.getFavorites(uid).collect { favorites ->
                    userFavorites = favorites
                    val favoriteItems = favorites.map { LocationItem(it.name, true) }
                    
                    // Favorites first, then demo locations, distinct by name
                    val allItems = (favoriteItems + demoLocations)
                        .distinctBy { it.name }
                        .sortedByDescending { it.isFavorite }
                    
                    originAdapter.updateItems(allItems)
                    destAdapter.updateItems(allItems)

                    updateRoutePreview()
                    updateFavoriteIcons()
                }
            }
        }

        // Listeners
        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                updateRoutePreview()
                performWeatherCheck()
                updateFavoriteIcons()
            }
        }
        etOrigin.addTextChangedListener(watcher)
        etDestination.addTextChangedListener(watcher)

        val weatherOptions = WeatherType.values().map { it.name }
        spinnerWeather.adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, weatherOptions)
        spinnerWeather.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p0: AdapterView<*>?, p1: View?, pos: Int, p3: Long) {
                val selected = WeatherType.values()[pos]
                layoutThreshold.visibility = if (selected == WeatherType.TOO_HOT || selected == WeatherType.TOO_COLD) View.VISIBLE else View.GONE
                performWeatherCheck()
            }
            override fun onNothingSelected(p0: AdapterView<*>?) {}
        }

        etThreshold.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) { performWeatherCheck() }
        })

        btnPickTime.setOnClickListener {
            TimePickerDialog(
                requireContext(),
                { _, hourOfDay, minute ->
                    selectedTime.set(Calendar.HOUR_OF_DAY, hourOfDay)
                    selectedTime.set(Calendar.MINUTE, minute)
                    selectedTime.set(Calendar.SECOND, 0)
                    selectedTime.set(Calendar.MILLISECOND, 0)
                    renderSelectedTime()
                    performWeatherCheck()
                },
                selectedTime.get(Calendar.HOUR_OF_DAY),
                selectedTime.get(Calendar.MINUTE),
                true
            ).show()
        }

        btnRequest.setOnClickListener {
            val origin = etOrigin.text.toString().trim()
            val destination = etDestination.text.toString().trim()
            
            val originPoint = MapDemoData.pointFor(origin) ?: userFavorites.find { it.name == origin }?.let { org.osmdroid.util.GeoPoint(it.latitude, it.longitude) }
            val destinationPoint = MapDemoData.pointFor(destination) ?: userFavorites.find { it.name == destination }?.let { org.osmdroid.util.GeoPoint(it.latitude, it.longitude) }
            
            if (origin.isBlank() || destination.isBlank()) {
                Toast.makeText(requireContext(), "Fill origin and destination", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (originPoint == null || destinationPoint == null) {
                Toast.makeText(
                    requireContext(),
                    "Use valid demo locations or your favorites from the map.",
                    Toast.LENGTH_LONG
                ).show()
                return@setOnClickListener
            }

            if (origin.equals(destination, ignoreCase = true)) {
                Toast.makeText(requireContext(), "Origin and Destination cannot be the same", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val estimatedPrice = PriceCalculator.estimate(origin, destination)

            val request = RideRequest(
                passengerId = session.uid ?: "",
                passengerName = session.displayName ?: "Anonymous",
                origin = origin,
                originLat = originPoint.latitude,
                originLng = originPoint.longitude,
                destination = destination,
                destinationLat = destinationPoint.latitude,
                destinationLng = destinationPoint.longitude,
                requestedTime = selectedTime.time,
                estimatedPrice = estimatedPrice,
                status = RequestStatus.OPEN,
                weatherCondition = WeatherCondition(
                    type = WeatherType.values()[spinnerWeather.selectedItemPosition],
                    threshold = etThreshold.text.toString().toDoubleOrNull()
                ),
                createdAt = Date()
            )

            viewLifecycleOwner.lifecycleScope.launch {
                val result = requestRepo.createRequest(request)
                if (result.isSuccess) {
                    Toast.makeText(requireContext(), "Request created successfully!", Toast.LENGTH_SHORT).show()
                    findNavController().navigate(R.id.action_request_to_my_requests)
                } else {
                    Toast.makeText(
                        requireContext(),
                        "Failed to create request: ${result.exceptionOrNull()?.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }

        renderSelectedTime()
        updateRoutePreview()
        performWeatherCheck()
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
