package pt.ulisboa.tecnico.sharist.ui.driver

import android.os.Bundle
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
import pt.ulisboa.tecnico.sharist.R
import pt.ulisboa.tecnico.sharist.data.model.RequestStatus
import pt.ulisboa.tecnico.sharist.data.model.RideRequest
import pt.ulisboa.tecnico.sharist.ui.demo.DemoRequestStore

class MyActiveRidesFragment : Fragment() {
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_my_active_rides, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val recycler = view.findViewById<RecyclerView>(R.id.recycler_rides)
        val tvEmpty = view.findViewById<TextView>(R.id.tv_empty)
        val adapter = ActiveRideAdapter(
            onFinish = {
                val completed = DemoRequestStore.completeRequest(it.id)
                if (!completed) {
                    Toast.makeText(requireContext(), "Unable to finish ride", Toast.LENGTH_SHORT).show()
                }
            }
        )
        recycler.layoutManager = LinearLayoutManager(requireContext())
        recycler.adapter = adapter

        viewLifecycleOwner.lifecycleScope.launch {
            DemoRequestStore.requests
                .map { list -> list.filter { it.driverId == DemoRequestStore.DEMO_DRIVER_ID && it.status == RequestStatus.ACCEPTED } }
                .collect {
                adapter.submitList(it)
                tvEmpty.text = "No active ride. You can accept one from Available Requests."
                tvEmpty.visibility = if (it.isEmpty()) View.VISIBLE else View.GONE
            }
        }
    }
}

class ActiveRideAdapter(
    private val onFinish: (RideRequest) -> Unit
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
        holder.tvDriver.text = "Active ride in progress"
        holder.btnCancel.visibility = View.VISIBLE
        holder.btnCancel.text = "Finish Ride"
        holder.btnCancel.setOnClickListener { onFinish(ride) }
        holder.btnRate.visibility = View.GONE
    }

    companion object {
        private val DIFF = object : androidx.recyclerview.widget.DiffUtil.ItemCallback<RideRequest>() {
            override fun areItemsTheSame(oldItem: RideRequest, newItem: RideRequest) = oldItem.id == newItem.id
            override fun areContentsTheSame(oldItem: RideRequest, newItem: RideRequest) = oldItem == newItem
        }
    }
}
