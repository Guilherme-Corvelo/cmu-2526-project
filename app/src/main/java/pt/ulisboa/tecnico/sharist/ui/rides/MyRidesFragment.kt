package pt.ulisboa.tecnico.sharist.ui.rides

import android.content.Intent
import android.os.Bundle
import android.view.*
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import pt.ulisboa.tecnico.sharist.R
import pt.ulisboa.tecnico.sharist.SharISTApp
import pt.ulisboa.tecnico.sharist.ui.home.RideAdapter
import pt.ulisboa.tecnico.sharist.ui.ride.RideDetailActivity

class MyRidesFragment : Fragment() {

    private val rideRepo by lazy { (requireActivity().application as SharISTApp).rideRepository }
    private val userRepo by lazy { (requireActivity().application as SharISTApp).userRepository }
    private val networkMonitor by lazy { (requireActivity().application as SharISTApp).networkMonitor }

    private lateinit var adapter: RideAdapter

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_my_rides, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val rv = view.findViewById<RecyclerView>(R.id.recycler_my_rides)
        val tvEmpty = view.findViewById<TextView>(R.id.tv_empty)
        val fab = view.findViewById<FloatingActionButton>(R.id.fab_add_ride)

        adapter = RideAdapter(networkMonitor) { ride ->
            val intent = Intent(requireContext(), RideDetailActivity::class.java).apply {
                putExtra("RIDE_ID", ride.id)
            }
            startActivity(intent)
        }

        rv.layoutManager = LinearLayoutManager(requireContext())
        rv.adapter = adapter

        val uid = userRepo.currentUid
        if (uid != null) {
            viewLifecycleOwner.lifecycleScope.launch {
                rideRepo.getDriverRides(uid).collectLatest { rides ->
                    adapter.submitList(rides)
                    tvEmpty.visibility = if (rides.isEmpty()) View.VISIBLE else View.GONE
                }
            }
        }

        fab.setOnClickListener {
            startActivity(Intent(requireContext(), CreateRideActivity::class.java))
        }
    }
}
