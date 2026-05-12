package pt.ulisboa.tecnico.sharist.ui.passenger

import android.app.TimePickerDialog
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import pt.ulisboa.tecnico.sharist.R
import pt.ulisboa.tecnico.sharist.ui.demo.DemoRequestStore
import pt.ulisboa.tecnico.sharist.ui.map.MapDemoData
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class RequestRideFragment : Fragment() {
    private lateinit var mapView: MapView

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_request_ride, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val etOrigin = view.findViewById<EditText>(R.id.et_origin)
        val etDestination = view.findViewById<EditText>(R.id.et_destination)
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
            } else {
                tvMapStatus.text = "Route preview needs known demo From/To names (IST Alameda, Saldanha, Campo Grande, Oriente)."
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
            if (origin.isBlank() || destination.isBlank()) {
                Toast.makeText(requireContext(), "Fill origin and destination", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val created = DemoRequestStore.submitRequest(origin, destination, selectedTime.time)
            if (!created) {
                Toast.makeText(
                    requireContext(),
                    "You already have an active request/ride. Complete or cancel it first.",
                    Toast.LENGTH_LONG
                ).show()
                return@setOnClickListener
            }
            Toast.makeText(requireContext(), "Demo request created", Toast.LENGTH_SHORT).show()
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
