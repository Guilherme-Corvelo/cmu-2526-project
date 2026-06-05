package pt.ulisboa.tecnico.sharist.ui.driver

import android.os.Bundle
import android.content.Intent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
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
import org.osmdroid.util.BoundingBox
import androidx.navigation.fragment.NavHostFragment
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

data class RideJourney(
    val ride: Ride,
    val bookings: List<Booking>
)

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

        Configuration.getInstance().setUserAgentValue(requireContext().packageName)
        Configuration.getInstance().load(requireContext(), requireContext().getSharedPreferences("osmdroid", 0))
        mapView = view.findViewById(R.id.driver_route_map)
        mapView.setTileSource(TileSourceFactory.MAPNIK)
        mapView.setMultiTouchControls(true)
        mapView.isHorizontalMapRepetitionEnabled = false
        mapView.isVerticalMapRepetitionEnabled = false
        mapView.setScrollableAreaLimitDouble(BoundingBox(85.0, 180.0, -85.0, -180.0))
        mapView.minZoomLevel = 3.0

        mapView.controller.setCenter(MapDemoData.lisbon)
        mapView.controller.setZoom(13.0)

        val adapter = ActiveRideAdapter(
            onUpdateStatus = { item, newStatus ->
                viewLifecycleOwner.lifecycleScope.launch {
                    try {
                        when {
                            item is RideRequest && newStatus is RequestStatus -> {
                                requestRepo.updateRequestStatus(item.id, newStatus)
                                Toast.makeText(requireContext(), "Status updated to ${newStatus.name}", Toast.LENGTH_SHORT).show()
                            }
                            item is Booking && newStatus is BookingStatus -> {
                                rideRepo.updateBookingStatus(item.id, newStatus)
                                Toast.makeText(requireContext(), "Booking updated to ${newStatus.name}", Toast.LENGTH_SHORT).show()
                            }
                            item is RideJourney && newStatus is RideStatus -> {
                                if (newStatus == RideStatus.EN_ROUTE) {
                                    val departure = item.ride.departureTime
                                    if (departure != null) {
                                        val now = System.currentTimeMillis()
                                        val maxStartTime = departure.time - (5 * 60 * 1000) // 5 minutes before
                                        if (now < maxStartTime) {
                                            Toast.makeText(requireContext(), "Too early to start! You can start at most 5 minutes before departure.", Toast.LENGTH_LONG).show()
                                            return@launch
                                        }
                                    }
                                }
                                val res = when (newStatus) {
                                    RideStatus.EN_ROUTE -> rideRepo.startRide(item.ride.id, app.weatherService)
                                    RideStatus.COMPLETED -> rideRepo.completeRide(item.ride.id)
                                    RideStatus.CANCELLED -> rideRepo.cancelRide(item.ride.id)
                                    else -> Result.success(Unit)
                                }
                                if (res.isSuccess) {
                                    Toast.makeText(requireContext(), "Journey ${newStatus.name}", Toast.LENGTH_SHORT).show()
                                } else {
                                    Toast.makeText(requireContext(), "Error: ${res.exceptionOrNull()?.message}", Toast.LENGTH_LONG).show()
                                }
                            }
                            // Consolidated pickup for all bookings in a journey
                            item is RideJourney && newStatus is BookingStatus && newStatus == BookingStatus.PICKED_UP -> {
                                var count = 0
                                item.bookings.forEach { b ->
                                    when (b.status) {
                                        BookingStatus.ACCEPTED -> {
                                            rideRepo.updateBookingStatus(b.id, BookingStatus.EN_ROUTE)
                                            rideRepo.updateBookingStatus(b.id, BookingStatus.PICKED_UP)
                                            count++
                                        }
                                        BookingStatus.EN_ROUTE -> {
                                            rideRepo.updateBookingStatus(b.id, BookingStatus.PICKED_UP)
                                            count++
                                        }
                                        else -> Unit
                                    }
                                }
                                Toast.makeText(requireContext(), "Confirmed pickup for $count passengers", Toast.LENGTH_SHORT).show()
                            }
                        }
                    } catch (e: Exception) {
                        android.util.Log.e("MyActiveRides", "Status update failed", e)
                        Toast.makeText(requireContext(), "Update failed: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
            },
            onRideCompleted = { item ->
                val (passengerName, passengerId, id) = when (item) {
                    is RideRequest -> Triple(item.passengerName, item.passengerId ?: "anonymized", item.id)
                    is Booking -> Triple(item.passengerName, item.passengerId ?: "anonymized", item.id)
                    is RideJourney -> {
                        val toRate = item.bookings.firstOrNull { !it.passengerReviewed }
                        if (toRate != null) {
                            Triple(toRate.passengerName, toRate.passengerId ?: "anonymized", toRate.id)
                        } else return@ActiveRideAdapter
                    }
                    else -> return@ActiveRideAdapter
                }
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
            },
            onViewProfile = { userId ->
                val bundle = Bundle().apply { putString("userId", userId) }
                try {
                    NavHostFragment.findNavController(this@MyActiveRidesFragment).navigate(R.id.profileFragment, bundle)
                } catch (e: Exception) {
                    android.util.Log.e("MyActiveRides", "Navigation failed", e)
                }
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
                rideRepo.getDriverBookings(uid).catch { emit(emptyList()) },
                rideRepo.getDriverRides(uid).catch { emit(emptyList()) }
            ) { requests, bookings, rides ->
                val filteredRequests = requests.filter { it.status in activeStatuses && !it.passengerReviewed }
                
                val journeys = mutableListOf<Any>()
                journeys.addAll(filteredRequests)
                
                val bookingsByRide = bookings.groupBy { it.rideId }
                rides.forEach { ride ->
                    val rideBookings = bookingsByRide[ride.id] ?: emptyList()
                    val activeRideBookings = rideBookings.filter { it.status in activeBookingStatuses && !it.passengerReviewed }
                    
                    // Show ride if it's active (OPEN/FULL/EN_ROUTE) even if no bookings yet,
                    // or if it's completed but has pending reviews.
                    if (ride.status == RideStatus.OPEN || ride.status == RideStatus.FULL || ride.status == RideStatus.EN_ROUTE ||
                        (ride.status == RideStatus.COMPLETED && activeRideBookings.isNotEmpty())) {
                        journeys.add(RideJourney(ride, activeRideBookings))
                    }
                }

                journeys.sortedByDescending {
                    when (it) {
                        is RideRequest -> it.createdAt?.time ?: 0L
                        is RideJourney -> it.ride.createdAt?.time ?: 0L
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
                    val status = when(item) {
                        is RideRequest -> item.status.name
                        is RideJourney -> item.ride.status.name
                        is Booking -> item.status.name
                        else -> ""
                    }
                    status != "COMPLETED" && status != "CANCELLED"
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

        val (originStr, destStr) = when(item) {
            is RideRequest -> item.origin to item.destination
            is RideJourney -> item.ride.origin to item.ride.destination
            is Booking -> item.origin to item.destination
            else -> "" to ""
        }

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
    private val onRideCompleted: (Any) -> Unit,
    private val onViewProfile: (String) -> Unit
) : androidx.recyclerview.widget.ListAdapter<Any, ActiveRideAdapter.VH>(DIFF) {
    inner class VH(v: View) : RecyclerView.ViewHolder(v) {
        val tvRoute: TextView = v.findViewById(R.id.tv_route)
        val tvPendingBadge: TextView = v.findViewById(R.id.tv_pending_badge)
        val tvDriver: TextView = v.findViewById(R.id.tv_driver)
        val btnAction: android.widget.Button = v.findViewById(R.id.btn_action)
        val btnCancel: android.widget.Button = v.findViewById(R.id.btn_cancel)
        val btnRate: android.widget.Button = v.findViewById(R.id.btn_rate)
        
        init {
            tvRoute.movementMethod = android.text.method.LinkMovementMethod.getInstance()
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        return VH(LayoutInflater.from(parent.context).inflate(R.layout.item_my_request, parent, false))
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = getItem(position)
        
        val origin = when(item) {
            is RideRequest -> item.origin
            is Booking -> item.origin
            is RideJourney -> item.ride.origin
            else -> ""
        }
        val destination = when(item) {
            is RideRequest -> item.destination
            is Booking -> item.destination
            is RideJourney -> item.ride.destination
            else -> ""
        }
        val status = when(item) {
            is RideRequest -> item.status.name
            is Booking -> item.status.name
            is RideJourney -> item.ride.status.name
            else -> ""
        }
        val isRecurring = if (item is Booking) item.recurring else false
        val isPending = when(item) {
            is RideRequest -> item.isPending
            is Booking -> item.isPending
            is RideJourney -> item.ride.isPending
            else -> false
        }
        val hasNewReqs = item is RideJourney && item.ride.hasNewRequests

        val sb = android.text.SpannableStringBuilder()
        if (isRecurring) sb.append("⟳ ")

        when (item) {
            is RideRequest -> {
                val start = sb.length
                sb.append(item.passengerName)
                item.passengerId?.let { pId ->
                    sb.setSpan(object : android.text.style.ClickableSpan() {
                        override fun onClick(v: View) { onViewProfile(pId) }
                    }, start, sb.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }
            is Booking -> {
                val start = sb.length
                sb.append(item.passengerName)
                item.passengerId?.let { pId ->
                    sb.setSpan(object : android.text.style.ClickableSpan() {
                        override fun onClick(v: View) { onViewProfile(pId) }
                    }, start, sb.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }
            is RideJourney -> {
                if (item.bookings.isEmpty()) {
                    sb.append("No passengers yet")
                } else {
                    item.bookings.forEachIndexed { index, booking ->
                        val start = sb.length
                        sb.append(booking.passengerName)
                        booking.passengerId?.let { pId ->
                            sb.setSpan(object : android.text.style.ClickableSpan() {
                                override fun onClick(v: View) { onViewProfile(pId) }
                            }, start, sb.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        }
                        if (index < item.bookings.size - 1) sb.append(", ")
                    }
                }
            }
        }
        sb.append(": $origin → $destination")
        holder.tvRoute.text = sb
        
        holder.tvPendingBadge.clearAnimation()
        if (hasNewReqs) {
            holder.tvPendingBadge.visibility = View.VISIBLE
            holder.tvPendingBadge.text = "NEW PASSENGERS"
            holder.tvPendingBadge.backgroundTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.RED)
            val anim = android.view.animation.AlphaAnimation(1.0f, 0.2f).apply {
                duration = 500
                repeatMode = android.view.animation.Animation.REVERSE
                repeatCount = android.view.animation.Animation.INFINITE
            }
            holder.tvPendingBadge.startAnimation(anim)
        } else if (isPending) {
            holder.tvPendingBadge.visibility = View.VISIBLE
            holder.tvPendingBadge.text = "PENDING"
            holder.tvPendingBadge.backgroundTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#FF9800"))
        } else {
            holder.tvPendingBadge.visibility = View.GONE
        }

        val (statusText, btnText, nextStatus) = when {
            item is RideRequest && item.status == RequestStatus.ACCEPTED -> Triple("Accepted - Get moving!", "Start Trip", RequestStatus.EN_ROUTE)
            item is RideRequest && item.status == RequestStatus.EN_ROUTE -> Triple("En route to pickup", "Confirm Pickup", RequestStatus.PICKED_UP)
            item is RideRequest && item.status == RequestStatus.PICKED_UP -> Triple("Passenger onboard", "Finish Ride", RequestStatus.COMPLETED)
            item is RideRequest && item.status == RequestStatus.COMPLETED -> Triple("Ride Completed", "", RequestStatus.COMPLETED)

            item is RideJourney && (item.ride.status == RideStatus.OPEN || item.ride.status == RideStatus.FULL) -> Triple("Journey Ready", "Start Journey", RideStatus.EN_ROUTE)
            item is RideJourney && item.ride.status == RideStatus.EN_ROUTE -> {
                val readyToFinish = item.bookings.isEmpty() ||
                    item.bookings.all { it.status == BookingStatus.PICKED_UP || it.status == BookingStatus.COMPLETED }
                if (readyToFinish) Triple("Journey ready to finish", "Finish Journey", RideStatus.COMPLETED)
                else Triple("Journey in progress", "Confirm All Pickups", BookingStatus.PICKED_UP)
            }
            item is RideJourney && item.ride.status == RideStatus.COMPLETED -> Triple("Journey Completed", "", RideStatus.COMPLETED)

            item is Booking && item.status == BookingStatus.ACCEPTED -> Triple("Booking Accepted", "Start Trip", BookingStatus.EN_ROUTE)
            item is Booking && item.status == BookingStatus.EN_ROUTE -> Triple("En route to pickup", "Confirm Pickup", BookingStatus.PICKED_UP)
            item is Booking && item.status == BookingStatus.PICKED_UP -> Triple("Passenger onboard", "Finish Ride", BookingStatus.COMPLETED)
            item is Booking && item.status == BookingStatus.COMPLETED -> Triple("Ride Completed", "", BookingStatus.COMPLETED)

            else -> Triple("Status: $status", "Next Step", RequestStatus.COMPLETED)
        }

        val isCompleted = (item is RideRequest && item.status == RequestStatus.COMPLETED) || 
                          (item is Booking && item.status == BookingStatus.COMPLETED) ||
                          (item is RideJourney && item.ride.status == RideStatus.COMPLETED)

        // Add weather warning if violation exists
        val app = holder.itemView.context.applicationContext as SharISTApp
        var weatherViolation = false
        
        // Use lifecycleScope for async weather check
        val scope = (holder.itemView.context as? androidx.fragment.app.FragmentActivity)?.lifecycleScope 
        
        if (scope != null) {
            scope.launch {
                val violation = when (item) {
                    is RideJourney -> if (item.ride.status == RideStatus.OPEN || item.ride.status == RideStatus.FULL) {
                        app.weatherService.checkWeatherViolation(
                            item.ride.origin,
                            item.ride.departureTime,
                            item.ride.weatherCondition ?: WeatherCondition(WeatherType.NONE)
                        )
                    } else pt.ulisboa.tecnico.sharist.utils.WeatherWarning.NONE
                    is RideRequest -> if (item.status == RequestStatus.ACCEPTED) {
                        app.weatherService.checkWeatherViolation(
                            item.origin,
                            item.requestedTime,
                            item.weatherCondition ?: WeatherCondition(WeatherType.NONE)
                        )
                    } else pt.ulisboa.tecnico.sharist.utils.WeatherWarning.NONE
                    else -> pt.ulisboa.tecnico.sharist.utils.WeatherWarning.NONE
                }

                if (violation == pt.ulisboa.tecnico.sharist.utils.WeatherWarning.WILL_CANCEL) {
                    val warningText = "⚠ Weather Safety Alert: Forecast exceeds limits. You cannot start this ride unless conditions improve."
                    holder.tvDriver.text = "$statusText\n\n$warningText"
                    holder.tvDriver.setTextColor(android.graphics.Color.parseColor("#D32F2F"))
                    holder.btnAction.isEnabled = false
                } else {
                    holder.tvDriver.text = statusText
                    holder.tvDriver.setTextColor(androidx.core.content.ContextCompat.getColor(holder.itemView.context, R.color.text_secondary))
                    holder.btnAction.isEnabled = true
                }
            }
        } else {
            holder.tvDriver.text = statusText
            holder.tvDriver.setTextColor(androidx.core.content.ContextCompat.getColor(holder.itemView.context, R.color.text_secondary))
        }

        holder.btnAction.visibility = if (btnText.isNotEmpty()) View.VISIBLE else View.GONE
        holder.btnAction.text = btnText
        holder.btnAction.setOnClickListener {
            onUpdateStatus(item, nextStatus)
        }

        holder.btnCancel.visibility = if (isCompleted) View.GONE else View.VISIBLE
        holder.btnCancel.text = "Cancel"
        holder.btnCancel.setOnClickListener {
            when (item) {
                is RideRequest -> onUpdateStatus(item, RequestStatus.CANCELLED)
                is Booking -> onUpdateStatus(item, BookingStatus.CANCELLED)
                is RideJourney -> onUpdateStatus(item, RideStatus.CANCELLED)
            }
        }
        
        val isReviewed = when(item) {
            is RideRequest -> item.passengerReviewed
            is Booking -> item.passengerReviewed
            is RideJourney -> item.bookings.all { it.passengerReviewed }
            else -> true
        }

        holder.btnRate.visibility = if (isCompleted && !isReviewed) View.VISIBLE else View.GONE
        if (item is RideJourney && isCompleted && !isReviewed) {
            val count = item.bookings.count { !it.passengerReviewed }
            holder.btnRate.text = if (count > 1) "Rate Passengers ($count)" else "Rate Passenger"
        } else if (isCompleted) {
            holder.btnRate.text = "Rate Passenger"
        }
        holder.btnRate.setOnClickListener { onRideCompleted(item) }
    }

    companion object {
        private val DIFF = object : androidx.recyclerview.widget.DiffUtil.ItemCallback<Any>() {
            override fun areItemsTheSame(oldItem: Any, newItem: Any): Boolean {
                return when {
                    oldItem is RideRequest && newItem is RideRequest -> oldItem.id == newItem.id
                    oldItem is Booking && newItem is Booking -> oldItem.id == newItem.id
                    oldItem is RideJourney && newItem is RideJourney -> oldItem.ride.id == newItem.ride.id
                    else -> false
                }
            }
            override fun areContentsTheSame(oldItem: Any, newItem: Any): Boolean {
                return when {
                    oldItem is RideRequest && newItem is RideRequest -> oldItem == newItem
                    oldItem is Booking && newItem is Booking -> oldItem == newItem
                    oldItem is RideJourney && newItem is RideJourney -> oldItem == newItem
                    else -> false
                }
            }
        }
    }
}
