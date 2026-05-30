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
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

class RequestRideFragment : Fragment() {
    private lateinit var mapView: MapView
    private lateinit var requestRepo: RideRequestRepository

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_request_ride, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val app = requireActivity().application as SharISTApp
        requestRepo = app.requestRepository
        val session = app.sessionManager
        val etOrigin = view.findViewById<AutoCompleteTextView>(R.id.et_origin)
        val etDestination = view.findViewById<AutoCompleteTextView>(R.id.et_destination)
        val tvEstimatedPrice = view.findViewById<TextView>(R.id.tv_estimated_price)
        
        val locations = MapDemoData.allPoints().keys.toList()
        val adapter = ArrayAdapter<String>(requireContext(), android.R.layout.simple_dropdown_item_1line, locations)
        etOrigin.setAdapter(adapter)
        etDestination.setAdapter(adapter)

        // Make dropdowns appear on click
        etOrigin.setOnClickListener { etOrigin.showDropDown() }
        etOrigin.setOnTouchListener { _, _ -> etOrigin.showDropDown(); false }
        etDestination.setOnClickListener { etDestination.showDropDown() }
        etDestination.setOnTouchListener { _, _ -> etDestination.showDropDown(); false }
        val tvSelectedTime = view.findViewById<TextView>(R.id.tv_selected_time)
        val tvMapStatus = view.findViewById<TextView>(R.id.tv_map_status)
        val selectedTime = Calendar.getInstance()
        val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

        Configuration.getInstance().load(requireContext(), requireContext().getSharedPreferences("osmdroid", 0))
        mapView = view.findViewById(R.id.request_map)
        mapView.setTileSource(TileSourceFactory.MAPNIK)
        mapView.setMultiTouchControls(true)
        mapView.controller.setCenter(MapDemoData.lisbon)
        mapView.controller.setZoom(13.0)

        fun renderSelectedTime() {
            tvSelectedTime.text = "Requested time: ${timeFormat.format(selectedTime.time)}"
        }

        fun updateRoutePreview() {
            val origin = MapDemoData.pointFor(etOrigin.text.toString().trim())
            val destination = MapDemoData.pointFor(etDestination.text.toString().trim())
            mapView.overlays.clear()

            if (origin != null) {
                mapView.overlays.add(Marker(mapView).apply { position = origin; title = "Pickup" })
            }
            if (destination != null) {
                mapView.overlays.add(Marker(mapView).apply { position = destination; title = "Destination" })
            }

            if (origin != null && destination != null) {
                val line = Polyline().apply {
                    setPoints(listOf(origin, destination))
                    outlinePaint.strokeWidth = 10f
                }
                mapView.overlays.add(line)
                mapView.controller.animateTo(origin)
                mapView.controller.setZoom(14.0)
                tvMapStatus.text = "Route preview ready. This request can be matched on the map."
                
                val price = PriceCalculator.estimate(etOrigin.text.toString().trim(), etDestination.text.toString().trim())
                tvEstimatedPrice.text = "Estimated Price: €${String.format("%.2f", price)}"
                tvEstimatedPrice.visibility = View.VISIBLE
            } else {
                tvMapStatus.text = "Route preview needs known demo From/To names (IST Alameda, Saldanha, Campo Grande, Oriente)."
                tvEstimatedPrice.visibility = View.GONE
            }
            mapView.invalidate()
        }

        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) = updateRoutePreview()
        }
        etOrigin.addTextChangedListener(watcher)
        etDestination.addTextChangedListener(watcher)

        renderSelectedTime()
        updateRoutePreview()

        val spinnerWeather = view.findViewById<Spinner>(R.id.spinner_weather)
        val layoutThreshold = view.findViewById<View>(R.id.layout_threshold)
        val etThreshold = view.findViewById<EditText>(R.id.et_threshold)

        val weatherOptions = WeatherType.values().map { it.name }
        spinnerWeather.adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, weatherOptions)
        spinnerWeather.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p0: AdapterView<*>?, p1: View?, pos: Int, p3: Long) {
                val selected = WeatherType.values()[pos]
                layoutThreshold.visibility = if (selected == WeatherType.TOO_HOT || selected == WeatherType.TOO_COLD) View.VISIBLE else View.GONE
            }
            override fun onNothingSelected(p0: AdapterView<*>?) {}
        }

        view.findViewById<Button>(R.id.btn_pick_time).setOnClickListener {
            TimePickerDialog(
                requireContext(),
                { _, hourOfDay, minute ->
                    selectedTime.set(Calendar.HOUR_OF_DAY, hourOfDay)
                    selectedTime.set(Calendar.MINUTE, minute)
                    selectedTime.set(Calendar.SECOND, 0)
                    selectedTime.set(Calendar.MILLISECOND, 0)
                    renderSelectedTime()
                },
                selectedTime.get(Calendar.HOUR_OF_DAY),
                selectedTime.get(Calendar.MINUTE),
                true
            ).show()
        }

        view.findViewById<Button>(R.id.btn_request).setOnClickListener {
            val origin = etOrigin.text.toString().trim()
            val destination = etDestination.text.toString().trim()
            val originPoint = MapDemoData.pointFor(origin)
            val destinationPoint = MapDemoData.pointFor(destination)
            if (origin.isBlank() || destination.isBlank()) {
                Toast.makeText(requireContext(), "Fill origin and destination", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (originPoint == null || destinationPoint == null) {
                Toast.makeText(
                    requireContext(),
                    "Use valid demo locations: IST Alameda, Saldanha, Campo Grande, or Oriente.",
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
                destination = destination,
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
                    // Navigate to My Requests list instead of just finishing the activity
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
