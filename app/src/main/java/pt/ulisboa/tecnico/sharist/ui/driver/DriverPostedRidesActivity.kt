package pt.ulisboa.tecnico.sharist.ui.driver

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import pt.ulisboa.tecnico.sharist.R
import pt.ulisboa.tecnico.sharist.SharISTApp
import pt.ulisboa.tecnico.sharist.ui.home.RideAdapter
import pt.ulisboa.tecnico.sharist.ui.ride.RideDetailActivity

class DriverPostedRidesActivity : AppCompatActivity() {
    private val app by lazy { application as SharISTApp }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_driver_posted_rides)

        val toolbar = findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        val recycler = findViewById<RecyclerView>(R.id.recycler_posted_rides)
        val empty = findViewById<TextView>(R.id.tv_empty)
        val uid = app.userRepository.currentUid

        val adapter = RideAdapter(
            network = app.networkMonitor,
            currentUid = uid,
            onCancelRide = { ride ->
                androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle("Cancel Ride")
                    .setMessage("Are you sure you want to cancel this ride?")
                    .setPositiveButton("Yes") { _, _ ->
                        lifecycleScope.launch {
                            app.rideRepository.cancelRide(ride.id)
                                .onSuccess { android.widget.Toast.makeText(this@DriverPostedRidesActivity, "Ride cancelled", android.widget.Toast.LENGTH_SHORT).show() }
                                .onFailure { e -> android.widget.Toast.makeText(this@DriverPostedRidesActivity, "Error: ${e.message}", android.widget.Toast.LENGTH_LONG).show() }
                        }
                    }
                    .setNegativeButton("No", null)
                    .show()
            }
        ) { ride ->
            startActivity(Intent(this, RideDetailActivity::class.java).apply { putExtra("RIDE_ID", ride.id) })
        }

        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = adapter

        if (uid != null) {
            lifecycleScope.launch {
                app.rideRepository.getDriverRides(uid).collectLatest { rides ->
                    adapter.submitList(rides)
                    empty.visibility = if (rides.isEmpty()) View.VISIBLE else View.GONE
                }
            }
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
