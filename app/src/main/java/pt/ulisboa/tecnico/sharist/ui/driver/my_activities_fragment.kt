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
import pt.ulisboa.tecnico.sharist.data.model.*
import pt.ulisboa.tecnico.sharist.data.repository.RideRepository
import pt.ulisboa.tecnico.sharist.data.repository.RideRequestRepository
import pt.ulisboa.tecnico.sharist.ui.map.MapDemoData
import pt.ulisboa.tecnico.sharist.ui.passenger.RateDriverDialog
import pt.ulisboa.tecnico.sharist.ui.rides.CreateRideActivity
import pt.ulisboa.tecnico.sharist.ui.driver.DriverPostedRidesActivity
import kotlinx.coroutines.flow.*

class MyActiveRidesFragment : Fragment() {
    private lateinit var requestRepo: RideRequestRepository
    private lateinit var rideRepo: RideRepository
    private lateinit var mapView: MapView
    private lateinit var app: SharISTApp

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_my_active_rides, container, false)

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        app = requireActivity().application as SharISTApp
        requestRepo = app.requestRepository
        rideRepo = app.rideRepository
        val session = app.sessionManager
        val recycler = view.findViewById<RecyclerView>(R.id.recycler_rides)
        val tvEmpty = view.findViewById<TextView>(R.id.tv_empty)
        view.findViewById<android.widget.Button>(R.id.btn_create_ride).setOnClickListener {
            startActivity(Intent(requireContext(), CreateRideActivity::class.java))
        }
        view.findViewById<android.widget.Button>(R.id.btn_view_posted_rides).setOnClickListener {
            startActivity(Intent(requireContext(), DriverPostedRidesActivity::class.java))
        }

        Configuration.getInstance().load(requireContext(), requireContext().getSharedPreferences("osmdroid", 0))
        mapView = view.findViewById(R.id.driver_route_map)
        mapView.setTileSource(TileSourceFactory.MAPNIK)
        mapView.setMultiTouchControls(true)
        mapView.controller.setCenter(MapDemoData.lisbon)
        mapView.controller.setZoom(13.0)

        val adapter = ActiveRideAdapter(
            onUpdateStatus = { item, newStatus ->
                viewLifecycleOwner.lifecycleScope.launch {
                    try {
                        if (item is RideRequest && newStatus is RequestStatus) {
                            requestRepo.updateRequestStatus(item.id, newStatus)
                        } else if (item is Booking && newStatus is BookingStatus) {
                            rideRepo.updateBookingStatus(item.id, newStatus)
                        }
                    } catch (e: Exception) {
                        android.util.Log.e("MyActiveRides", "Status update failed", e)
                    }
                }
            },
            onRideCompleted = { item ->
                val passengerName = if (item is RideRequest) item.passengerName else (item as Booking).passengerName
                val passengerId = if (item is RideRequest) item.passengerId else (item as Booking).passengerId
                val id = if (item is RideRequest) item.id else (item as Booking).id

                RateDriverDialog(
                    targetName = passengerName,
                    titleOverride = "Rate your passenger $passengerName"
                ) { stars, comment ->
                    viewLifecycleOwner.lifecycleScope.launch {
                        try {
                            val uid = session.uid ?: return@launch
                            val review = Review(
                                requestId = id,
                                driverId = passengerId, // review target (passenger)
                                passengerId = uid,
                                rating = stars,
                                comment = comment
                            )
                            requestRepo.submitReview(review)
                            context?.let { android.widget.Toast.makeText(it, "Review submitted!", android.widget.Toast.LENGTH_SHORT).show() }
                        } catch (e: Exception) {
                            android.util.Log.e("MyActiveRides", "Error submitting review", e)
                            context?.let { android.widget.Toast.makeText(it, "Failed to submit review", android.widget.Toast.LENGTH_LONG).show() }
                        }
                    }
                }.show(childFragmentManager, "rate_passenger")
            }
        )
        recycler.layoutManager = LinearLayoutManager(requireContext())
        recycler.adapter = adapter

        viewLifecycleOwner.lifecycleScope.launch {
            val activeStatuses = listOf(RequestStatus.ACCEPTED, RequestStatus.EN_ROUTE, RequestStatus.PICKED_UP, RequestStatus.COMPLETED)
            val activeBookingStatuses = listOf(BookingStatus.ACCEPTED, BookingStatus.EN_ROUTE, BookingStatus.PICKED_UP, BookingStatus.COMPLETED)
            val uid = session.uid ?: ""
            
            kotlinx.coroutines.flow.combine(
                requestRepo.getDriverRequests(uid).catch { emit(emptyList()) },
                rideRepo.getDriverBookings(uid).catch { emit(emptyList()) }
            ) { requests, bookings ->
                val filteredRequests = requests.filter { it.status in activeStatuses && !it.passengerReviewed }
                val filteredBookings = bookings.filter { it.status in activeBookingStatuses && !it.passengerReviewed }
                (filteredRequests + filteredBookings).sortedByDescending {
                    when (it) {
                        is RideRequest -> it.createdAt?.time ?: 0L
                        is Booking -> it.createdAt?.time ?: 0L
                        else -> 0L
                    }
                }
            }.catch { e ->
                android.util.Log.e("MyActiveRidesFragment", "Error combining flows", e)
                emit(emptyList())
            }.collect {
                adapter.submitList(it)
                renderAcceptedRoute(it.firstOrNull { item ->
                    val status = if (item is RideRequest) item.status else (item as Booking).status
                    status != RequestStatus.COMPLETED && status != BookingStatus.COMPLETED
                })
                tvEmpty.text = "No active trip. Create a shared ride above or accept bookings from your rides."
                tvEmpty.visibility = if (it.isEmpty()) View.VISIBLE else View.GONE
            }
        }
    }

    private fun renderAcceptedRoute(item: Any?) {
        mapView.overlays.clear()
        if (item == null) {
            mapView.controller.animateTo(MapDemoData.lisbon)
            mapView.controller.setZoom(13.0)
            mapView.invalidate()
            return
        }

        val originStr = if (item is RideRequest) item.origin else (item as Booking).origin
        val destStr = if (item is RideRequest) item.destination else (item as Booking).destination

        val origin = MapDemoData.pointFor(originStr)
        val destination = MapDemoData.pointFor(destStr)

        if (origin != null) {
            mapView.overlays.add(Marker(mapView).apply { position = origin; title = "Pickup: $originStr" })
        }
        if (destination != null) {
            mapView.overlays.add(Marker(mapView).apply { position = destination; title = "Drop-off: $destStr" })
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
    private val onUpdateStatus: (Any, Any) -> Unit,
    private val onRideCompleted: (Any) -> Unit
) : androidx.recyclerview.widget.ListAdapter<Any, ActiveRideAdapter.VH>(DIFF) {
    inner class VH(v: View) : RecyclerView.ViewHolder(v) {
        val tvRoute: TextView = v.findViewById(R.id.tv_route)
        val tvDriver: TextView = v.findViewById(R.id.tv_driver)
        val btnAction: android.widget.Button = v.findViewById(R.id.btn_action)
        val btnCancel: android.widget.Button = v.findViewById(R.id.btn_cancel)
        val btnRate: android.widget.Button = v.findViewById(R.id.btn_rate)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        return VH(LayoutInflater.from(parent.context).inflate(R.layout.item_my_request, parent, false))
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = getItem(position)
        
        val passengerName = if (item is RideRequest) item.passengerName else (item as Booking).passengerName
        val origin = if (item is RideRequest) item.origin else (item as Booking).origin
        val destination = if (item is RideRequest) item.destination else (item as Booking).destination
        val status = if (item is RideRequest) item.status.name else (item as Booking).status.name

        holder.tvRoute.text = "$passengerName: $origin → $destination"

        val (statusText, btnText, nextStatus) = when {
            item is RideRequest && item.status == RequestStatus.ACCEPTED -> Triple("Accepted - Get moving!", "Start Trip", RequestStatus.EN_ROUTE)
            item is RideRequest && item.status == RequestStatus.EN_ROUTE -> Triple("En route to pickup", "Confirm Pickup", RequestStatus.PICKED_UP)
            item is RideRequest && item.status == RequestStatus.PICKED_UP -> Triple("Passenger onboard", "Finish Ride", RequestStatus.COMPLETED)
            item is RideRequest && item.status == RequestStatus.COMPLETED -> Triple("Ride Completed", "", RequestStatus.COMPLETED)

            item is Booking && item.status == BookingStatus.ACCEPTED -> Triple("Booking Accepted", "Start Trip", BookingStatus.EN_ROUTE)
            item is Booking && item.status == BookingStatus.EN_ROUTE -> Triple("En route to pickup", "Confirm Pickup", BookingStatus.PICKED_UP)
            item is Booking && item.status == BookingStatus.PICKED_UP -> Triple("Passenger onboard", "Finish Ride", BookingStatus.COMPLETED)
            item is Booking && item.status == BookingStatus.COMPLETED -> Triple("Ride Completed", "", BookingStatus.COMPLETED)

            else -> Triple("Status: $status", "Next Step", RequestStatus.COMPLETED)
        }

        val isCompleted = (item is RideRequest && item.status == RequestStatus.COMPLETED) || 
                          (item is Booking && item.status == BookingStatus.COMPLETED)
        
        holder.tvDriver.text = statusText
        holder.btnAction.visibility = if (btnText.isNotEmpty()) View.VISIBLE else View.GONE
        holder.btnAction.text = btnText
        holder.btnAction.setOnClickListener {
            onUpdateStatus(item, nextStatus)
        }

        holder.btnCancel.visibility = if (isCompleted) View.GONE else View.VISIBLE
        holder.btnCancel.text = "Cancel"
        holder.btnCancel.setOnClickListener {
            // New logic to allow driver to cancel individual booking/request
            if (item is RideRequest) {
                onUpdateStatus(item, RequestStatus.CANCELLED)
            } else if (item is Booking) {
                onUpdateStatus(item, BookingStatus.CANCELLED)
            }
        }
        
        val isReviewed = if (item is RideRequest) item.passengerReviewed else (item as Booking).passengerReviewed

        holder.btnRate.visibility = if (isCompleted && !isReviewed) View.VISIBLE else View.GONE
        holder.btnRate.setOnClickListener { onRideCompleted(item) }
    }

    companion object {
        private val DIFF = object : androidx.recyclerview.widget.DiffUtil.ItemCallback<Any>() {
            override fun areItemsTheSame(oldItem: Any, newItem: Any): Boolean {
                return if (oldItem is RideRequest && newItem is RideRequest) oldItem.id == newItem.id
                else if (oldItem is Booking && newItem is Booking) oldItem.id == newItem.id
                else false
            }
            override fun areContentsTheSame(oldItem: Any, newItem: Any) = oldItem == newItem
        }
    }
}
