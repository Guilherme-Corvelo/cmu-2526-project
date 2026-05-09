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

        val etOrigin = findViewById<EditText>(R.id.et_origin)
        val etDest = findViewById<EditText>(R.id.et_destination)
        val btnPickTime = findViewById<Button>(R.id.btn_pick_time)
        val tvDateTime = findViewById<TextView>(R.id.tv_date_time)
        val etSeats = findViewById<EditText>(R.id.et_seats)
        val etPrice = findViewById<EditText>(R.id.et_price)
        val spinnerWeather = findViewById<Spinner>(R.id.spinner_weather)
        val etThreshold = findViewById<EditText>(R.id.et_threshold)
        val btnCreate = findViewById<Button>(R.id.btn_create)
        val progressBar = findViewById<ProgressBar>(R.id.progress_bar)

        // Setup Weather Spinner
        val weatherOptions = WeatherType.values().map { it.name }
        spinnerWeather.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, weatherOptions)
        spinnerWeather.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p0: AdapterView<*>?, p1: View?, pos: Int, p3: Long) {
                val selected = WeatherType.values()[pos]
                etThreshold.visibility = if (selected == WeatherType.TOO_HOT || selected == WeatherType.TOO_COLD) View.VISIBLE else View.GONE
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
            val origin = etOrigin.text.toString()
            val dest = etDest.text.toString()
            val seats = etSeats.text.toString().toIntOrNull() ?: 0
            val price = etPrice.text.toString().toDoubleOrNull() ?: 0.0
            val uid = userRepo.currentUid

            if (origin.isBlank() || dest.isBlank() || seats <= 0 || uid == null) {
                Toast.makeText(this, "Please fill all fields", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            progressBar.visibility = View.VISIBLE
            btnCreate.isEnabled = false

            lifecycleScope.launch {
                val user = userRepo.getUser(uid)
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
                    pricePerSeat = price,
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
