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

    sealed class BookingState {
        object Idle    : BookingState()
        object Loading : BookingState()
        data class Success(val bookingId: String, val isPending: Boolean) : BookingState()
        data class Error(val message: String)   : BookingState()
    }

    fun loadRide(rideId: String) {
        viewModelScope.launch {
            val ride = rideRepo.getRide(rideId) ?: return@launch
            _ride.value = ride
            checkWeather(ride)
        }
    }

    private suspend fun checkWeather(ride: Ride) {
        if (ride.weatherCondition == null || ride.weatherCondition.type == WeatherType.NONE) return

        // Map origin to nearest IPMA district (simplified: default to Lisbon)
        val locationId = pt.ulisboa.tecnico.sharist.utils.IpmaDistrict.LISBOA
        weatherSvc.getForecast(locationId)
            .onSuccess { forecast ->
                _weatherWarning.value = weatherSvc.evaluateCondition(ride.weatherCondition, forecast)
            }
    }

    fun bookRide(rideId: String, seats: Int) {
        val uid = userRepo.currentUid ?: run {
            _bookingState.value = BookingState.Error("Not logged in")
            return
        }
        _bookingState.value = BookingState.Loading
        viewModelScope.launch {
            val ride = _ride.value ?: return@launch
            val booking = Booking(
                rideId           = rideId,
                passengerId      = uid,
                passengerName    = "Me", // fetch from user profile in full impl
                seatsRequested   = seats,
                totalPrice       = ride.pricePerSeat * seats
            )
            rideRepo.bookRide(booking)
                .onSuccess { id ->
                    val isPending = id.startsWith("pending_")
                    _bookingState.value = BookingState.Success(id, isPending)
                    // Deduct balance if not using PayPal
                    userRepo.updateBalance(uid, -(ride.pricePerSeat * seats))
                }
                .onFailure { e ->
                    _bookingState.value = BookingState.Error(e.message ?: "Booking failed")
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
    private lateinit var tvWeatherWarning: TextView
    private lateinit var btnBook: Button
    private lateinit var progressBar: ProgressBar

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
        tvWeatherWarning = findViewById(R.id.tv_weather_warning)
        btnBook          = findViewById(R.id.btn_book)
        progressBar      = findViewById(R.id.progress_bar)
    }

    private fun observeViewModel(rideId: String) {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {

                launch {
                    viewModel.ride.filterNotNull().collect { ride -> populateUI(ride, rideId) }
                }

                launch {
                    viewModel.weatherWarning.collect { warning ->
                        tvWeatherWarning.visibility =
                            if (warning == WeatherWarning.WILL_CANCEL) View.VISIBLE else View.GONE
                        if (warning == WeatherWarning.WILL_CANCEL) {
                            tvWeatherWarning.text = "⚠ This ride may be cancelled due to weather conditions"
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
    }

    private fun populateUI(ride: Ride, rideId: String) {
        title = "Ride detail"
        tvDriverName.text = ride.driverName
        tvRating.text     = "★ %.1f  (${ride.driverRating})" .format(ride.driverRating)
        tvRoute.text      = "${ride.origin}  →  ${ride.destination}"
        tvDeparture.text  = ride.departureTime?.let { dateFmt.format(it) } ?: "—"
        tvSeats.text      = "${ride.seatsAvailable} of ${ride.seatsTotal} seats available"
        tvPrice.text      = "€%.2f per seat".format(ride.pricePerSeat)

        val app = application as SharISTApp
        ImageLoader.load(
            imageView   = ivCarPhoto,
            url         = ride.carPhotoUrl,
            placeholder = R.drawable.ic_car_placeholder,
            network     = app.networkMonitor
        )

        btnBook.isEnabled = ride.seatsAvailable > 0
        btnBook.setOnClickListener {
            if (ride.seatsAvailable > 0) viewModel.bookRide(rideId, seats = 1)
        }
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }
}
