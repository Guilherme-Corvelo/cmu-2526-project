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

    private val _weatherCondition = MutableStateFlow(WeatherCondition())
    val weatherCondition: StateFlow<WeatherCondition> = _weatherCondition.asStateFlow()

    sealed class BookingState {
        object Idle    : BookingState()
        object Loading : BookingState()
        data class Success(val bookingId: String, val isPending: Boolean) : BookingState()
        data class Error(val message: String)   : BookingState()
    }

    fun loadRide(rideId: String) {
        viewModelScope.launch {
            val currentUid = userRepo.currentUid
            
            // Background fetch to ensure latest data
            rideRepo.getRide(rideId)

            launch {
                rideRepo.observeRide(rideId).collect { ride ->
                    if (ride != null) {
                        _ride.value = ride
                        _isRideOwner.value = (currentUid != null && ride.driverId == currentUid)
                        checkWeather(ride)
                    }
                }
            }

            if (currentUid != null) {
                launch {
                    rideRepo.getRideBookings(rideId)
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

    fun setWeatherCondition(condition: WeatherCondition) {
        _weatherCondition.value = condition
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
                recurring        = recurring,
                weatherCondition = _weatherCondition.value
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
            val currentRide = _ride.value
            if (currentRide?.departureTime != null) {
                val now = System.currentTimeMillis()
                val maxStartTime = currentRide.departureTime!!.time - (5 * 60 * 1000)
                if (now < maxStartTime) {
                    _bookingState.value = BookingState.Error("Too early to start! You can start at most 5 minutes before departure.")
                    return@launch
                }
            }
            _bookingState.value = BookingState.Loading
            rideRepo.startRide(rideId, weatherSvc).onSuccess {
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
    private lateinit var cvWeatherPrefs: View
    private lateinit var actvWeatherType: AutoCompleteTextView
    private lateinit var tilThreshold: com.google.android.material.textfield.TextInputLayout
    private lateinit var etThreshold: com.google.android.material.textfield.TextInputEditText
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
        cvWeatherPrefs   = findViewById(R.id.cv_weather_prefs)
        actvWeatherType  = findViewById(R.id.actv_weather_type)
        tilThreshold     = findViewById(R.id.til_threshold)
        etThreshold      = findViewById(R.id.et_threshold)
        progressBar      = findViewById(R.id.progress_bar)

        setupWeatherPreferences()
    }

    private fun setupWeatherPreferences() {
        val weatherTypes = WeatherType.values().map { it.name }
        val adapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, weatherTypes)
        actvWeatherType.setAdapter(adapter)
        actvWeatherType.setText(WeatherType.NONE.name, false)

        actvWeatherType.setOnItemClickListener { _, _, position, _ ->
            val selectedType = WeatherType.valueOf(weatherTypes[position])
            tilThreshold.visibility = if (selectedType == WeatherType.TOO_HOT || selectedType == WeatherType.TOO_COLD) {
                View.VISIBLE
            } else {
                View.GONE
            }
            updateWeatherCondition()
        }

        etThreshold.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                updateWeatherCondition()
            }
        })
    }

    private fun updateWeatherCondition() {
        val type = WeatherType.valueOf(actvWeatherType.text.toString())
        val threshold = etThreshold.text.toString().toDoubleOrNull()
        viewModel.setWeatherCondition(WeatherCondition(type, threshold))
    }

    private fun observeViewModel(rideId: String) {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                // Combined flow for all state-dependent UI changes
                combine(
                    viewModel.ride.filterNotNull(),
                    viewModel.isRideOwner,
                    viewModel.weatherWarning,
                    viewModel.pendingBookings,
                    viewModel.joinedBookings
                ) { ride, isOwner, warning, pending, joined ->
                    // 1. Update core UI data
                    populateUI(ride, rideId)

                    val isCancelled = ride.status == RideStatus.CANCELLED
                    val isCompleted = ride.status == RideStatus.COMPLETED
                    val isEnRoute = ride.status == RideStatus.EN_ROUTE
                    val isOpenOrFull = ride.status == RideStatus.OPEN || ride.status == RideStatus.FULL

                    // 2. Weather Warning UI
                    cvWeatherWarning.visibility = if (warning == WeatherWarning.WILL_CANCEL) View.VISIBLE else View.GONE
                    if (warning == WeatherWarning.WILL_CANCEL) {
                        val cond = ride.weatherCondition
                        val thresholdValue = cond.threshold ?: (if (cond.type == WeatherType.TOO_HOT) 35.0 else 5.0)
                        val conditionDetail = when (cond.type) {
                            WeatherType.TOO_HOT -> "high temperatures (Threshold: ${thresholdValue}°C)"
                            WeatherType.TOO_COLD -> "low temperatures (Threshold: ${thresholdValue}°C)"
                            WeatherType.RAIN -> "rain"
                            else -> "weather conditions"
                        }
                        tvWeatherText.text = "⚠ Safety Note: This ride currently violates weather safety rules for $conditionDetail. It will remain active until departure time in case the forecast improves, but booking is currently restricted."
                    }

                    // 3. Status-based Visibilities & Button Text
                    btnBook.text = when (ride.status) {
                        RideStatus.CANCELLED -> "Ride Cancelled"
                        RideStatus.COMPLETED -> "Ride Completed"
                        RideStatus.EN_ROUTE -> "Ride in Progress"
                        else -> "Book Ride"
                    }

                    if (isOwner) {
                        btnBook.visibility = View.GONE
                        cbRecurringBooking.visibility = View.GONE
                        cvWeatherPrefs.visibility = View.GONE
                        
                        btnCancelRide.visibility = if (isOpenOrFull) View.VISIBLE else View.GONE
                        btnStartRide.visibility = if (isOpenOrFull) View.VISIBLE else View.GONE
                        btnFinishRide.visibility = if (isEnRoute) View.VISIBLE else View.GONE

                        // Pending Requests Logic
                        if (pending.isEmpty()) {
                            tvRequests.visibility = View.GONE
                            layoutRequestActions.visibility = View.GONE
                            selectedPendingBooking = null
                        } else {
                            val first = pending.first()
                            selectedPendingBooking = first
                            tvRequests.visibility = View.VISIBLE
                            layoutRequestActions.visibility = View.VISIBLE
                            val count = pending.size
                            val seatsLabel = if (first.seatsRequested == 1) "seat" else "seats"
                            val suffix = if (count > 1) " (+${count - 1} more)" else ""
                            tvRequests.text = "Pending request: ${first.passengerName} (${first.seatsRequested} $seatsLabel)$suffix"
                            btnAcceptRequest.setOnClickListener { viewModel.respondToBooking(first.id, true) }
                            btnRejectRequest.setOnClickListener { viewModel.respondToBooking(first.id, false) }
                            cvWeatherPrefs.visibility = View.GONE
                        }

                        // Profile button state for owner
                        btnViewPassengerProfile.visibility = if (pending.isNotEmpty() || joined.isNotEmpty()) View.VISIBLE else View.GONE
                        if (joined.isNotEmpty()) {
                            val first = joined.first()
                            val more = if (joined.size > 1) " (+${joined.size - 1} more)" else ""
                            btnViewPassengerProfile.text = "View joined: ${first.passengerName}$more"
                        } else if (pending.isNotEmpty()) {
                            btnViewPassengerProfile.text = "View passenger profile"
                        }
                    } else {
                        btnBook.visibility = if (isCancelled || isCompleted || isEnRoute) View.GONE else View.VISIBLE
                        btnCancelRide.visibility = View.GONE
                        btnStartRide.visibility = View.GONE
                        btnFinishRide.visibility = View.GONE
                        cbRecurringBooking.visibility = if (ride.periodic && !isCancelled && !isCompleted) View.VISIBLE else View.GONE
                        cvWeatherPrefs.visibility = if (isCancelled || isCompleted || isEnRoute) View.GONE else View.VISIBLE
                        tvRequests.visibility = View.GONE
                        layoutRequestActions.visibility = View.GONE
                        btnViewPassengerProfile.visibility = View.GONE
                    }

                    // 4. Safety locks (Last word on enablements)
                    if (warning == WeatherWarning.WILL_CANCEL) {
                        btnBook.isEnabled = false
                        btnStartRide.isEnabled = false
                    } else {
                        if (isOwner) {
                            btnStartRide.isEnabled = isOpenOrFull
                        } else {
                            btnBook.isEnabled = ride.seatsAvailable > 0 && isOpenOrFull
                        }
                    }
                }.collect()

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
                val pId = booking.passengerId
                if (pId != null) {
                    showPassengerProfileDialog(pId, booking.passengerName)
                } else {
                    Toast.makeText(this@RideDetailActivity, "User profile is anonymized for privacy.", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun populateUI(ride: Ride, rideId: String) {
        findViewById<View>(R.id.layout_content).visibility = View.VISIBLE
        title = "Ride detail"
        tvDriverName.text = ride.driverName
        tvRating.text     = "★ %.1f".format(ride.driverRating)
        tvRoute.text      = "${ride.origin}  →  ${ride.destination}"
        tvDeparture.text  = ride.departureTime?.let { dateFmt.format(it) } ?: "—"
        tvSeats.text      = "${ride.seatsAvailable} of ${ride.seatsTotal} seats available"
        tvPrice.text      = "€%.2f per seat".format(ride.pricePerSeat)

        if (ride.periodic) {
            tvPeriodicInfo.visibility = View.VISIBLE
            tvPeriodicInfo.text = if (ride.periodicLabel.isNotBlank()) 
                "This is a periodic ride (${ride.periodicLabel})" 
                else "This is a periodic ride"
        } else {
            tvPeriodicInfo.visibility = View.GONE
        }

        val app = application as SharISTApp
        ImageLoader.load(
            imageView   = ivCarPhoto,
            url         = ride.carPhotoUrl,
            placeholder = R.drawable.ic_car_placeholder,
            network     = app.networkMonitor
        )

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
