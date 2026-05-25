package pt.ulisboa.tecnico.sharist.ui.driver

import android.os.Bundle
import android.content.Intent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import pt.ulisboa.tecnico.sharist.R
import pt.ulisboa.tecnico.sharist.SharISTApp
import pt.ulisboa.tecnico.sharist.data.model.RequestStatus
import pt.ulisboa.tecnico.sharist.data.model.Review
import pt.ulisboa.tecnico.sharist.data.model.RideRequest
import pt.ulisboa.tecnico.sharist.data.repository.RideRequestRepository
import pt.ulisboa.tecnico.sharist.ui.map.MapDemoData
import pt.ulisboa.tecnico.sharist.ui.passenger.RateDriverDialog
import pt.ulisboa.tecnico.sharist.ui.rides.CreateRideActivity

class MyActiveRidesFragment : Fragment() {
    private lateinit var requestRepo: RideRequestRepository
    private lateinit var mapView: MapView
    private lateinit var app: SharISTApp

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_my_active_rides, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        app = requireActivity().application as SharISTApp
        requestRepo = app.requestRepository
        val session = app.sessionManager
        val recycler = view.findViewById<RecyclerView>(R.id.recycler_rides)
        val tvEmpty = view.findViewById<TextView>(R.id.tv_empty)
        view.findViewById<android.widget.Button>(R.id.btn_create_ride).setOnClickListener {
            startActivity(Intent(requireContext(), CreateRideActivity::class.java))
        }

        Configuration.getInstance().load(requireContext(), requireContext().getSharedPreferences("osmdroid", 0))
        mapView = view.findViewById(R.id.driver_route_map)
        mapView.setTileSource(TileSourceFactory.MAPNIK)
        mapView.setMultiTouchControls(true)
        mapView.controller.setCenter(MapDemoData.lisbon)
        mapView.controller.setZoom(13.0)

        val adapter = ActiveRideAdapter(
            onUpdateStatus = { req, newStatus ->
                viewLifecycleOwner.lifecycleScope.launch {
                    requestRepo.updateRequestStatus(req.id, newStatus)
                }
            },
            onRideCompleted = { req ->
                RateDriverDialog(req.passengerName) { stars, comment ->
                    viewLifecycleOwner.lifecycleScope.launch {
                        val review = Review(
                            requestId = req.id,
                            driverId = req.passengerId, // review target (passenger)
                            passengerId = session.uid ?: "",
                            rating = stars,
                            comment = comment
                        )
                        requestRepo.submitReview(review)
                    }
                }.show(parentFragmentManager, "rate_passenger")
            }
        )
        recycler.layoutManager = LinearLayoutManager(requireContext())
        recycler.adapter = adapter

        viewLifecycleOwner.lifecycleScope.launch {
            val activeStatuses = listOf(RequestStatus.ACCEPTED, RequestStatus.EN_ROUTE, RequestStatus.PICKED_UP)
            requestRepo.getDriverRequests(session.uid ?: "")
                .map { list -> list.filter { it.status in activeStatuses } }
                .collect {
                    adapter.submitList(it)
                    renderAcceptedRoute(it.firstOrNull())
                    tvEmpty.text = "No active trip. Create a shared ride above or accept bookings from your rides."
                    tvEmpty.visibility = if (it.isEmpty()) View.VISIBLE else View.GONE
                }
        }
    }

    private fun renderAcceptedRoute(ride: RideRequest?) {
        mapView.overlays.clear()
        if (ride == null) {
            mapView.controller.animateTo(MapDemoData.lisbon)
            mapView.controller.setZoom(13.0)
            mapView.invalidate()
            return
        }

        val origin = MapDemoData.pointFor(ride.origin)
        val destination = MapDemoData.pointFor(ride.destination)

        if (origin != null) {
            mapView.overlays.add(Marker(mapView).apply { position = origin; title = "Pickup: ${ride.origin}" })
        }
        if (destination != null) {
            mapView.overlays.add(Marker(mapView).apply { position = destination; title = "Drop-off: ${ride.destination}" })
        }
        if (origin != null && destination != null) {
            val route = Polyline().apply {
                setPoints(listOf(origin, destination))
                outlinePaint.strokeWidth = 10f
            }
            mapView.overlays.add(route)
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

class ActiveRideAdapter(
    private val onUpdateStatus: (RideRequest, RequestStatus) -> Unit,
    private val onRideCompleted: (RideRequest) -> Unit
) : androidx.recyclerview.widget.ListAdapter<RideRequest, ActiveRideAdapter.VH>(DIFF) {
    inner class VH(v: View) : RecyclerView.ViewHolder(v) {
        val tvRoute: TextView = v.findViewById(R.id.tv_route)
        val tvDriver: TextView = v.findViewById(R.id.tv_driver)
        val btnCancel: android.widget.Button = v.findViewById(R.id.btn_cancel)
        val btnRate: android.widget.Button = v.findViewById(R.id.btn_rate)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        return VH(LayoutInflater.from(parent.context).inflate(R.layout.item_my_request, parent, false))
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val ride = getItem(position)
        holder.tvRoute.text = "${ride.passengerName}: ${ride.origin} → ${ride.destination}"

        val (statusText, btnText, nextStatus) = when (ride.status) {
            RequestStatus.ACCEPTED -> Triple("Accepted - Get moving!", "Start Trip", RequestStatus.EN_ROUTE)
            RequestStatus.EN_ROUTE -> Triple("En route to pickup", "Confirm Pickup", RequestStatus.PICKED_UP)
            RequestStatus.PICKED_UP -> Triple("Passenger onboard", "Finish Ride", RequestStatus.COMPLETED)
            else -> Triple("Active ride", "Next Step", RequestStatus.COMPLETED)
        }

        holder.tvDriver.text = statusText
        holder.btnCancel.visibility = View.VISIBLE
        holder.btnCancel.text = btnText
        holder.btnCancel.setOnClickListener {
            onUpdateStatus(ride, nextStatus)
            if (nextStatus == RequestStatus.COMPLETED) onRideCompleted(ride)
        }
        holder.btnRate.visibility = View.GONE
    }

    companion object {
        private val DIFF = object : androidx.recyclerview.widget.DiffUtil.ItemCallback<RideRequest>() {
            override fun areItemsTheSame(oldItem: RideRequest, newItem: RideRequest) = oldItem.id == newItem.id
            override fun areContentsTheSame(oldItem: RideRequest, newItem: RideRequest) = oldItem == newItem
        }
    }
}
