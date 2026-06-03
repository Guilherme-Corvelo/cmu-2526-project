package pt.ulisboa.tecnico.sharist.ui.driver

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.util.BoundingBox
import pt.ulisboa.tecnico.sharist.R
import pt.ulisboa.tecnico.sharist.SharISTApp
import pt.ulisboa.tecnico.sharist.data.model.RideRequest
import pt.ulisboa.tecnico.sharist.data.repository.RideRequestRepository
import pt.ulisboa.tecnico.sharist.ui.map.MapDemoData

import android.util.Log
import kotlinx.coroutines.flow.catch
import pt.ulisboa.tecnico.sharist.data.repository.UserRepository
import pt.ulisboa.tecnico.sharist.utils.WeatherService
import pt.ulisboa.tecnico.sharist.utils.WeatherWarning
import pt.ulisboa.tecnico.sharist.data.model.WeatherType
import pt.ulisboa.tecnico.sharist.data.model.WeatherCondition

class AvailableRequestsFragment : Fragment() {
    private lateinit var mapView: MapView
    private lateinit var requestRepo: RideRequestRepository
    private lateinit var userRepo: UserRepository

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_available_requests, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val app = requireActivity().application as SharISTApp
        requestRepo = app.requestRepository
        userRepo = app.userRepository
        val session = app.sessionManager
        val recycler = view.findViewById<RecyclerView>(R.id.recycler_requests)
        val tvEmpty = view.findViewById<TextView>(R.id.tv_empty)

        Configuration.getInstance().setUserAgentValue(requireContext().packageName)
        Configuration.getInstance().load(requireContext(), requireContext().getSharedPreferences("osmdroid", 0))

        mapView = view.findViewById(R.id.available_request_map)
        mapView.setTileSource(TileSourceFactory.MAPNIK)
        mapView.setMultiTouchControls(true)
        mapView.isHorizontalMapRepetitionEnabled = false
        mapView.isVerticalMapRepetitionEnabled = false
        mapView.setScrollableAreaLimitDouble(BoundingBox(85.0, 180.0, -85.0, -180.0))
        mapView.minZoomLevel = 3.0

        mapView.controller.setCenter(MapDemoData.lisbon)
        mapView.controller.setZoom(13.0)

        val adapter = DriverRequestAdapter(
            onAccept = { req ->
                renderPreviewRoute(req)
                viewLifecycleOwner.lifecycleScope.launch {
                    // Check for passenger's weather rule violation
                    val warning = WeatherService().checkWeatherViolation(
                        req.origin,
                        req.requestedTime,
                        req.weatherCondition ?: WeatherCondition(WeatherType.NONE)
                    )
                    
                    if (warning == WeatherWarning.WILL_CANCEL) {
                        Toast.makeText(
                            requireContext(),
                            "Warning: The passenger's weather safety rules are currently triggered for this ride. It might be cancelled automatically.",
                            Toast.LENGTH_LONG
                        ).show()
                    }

                    val driverRating = userRepo.getUser(session.uid ?: "")?.rating ?: 5.0
                    val res = requestRepo.acceptRequest(
                        req.id,
                        session.uid ?: "",
                        session.displayName ?: "Driver",
                        driverRating
                    )
                    if (res.isFailure) {
                        Toast.makeText(requireContext(), "Failed to accept: ${res.exceptionOrNull()?.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            },
            onDeny = { req ->
                viewLifecycleOwner.lifecycleScope.launch {
                    val res = requestRepo.denyRequest(req.id, session.uid ?: "")
                    if (res.isFailure) {
                        Toast.makeText(requireContext(), "Failed to deny: ${res.exceptionOrNull()?.message}", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(requireContext(), "Request hidden", Toast.LENGTH_SHORT).show()
                    }
                }
            },
            onPreview = { renderPreviewRoute(it) }
        )
        recycler.layoutManager = LinearLayoutManager(requireContext())
        recycler.adapter = adapter

        viewLifecycleOwner.lifecycleScope.launch {
            requestRepo.getOpenRequests()
                .catch { e ->
                    Log.e("AvailableReq", "Index missing?", e)
                    tvEmpty.visibility = View.VISIBLE
                    tvEmpty.text = "Error: Check Logcat for index URL"
                }
                .collect { list ->
                    adapter.submitList(list)
                    renderPreviewRoute(list.firstOrNull())
                    tvEmpty.text = "No open requests available"
                    tvEmpty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
                }
        }
    }

    private fun renderPreviewRoute(ride: RideRequest?) {
        mapView.overlays.clear()
        if (ride == null) {
            mapView.controller.animateTo(MapDemoData.lisbon)
            mapView.controller.setZoom(13.0)
            mapView.invalidate()
            return
        }
        val origin = MapDemoData.pointFor(ride.origin)
        val destination = MapDemoData.pointFor(ride.destination)
        if (origin != null) mapView.overlays.add(Marker(mapView).apply { position = origin; title = "Pickup: ${ride.origin}" })
        if (destination != null) mapView.overlays.add(Marker(mapView).apply { position = destination; title = "Drop-off: ${ride.destination}" })
        if (origin != null && destination != null) {
            mapView.overlays.add(Polyline().apply { setPoints(listOf(origin, destination)); outlinePaint.strokeWidth = 10f })
            mapView.controller.animateTo(origin)
            mapView.controller.setZoom(14.0)
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

class DriverRequestAdapter(
    private val onAccept: (RideRequest) -> Unit,
    private val onDeny: (RideRequest) -> Unit = {},
    private val onPreview: (RideRequest) -> Unit = {}
) : ListAdapter<RideRequest, DriverRequestAdapter.VH>(DIFF) {
    inner class VH(v: View) : RecyclerView.ViewHolder(v) {
        val tvRoute: TextView = v.findViewById(R.id.tv_route)
        val tvDriver: TextView = v.findViewById(R.id.tv_driver)
        val tvWeatherBadge: TextView = v.findViewById(R.id.tv_weather_badge)
        val btnCancel: Button = v.findViewById(R.id.btn_cancel)
        val btnRate: Button = v.findViewById(R.id.btn_rate)
    }
    override fun onCreateViewHolder(p: ViewGroup, v: Int) = VH(LayoutInflater.from(p.context).inflate(R.layout.item_my_request, p, false))
    override fun onBindViewHolder(h: VH, pos: Int) {
        val r = getItem(pos)
        h.tvRoute.text = "${r.passengerName}: ${r.origin} → ${r.destination}"
        h.tvDriver.text = "Tap accept to move this ride to My Activities"
        h.tvWeatherBadge.visibility = if (r.weatherWarning) View.VISIBLE else View.GONE
        h.btnCancel.visibility = View.VISIBLE
        h.btnCancel.text = "Accept"
        h.btnCancel.setOnClickListener { onAccept(r) }
        h.btnRate.visibility = View.VISIBLE
        h.btnRate.text = "Deny"
        h.btnRate.setOnClickListener { onDeny(r) }
        h.itemView.setOnClickListener { onPreview(r) }
    }
    companion object { private val DIFF = object : DiffUtil.ItemCallback<RideRequest>() { override fun areItemsTheSame(a: RideRequest, b: RideRequest)=a.id==b.id; override fun areContentsTheSame(a: RideRequest, b: RideRequest)=a==b } }
}
