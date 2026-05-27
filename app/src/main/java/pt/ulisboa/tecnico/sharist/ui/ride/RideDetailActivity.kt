package pt.ulisboa.tecnico.sharist.ui.ride

import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import pt.ulisboa.tecnico.sharist.R
import pt.ulisboa.tecnico.sharist.SharISTApp
import pt.ulisboa.tecnico.sharist.data.model.*
import pt.ulisboa.tecnico.sharist.utils.ImageLoader
import pt.ulisboa.tecnico.sharist.utils.WeatherService
import pt.ulisboa.tecnico.sharist.utils.WeatherWarning
import java.text.SimpleDateFormat
import java.util.*

// ─── ViewModel ────────────────────────────────────────────────────────────────

class RideDetailViewModel(
    private val rideRepo: pt.ulisboa.tecnico.sharist.data.repository.RideRepository,
    private val userRepo: pt.ulisboa.tecnico.sharist.data.repository.UserRepository,
    private val weatherSvc: WeatherService
) : ViewModel() {

    private val _ride = MutableStateFlow<Ride?>(null)
    val ride: StateFlow<Ride?> = _ride.asStateFlow()

    private val _weatherWarning = MutableStateFlow(WeatherWarning.NONE)
    val weatherWarning: StateFlow<WeatherWarning> = _weatherWarning.asStateFlow()

    private val _bookingState = MutableStateFlow<BookingState>(BookingState.Idle)
    val bookingState: StateFlow<BookingState> = _bookingState.asStateFlow()
    private val _pendingBookings = MutableStateFlow<List<Booking>>(emptyList())
    val pendingBookings: StateFlow<List<Booking>> = _pendingBookings.asStateFlow()
    private val _joinedBookings = MutableStateFlow<List<Booking>>(emptyList())
    val joinedBookings: StateFlow<List<Booking>> = _joinedBookings.asStateFlow()
    private val _isRideOwner = MutableStateFlow(false)
    val isRideOwner: StateFlow<Boolean> = _isRideOwner.asStateFlow()

    sealed class BookingState {
        object Idle    : BookingState()
        object Loading : BookingState()
        data class Success(val bookingId: String, val isPending: Boolean) : BookingState()
        data class Error(val message: String)   : BookingState()
    }

    fun loadRide(rideId: String) {
        viewModelScope.launch {
            val currentUid = userRepo.currentUid
            val ride = rideRepo.getRide(rideId) ?: return@launch
            _ride.value = ride
            _isRideOwner.value = (currentUid != null && ride.driverId == currentUid)

            if (currentUid != null && ride.driverId == currentUid) {
                launch {
                    rideRepo.getRideBookings(ride.id)
                        .catch { e ->
                            android.util.Log.e("RideDetailViewModel", "Error fetching bookings", e)
                            emit(emptyList())
                        }
                        .collect { bookings ->
                            _pendingBookings.value = bookings.filter { it.status == BookingStatus.PENDING }
                            _joinedBookings.value = bookings.filter {
                                it.status == BookingStatus.ACCEPTED || it.status == BookingStatus.EN_ROUTE || it.status == BookingStatus.PICKED_UP
                            }
                        }
                }
            }
            checkWeather(ride)
        }
    }

    private suspend fun checkWeather(ride: Ride) {
        if (ride.weatherCondition.type == WeatherType.NONE) return

        // Map origin to nearest IPMA district (simplified: default to Lisbon)
        val locationId = pt.ulisboa.tecnico.sharist.utils.IpmaDistrict.LISBOA
        weatherSvc.getForecast(locationId)
            .onSuccess { forecast ->
                _weatherWarning.value = weatherSvc.evaluateCondition(ride.weatherCondition, forecast)
            }
    }

    fun bookRide(rideId: String, seats: Int, recurring: Boolean = false) {
        val uid = userRepo.currentUid ?: run {
            _bookingState.value = BookingState.Error("Not logged in")
            return
        }
        val ride = _ride.value ?: return
        if (ride.driverId == uid) {
            _bookingState.value = BookingState.Error("You cannot book your own ride")
            return
        }
        _bookingState.value = BookingState.Loading
        viewModelScope.launch {
            val ride = _ride.value ?: return@launch
            val user = userRepo.getUser(uid)
            val booking = Booking(
                rideId           = rideId,
                passengerId      = uid,
                passengerName    = user?.displayName ?: "Passenger",
                passengerRating  = user?.rating ?: 5.0,
                passengerPhotoUrl = user?.photoUrl,
                seatsRequested   = seats,
                totalPrice       = ride.pricePerSeat * seats,
                origin           = ride.origin,
                destination      = ride.destination,
                departureTime    = ride.departureTime,
                driverName       = ride.driverName,
                driverId         = ride.driverId,
                recurring        = recurring
            )
            rideRepo.bookRide(booking)
                .onSuccess { id ->
                    val isPending = id.startsWith("pending_")
                    _bookingState.value = BookingState.Success(id, isPending)
                }
                .onFailure { e ->
                    _bookingState.value = BookingState.Error(e.message ?: "Booking failed")
                }
        }
    }

    fun cancelRide(rideId: String) {
        viewModelScope.launch {
            _bookingState.value = BookingState.Loading
            rideRepo.cancelRide(rideId).onSuccess {
                _ride.value = _ride.value?.copy(status = RideStatus.CANCELLED)
                _bookingState.value = BookingState.Idle
            }.onFailure {
                _bookingState.value = BookingState.Error(it.message ?: "Cancel failed")
            }
        }
    }

    fun finishRide(rideId: String) {
        viewModelScope.launch {
            _bookingState.value = BookingState.Loading
            try {
                // To finish a ride, we need to finish all bookings and the ride itself
                val bookings = rideRepo.getRideBookings(rideId).first()
                bookings.forEach { booking ->
                    if (booking.status == BookingStatus.ACCEPTED || booking.status == BookingStatus.EN_ROUTE || booking.status == BookingStatus.PICKED_UP) {
                        rideRepo.updateBookingStatus(booking.id, BookingStatus.COMPLETED)
                    } else if (booking.status == BookingStatus.PENDING) {
                        // Reject pending bookings if finishing the ride
                        rideRepo.updateBookingStatus(booking.id, BookingStatus.REJECTED)
                    }
                }
                
                rideRepo.completeRide(rideId).onSuccess {
                    _ride.value = _ride.value?.copy(status = RideStatus.COMPLETED)
                    _bookingState.value = BookingState.Success("completed", false)
                }.onFailure {
                    _bookingState.value = BookingState.Error(it.message ?: "Finish failed")
                }
            } catch (e: Exception) {
                _bookingState.value = BookingState.Error(e.message ?: "An error occurred while finishing the ride")
            }
        }
    }

    fun startRide(rideId: String) {
        viewModelScope.launch {
            _bookingState.value = BookingState.Loading
            rideRepo.startRide(rideId).onSuccess {
                _ride.value = _ride.value?.copy(status = RideStatus.EN_ROUTE)
                _bookingState.value = BookingState.Success("started", false)
            }.onFailure {
                _bookingState.value = BookingState.Error(it.message ?: "Start failed")
            }
        }
    }

    fun respondToBooking(bookingId: String, accepted: Boolean) {
        viewModelScope.launch {
            runCatching {
                rideRepo.updateBookingStatus(
                    bookingId,
                    if (accepted) BookingStatus.ACCEPTED else BookingStatus.REJECTED
                )
            }.onFailure {
                _bookingState.value = BookingState.Error(it.message ?: "Could not update booking")
            }
        }
    }
}

// ─── Activity ─────────────────────────────────────────────────────────────────

class RideDetailActivity : AppCompatActivity() {

    private val viewModel: RideDetailViewModel by viewModels {
        object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val app = application as SharISTApp
                @Suppress("UNCHECKED_CAST")
                return RideDetailViewModel(
                    app.rideRepository, app.userRepository, WeatherService()
                ) as T
            }
        }
    }

    private lateinit var ivCarPhoto: ImageView
    private lateinit var tvDriverName: TextView
    private lateinit var tvRating: TextView
    private lateinit var tvRoute: TextView
    private lateinit var tvDeparture: TextView
    private lateinit var tvSeats: TextView
    private lateinit var tvPrice: TextView
    private lateinit var tvPeriodicInfo: TextView
    private lateinit var cbRecurringBooking: CheckBox
    private lateinit var cvWeatherWarning: View
    private lateinit var tvWeatherText: TextView
    private lateinit var tvRequests: TextView
    private lateinit var layoutRequestActions: LinearLayout
    private lateinit var btnAcceptRequest: Button
    private lateinit var btnRejectRequest: Button
    private lateinit var btnViewPassengerProfile: Button
    private lateinit var btnBook: Button
    private lateinit var btnStartRide: Button
    private lateinit var btnFinishRide: Button
    private lateinit var btnCancelRide: Button
    private lateinit var progressBar: ProgressBar
    private var selectedPendingBooking: Booking? = null

    private val dateFmt = SimpleDateFormat("EEE, dd MMM yyyy  HH:mm", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_ride_detail)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        bindViews()
        val rideId = intent.getStringExtra("RIDE_ID") ?: return
        viewModel.loadRide(rideId)
        observeViewModel(rideId)
    }

    private fun bindViews() {
        ivCarPhoto       = findViewById(R.id.iv_car_photo)
        tvDriverName     = findViewById(R.id.tv_driver_name)
        tvRating         = findViewById(R.id.tv_rating)
        tvRoute          = findViewById(R.id.tv_route)
        tvDeparture      = findViewById(R.id.tv_departure)
        tvSeats          = findViewById(R.id.tv_seats)
        tvPrice          = findViewById(R.id.tv_price)
        tvPeriodicInfo   = findViewById(R.id.tv_periodic_info)
        cbRecurringBooking = findViewById(R.id.cb_recurring_booking)
        cvWeatherWarning = findViewById(R.id.tv_weather_warning)
        tvWeatherText    = findViewById(R.id.tv_weather_text)
        tvRequests       = findViewById(R.id.tv_requests)
        layoutRequestActions = findViewById(R.id.layout_request_actions)
        btnAcceptRequest = findViewById(R.id.btn_accept_request)
        btnRejectRequest = findViewById(R.id.btn_reject_request)
        btnViewPassengerProfile = findViewById(R.id.btn_view_passenger_profile)
        btnBook          = findViewById(R.id.btn_book)
        btnStartRide     = findViewById(R.id.btn_start_ride)
        btnFinishRide    = findViewById(R.id.btn_finish_ride)
        btnCancelRide    = findViewById(R.id.btn_cancel_ride)
        progressBar      = findViewById(R.id.progress_bar)
    }

    private fun observeViewModel(rideId: String) {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {

                launch {
                    viewModel.ride.filterNotNull().collect { ride -> populateUI(ride, rideId) }
                }
                launch {
                    viewModel.isRideOwner.collect { isOwner ->
                        val ride = viewModel.ride.value
                        val isCancelled = ride?.status == RideStatus.CANCELLED
                        val isCompleted = ride?.status == RideStatus.COMPLETED

                        if (isOwner) {
                            btnBook.visibility = View.GONE
                            btnCancelRide.visibility = if (isCancelled || isCompleted) View.GONE else View.VISIBLE
                            cbRecurringBooking.visibility = View.GONE
                            
                            // Show Start/Finish based on status
                            btnStartRide.visibility = if (ride?.status == RideStatus.OPEN || ride?.status == RideStatus.FULL) View.VISIBLE else View.GONE
                            btnFinishRide.visibility = if (ride?.status == RideStatus.EN_ROUTE) View.VISIBLE else View.GONE
                        } else {
                            btnBook.visibility = if (isCancelled || isCompleted || ride?.status == RideStatus.EN_ROUTE) View.GONE else View.VISIBLE
                            btnCancelRide.visibility = View.GONE
                            btnStartRide.visibility = View.GONE
                            btnFinishRide.visibility = View.GONE
                            if (ride?.periodic == true) {
                                cbRecurringBooking.visibility = View.VISIBLE
                            }
                        }
                    }
                }
                launch {
                    viewModel.pendingBookings.collect { requests ->
                        if (!viewModel.isRideOwner.value || requests.isEmpty()) {
                            tvRequests.visibility = View.GONE
                            layoutRequestActions.visibility = View.GONE
                            btnViewPassengerProfile.visibility = View.GONE
                            selectedPendingBooking = null
                        } else {
                            val first = requests.first()
                            selectedPendingBooking = first
                            tvRequests.visibility = View.VISIBLE
                            layoutRequestActions.visibility = View.VISIBLE
                            btnViewPassengerProfile.visibility = View.VISIBLE
                            val count = requests.size
                            val seatsLabel = if (first.seatsRequested == 1) "seat" else "seats"
                            val suffix = if (count > 1) " (+${count - 1} more)" else ""
                            tvRequests.text = "Pending request: ${first.passengerName} (${first.seatsRequested} $seatsLabel)$suffix"
                            btnAcceptRequest.setOnClickListener { viewModel.respondToBooking(first.id, true) }
                            btnRejectRequest.setOnClickListener { viewModel.respondToBooking(first.id, false) }
                        }
                    }
                }
                launch {
                    viewModel.joinedBookings.collect { joined ->
                        if (viewModel.isRideOwner.value && joined.isNotEmpty()) {
                            val first = joined.first()
                            val more = if (joined.size > 1) " (+${joined.size - 1} more)" else ""
                            btnViewPassengerProfile.visibility = View.VISIBLE
                            btnViewPassengerProfile.text = "View joined: ${first.passengerName}$more"
                        } else if (viewModel.isRideOwner.value) {
                            btnViewPassengerProfile.text = "View passenger profile"
                        }
                    }
                }

                launch {
                    viewModel.weatherWarning.collect { warning ->
                        cvWeatherWarning.visibility =
                            if (warning == WeatherWarning.WILL_CANCEL) View.VISIBLE else View.GONE
                        if (warning == WeatherWarning.WILL_CANCEL) {
                            tvWeatherText.text = "⚠ This ride may be cancelled due to weather conditions"
                            btnBook.isEnabled = false
                        }
                    }
                }

                launch {
                    viewModel.bookingState.collect { state ->
                        progressBar.visibility =
                            if (state is RideDetailViewModel.BookingState.Loading) View.VISIBLE else View.GONE

                        when (state) {
                            is RideDetailViewModel.BookingState.Success -> {
                                val msg = if (state.isPending)
                                    "Booking queued – will be confirmed when you're back online"
                                else
                                    "Booking confirmed! ID: ${state.bookingId}"
                                AlertDialog.Builder(this@RideDetailActivity)
                                    .setTitle("Done")
                                    .setMessage(msg)
                                    .setPositiveButton("OK") { _, _ -> finish() }
                                    .show()
                            }
                            is RideDetailViewModel.BookingState.Error ->
                                Toast.makeText(this@RideDetailActivity, state.message, Toast.LENGTH_LONG).show()
                            else -> Unit
                        }
                    }
                }
            }
        }

        btnViewPassengerProfile.setOnClickListener {
            val booking = selectedPendingBooking ?: viewModel.joinedBookings.value.firstOrNull()
            if (booking == null) {
                Toast.makeText(this, "No passengers have joined yet.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            lifecycleScope.launch {
                showPassengerProfileDialog(booking.passengerId, booking.passengerName)
            }
        }
    }

    private fun populateUI(ride: Ride, rideId: String) {
        title = "Ride detail"
        tvDriverName.text = ride.driverName
        tvRating.text     = "★ %.1f  (${ride.driverRating})" .format(ride.driverRating)
        tvRoute.text      = "${ride.origin}  →  ${ride.destination}"
        tvDeparture.text  = ride.departureTime?.let { dateFmt.format(it) } ?: "—"
        tvSeats.text      = "${ride.seatsAvailable} of ${ride.seatsTotal} seats available"
        tvPrice.text      = "€%.2f per seat".format(ride.pricePerSeat)

        if (ride.periodic) {
            tvPeriodicInfo.visibility = View.VISIBLE
            tvPeriodicInfo.text = if (ride.periodicLabel.isNotBlank()) 
                "This is a periodic ride (${ride.periodicLabel})" 
                else "This is a periodic ride"
            
            // Re-check visibility based on updated isRideOwner flow
            cbRecurringBooking.visibility = if (viewModel.isRideOwner.value) View.GONE else View.VISIBLE
        } else {
            tvPeriodicInfo.visibility = View.GONE
            cbRecurringBooking.visibility = View.GONE
        }

        val app = application as SharISTApp
        ImageLoader.load(
            imageView   = ivCarPhoto,
            url         = ride.carPhotoUrl,
            placeholder = R.drawable.ic_car_placeholder,
            network     = app.networkMonitor
        )

        btnBook.isEnabled = ride.seatsAvailable > 0 && !viewModel.isRideOwner.value
        btnBook.setOnClickListener {
            if (ride.seatsAvailable > 0) {
                viewModel.bookRide(rideId, seats = 1, recurring = cbRecurringBooking.isChecked)
            }
        }

        btnCancelRide.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Cancel Ride")
                .setMessage("Are you sure you want to cancel this ride?")
                .setPositiveButton("Yes") { _, _ -> viewModel.cancelRide(rideId) }
                .setNegativeButton("No", null)
                .show()
        }

        btnStartRide.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Start Ride")
                .setMessage("Are you ready to depart?")
                .setPositiveButton("Start") { _, _ -> viewModel.startRide(rideId) }
                .setNegativeButton("Not yet", null)
                .show()
        }

        btnFinishRide.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Finish Ride")
                .setMessage("Have you reached your destination?")
                .setPositiveButton("Finish") { _, _ -> viewModel.finishRide(rideId) }
                .setNegativeButton("No", null)
                .show()
        }

        if (ride.status == RideStatus.CANCELLED) {
            btnBook.isEnabled = false
            btnBook.text = "Ride Cancelled"
            btnCancelRide.visibility = View.GONE
            btnStartRide.visibility = View.GONE
            btnFinishRide.visibility = View.GONE
        } else if (ride.status == RideStatus.COMPLETED) {
            btnBook.isEnabled = false
            btnBook.text = "Ride Completed"
            btnCancelRide.visibility = View.GONE
            btnStartRide.visibility = View.GONE
            btnFinishRide.visibility = View.GONE
        } else if (ride.status == RideStatus.EN_ROUTE) {
            btnBook.isEnabled = false
            btnBook.text = "Ride in Progress"
            btnCancelRide.visibility = View.GONE
            btnStartRide.visibility = View.GONE
            if (viewModel.isRideOwner.value) {
                btnFinishRide.visibility = View.VISIBLE
            }
        }
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }

    private suspend fun showPassengerProfileDialog(passengerId: String, fallbackName: String) {
        val app = application as SharISTApp
        val user = app.userRepository.getUser(passengerId)
        val reviews = app.requestRepository.getReviewsForUser(passengerId).first()
        val avgRating = if (reviews.isEmpty()) null else reviews.map { it.rating }.average()
        val recentComments = reviews
            .mapNotNull { it.comment.takeIf { c -> c.isNotBlank() } }
            .take(3)
            .ifEmpty { listOf("No written comments yet.") }

        val profileText = buildString {
            appendLine("Name: ${user?.displayName ?: fallbackName}")
            appendLine("Email: ${user?.email ?: "N/A"}")
            appendLine("Role: ${if (user?.driver == true) "Driver" else "Passenger"}")
            appendLine("Ratings: ${avgRating?.let { "%.1f".format(it) } ?: "N/A"} (${reviews.size} reviews)")
            appendLine()
            appendLine("Recent comments:")
            recentComments.forEach { appendLine("• $it") }
        }

        AlertDialog.Builder(this)
            .setTitle("Passenger profile")
            .setMessage(profileText)
            .setPositiveButton("Close", null)
            .show()
    }
}
