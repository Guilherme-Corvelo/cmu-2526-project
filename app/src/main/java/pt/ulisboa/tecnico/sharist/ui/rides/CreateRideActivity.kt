package pt.ulisboa.tecnico.sharist.ui.rides

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import pt.ulisboa.tecnico.sharist.R
import pt.ulisboa.tecnico.sharist.SharISTApp
import pt.ulisboa.tecnico.sharist.data.model.*
import pt.ulisboa.tecnico.sharist.ui.map.MapDemoData
import java.text.SimpleDateFormat
import java.util.*

class CreateRideActivity : AppCompatActivity() {

    private val rideRepo by lazy { (application as SharISTApp).rideRepository }
    private val userRepo by lazy { (application as SharISTApp).userRepository }

    private var selectedCalendar = Calendar.getInstance()
    private val dateTimeFormat = SimpleDateFormat("EEE, dd MMM yyyy HH:mm", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_create_ride)

        val toolbar = findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setHomeAsUpIndicator(R.drawable.ic_close_white)

        val etOrigin = findViewById<AutoCompleteTextView>(R.id.et_origin)
        val etDest = findViewById<AutoCompleteTextView>(R.id.et_destination)
        val btnPickTime = findViewById<Button>(R.id.btn_pick_time)
        val tvDateTime = findViewById<TextView>(R.id.tv_date_time)
        val etSeats = findViewById<EditText>(R.id.et_seats)
        val tvAutoPrice = findViewById<TextView>(R.id.tv_auto_price)
        val switchPeriodic = findViewById<CompoundButton>(R.id.switch_periodic)
        val spinnerPeriodicity = findViewById<Spinner>(R.id.spinner_periodicity)
        val spinnerWeather = findViewById<Spinner>(R.id.spinner_weather)
        val etThreshold = findViewById<EditText>(R.id.et_threshold)
        val tilThreshold = findViewById<View>(R.id.til_threshold)
        val btnCreate = findViewById<Button>(R.id.btn_create)
        val progressBar = findViewById<ProgressBar>(R.id.progress_bar)

        // Setup Location Dropdowns
        val locations = MapDemoData.allPoints().keys.toList()
        val locationAdapter = ArrayAdapter<String>(this, android.R.layout.simple_dropdown_item_1line, locations)
        etOrigin.setAdapter(locationAdapter)
        etDest.setAdapter(locationAdapter)

        // Force dropdown on click/touch
        etOrigin.setOnClickListener { etOrigin.showDropDown() }
        etOrigin.setOnTouchListener { _, _ -> etOrigin.showDropDown(); false }
        etDest.setOnClickListener { etDest.showDropDown() }
        etDest.setOnTouchListener { _, _ -> etDest.showDropDown(); false }

        // Setup Weather Spinner
        val periodicLabels = listOf("Daily", "Weekdays", "Weekly", "Biweekly", "Monthly")
        spinnerPeriodicity.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, periodicLabels)
        switchPeriodic.setOnCheckedChangeListener { _, isChecked ->
            spinnerPeriodicity.visibility = if (isChecked) View.VISIBLE else View.GONE
        }

        // Setup Weather Spinner
        val weatherOptions = WeatherType.values().map { it.name }
        spinnerWeather.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, weatherOptions)
        spinnerWeather.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p0: AdapterView<*>?, p1: View?, pos: Int, p3: Long) {
                val selected = WeatherType.values()[pos]
                tilThreshold.visibility = if (selected == WeatherType.TOO_HOT || selected == WeatherType.TOO_COLD) View.VISIBLE else View.GONE
            }
            override fun onNothingSelected(p0: AdapterView<*>?) {}
        }

        btnPickTime.setOnClickListener {
            val now = Calendar.getInstance()
            DatePickerDialog(this, { _, year, month, day ->
                selectedCalendar.set(Calendar.YEAR, year)
                selectedCalendar.set(Calendar.MONTH, month)
                selectedCalendar.set(Calendar.DAY_OF_MONTH, day)
                
                TimePickerDialog(this, { _, hour, min ->
                    selectedCalendar.set(Calendar.HOUR_OF_DAY, hour)
                    selectedCalendar.set(Calendar.MINUTE, min)
                    tvDateTime.text = dateTimeFormat.format(selectedCalendar.time)
                }, now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE), true).show()
                
            }, now.get(Calendar.YEAR), now.get(Calendar.MONTH), now.get(Calendar.DAY_OF_MONTH)).show()
        }

        btnCreate.setOnClickListener {
            val origin = etOrigin.text.toString().trim()
            val dest = etDest.text.toString().trim()
            val seats = etSeats.text.toString().toIntOrNull() ?: 0
            val uid = userRepo.currentUid

            if (origin.isBlank() || dest.isBlank() || seats <= 0 || uid == null) {
                Toast.makeText(this, "Please fill all fields", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (origin.equals(dest, ignoreCase = true)) {
                Toast.makeText(this, "Origin and Destination cannot be the same", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val now = Calendar.getInstance()
            if (selectedCalendar.before(now)) {
                Toast.makeText(this, "Departure time cannot be in the past", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            progressBar.visibility = View.VISIBLE
            btnCreate.isEnabled = false

            lifecycleScope.launch {
                val user = userRepo.getUser(uid)
                val maxSeats = if (user?.driver == true && user.vehicleType.maxSeats > 0) user.vehicleType.maxSeats else seats
                if (seats > maxSeats) {
                    progressBar.visibility = View.GONE
                    btnCreate.isEnabled = true
                    Toast.makeText(
                        this@CreateRideActivity,
                        "Your vehicle only supports up to $maxSeats shared seats.",
                        Toast.LENGTH_LONG
                    ).show()
                    return@launch
                }
                val computedPricePerSeat = ((maxSeats * 2.0) / seats).coerceAtLeast(0.5)
                tvAutoPrice.text = "Auto price per seat: €%.2f".format(computedPricePerSeat)

                val ride = Ride(
                    driverId = uid,
                    driverName = user?.displayName ?: "Unknown",
                    driverPhotoUrl = user?.photoUrl,
                    driverRating = user?.rating ?: 5.0,
                    origin = origin,
                    destination = dest,
                    departureTime = selectedCalendar.time,
                    seatsTotal = seats,
                    seatsAvailable = seats,
                    periodic = switchPeriodic.isChecked,
                    periodicLabel = if (switchPeriodic.isChecked) periodicLabels[spinnerPeriodicity.selectedItemPosition] else "",
                    pricePerSeat = computedPricePerSeat,
                    weatherCondition = WeatherCondition(
                        type = WeatherType.values()[spinnerWeather.selectedItemPosition],
                        threshold = etThreshold.text.toString().toDoubleOrNull()
                    )
                )

                val result = rideRepo.createRide(ride)
                progressBar.visibility = View.GONE
                btnCreate.isEnabled = true

                if (result.isSuccess) {
                    Toast.makeText(this@CreateRideActivity, "Ride created!", Toast.LENGTH_SHORT).show()
                    finish()
                } else {
                    Toast.makeText(this@CreateRideActivity, "Error: ${result.exceptionOrNull()?.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
