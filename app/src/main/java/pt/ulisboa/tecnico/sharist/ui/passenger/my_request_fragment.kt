package pt.ulisboa.tecnico.sharist.ui.passenger

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import pt.ulisboa.tecnico.sharist.R
import pt.ulisboa.tecnico.sharist.data.model.BookingStatus
import pt.ulisboa.tecnico.sharist.data.model.RequestStatus
import pt.ulisboa.tecnico.sharist.data.model.RideRequest
import pt.ulisboa.tecnico.sharist.ui.demo.DemoRequestStore
import java.text.SimpleDateFormat
import java.util.Locale

import android.widget.Toast
import pt.ulisboa.tecnico.sharist.SharISTApp
import pt.ulisboa.tecnico.sharist.data.repository.RideRequestRepository

import android.util.Log
import kotlinx.coroutines.flow.catch

import kotlinx.coroutines.flow.combine

class MyRequestsFragment : Fragment() {
    private lateinit var requestRepo: RideRequestRepository
    private lateinit var rideRepo: pt.ulisboa.tecnico.sharist.data.repository.RideRepository

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View = i.inflate(R.layout.fragment_my_requests, c, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val app = requireActivity().application as SharISTApp
        requestRepo = app.requestRepository
        rideRepo = app.rideRepository
        val session = app.sessionManager
        val recycler = view.findViewById<RecyclerView>(R.id.recycler_requests)
        val tvEmpty = view.findViewById<TextView>(R.id.tv_empty)

        val adapter = MyRequestAdapter(
            onCancel = { item ->
                viewLifecycleOwner.lifecycleScope.launch {
                    if (item is RideRequest) {
                        requestRepo.cancelRequest(item.id)
                    } else if (item is pt.ulisboa.tecnico.sharist.data.model.Booking) {
                        try {
                            rideRepo.updateBookingStatus(item.id, pt.ulisboa.tecnico.sharist.data.model.BookingStatus.CANCELLED)
                            Toast.makeText(requireContext(), "Booking cancelled", Toast.LENGTH_SHORT).show()
                        } catch (e: Exception) {
                            Toast.makeText(requireContext(), "Failed to cancel: ${e.message}", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            },
            onRate = { item ->
                val (name, targetId, id) = if (item is RideRequest) {
                    Triple(item.driverName ?: "Driver", item.driverId ?: "", item.id)
                } else {
                    val b = item as pt.ulisboa.tecnico.sharist.data.model.Booking
                    Triple(b.driverName, b.driverId, b.id)
                }

                val dialog = RateDriverDialog(
                    targetName = name,
                    titleOverride = "Rate your driver $name"
                ) { stars, comment ->
                    viewLifecycleOwner.lifecycleScope.launch {
                        try {
                            val review = pt.ulisboa.tecnico.sharist.data.model.Review(
                                requestId = id,
                                driverId = targetId,
                                passengerId = session.uid ?: "",
                                rating = stars,
                                comment = comment
                            )
                            requestRepo.submitReview(review)
                            context?.let { Toast.makeText(it, "Review submitted!", Toast.LENGTH_SHORT).show() }
                        } catch (e: Exception) {
                            Log.e("MyRequests", "Error submitting review", e)
                            context?.let { Toast.makeText(it, "Failed to submit review: ${e.message}", Toast.LENGTH_LONG).show() }
                        }
                    }
                }
                dialog.show(childFragmentManager, "rate_driver")
            }
        )
        recycler.layoutManager = LinearLayoutManager(requireContext())
        recycler.adapter = adapter

        viewLifecycleOwner.lifecycleScope.launch {
            val uid = session.uid ?: ""
            combine(
                requestRepo.getPassengerRequests(uid),
                rideRepo.getPassengerBookings(uid)
            ) { requests, bookings ->
                (requests + bookings).sortedByDescending { 
                    when (it) {
                        is RideRequest -> it.createdAt?.time ?: 0L
                        is pt.ulisboa.tecnico.sharist.data.model.Booking -> it.createdAt?.time ?: 0L
                        else -> 0L
                    }
                }
            }.catch { e ->
                Log.e("MyRequests", "Error loading data", e)
                tvEmpty.visibility = View.VISIBLE
                tvEmpty.text = "Error: ${e.message}"
            }.collect {
                adapter.submitList(it)
                tvEmpty.visibility = if (it.isEmpty()) View.VISIBLE else View.GONE
                tvEmpty.text = "No rides or requests found"
            }
        }
    }
}

class MyRequestAdapter(
    private val onCancel: (Any) -> Unit = {},
    private val onRate: (Any) -> Unit = {}
) : ListAdapter<Any, MyRequestAdapter.VH>(DIFF) {
    private val dateFmt = SimpleDateFormat("dd MMM, HH:mm", Locale.getDefault())
    inner class VH(v: View) : RecyclerView.ViewHolder(v) {
        val tvRoute: TextView = v.findViewById(R.id.tv_route)
        val tvTime: TextView = v.findViewById(R.id.tv_time)
        val tvPrice: TextView = v.findViewById(R.id.tv_price)
        val tvDriver: TextView = v.findViewById(R.id.tv_driver)
        val tvStatus: TextView = v.findViewById(R.id.tv_status)
        val btnAction: Button = v.findViewById(R.id.btn_action)
        val btnCancel: Button = v.findViewById(R.id.btn_cancel)
        val btnRate: Button = v.findViewById(R.id.btn_rate)
    }
    override fun onCreateViewHolder(p: ViewGroup, v: Int) = VH(LayoutInflater.from(p.context).inflate(R.layout.item_my_request, p, false))
    override fun onBindViewHolder(h: VH, pos: Int) {
        val item = getItem(pos)
        if (item is RideRequest) {
            h.tvRoute.text = "${item.origin} → ${item.destination}"
            h.tvTime.text = item.requestedTime?.let { dateFmt.format(it) } ?: "Pending..."
            h.tvPrice.text = "€ %.2f".format(item.estimatedPrice)
            h.tvDriver.text = item.driverName?.let { "Driver: $it" } ?: "Searching for driver..."
            h.tvStatus.text = "Request: ${item.status}"
            
            h.btnAction.visibility = View.GONE
            h.btnCancel.visibility = if (item.status == RequestStatus.OPEN || item.status == RequestStatus.ACCEPTED) View.VISIBLE else View.GONE
            h.btnCancel.setOnClickListener { onCancel(item) }
            
            h.btnRate.visibility = if (item.status == RequestStatus.COMPLETED && !item.driverReviewed) View.VISIBLE else View.GONE
            h.btnRate.setOnClickListener { onRate(item) }
        } else if (item is pt.ulisboa.tecnico.sharist.data.model.Booking) {
            h.tvRoute.text = "${item.origin} → ${item.destination}"
            h.tvTime.text = item.departureTime?.let { dateFmt.format(it) } ?: "Pending..."
            h.tvPrice.text = "€ %.2f".format(item.totalPrice)
            h.tvDriver.text = "Driver: ${item.driverName}"
            h.tvStatus.text = "Booking: ${item.status}"
            
            h.btnAction.visibility = View.GONE
            h.btnCancel.visibility = if (item.status == BookingStatus.PENDING || item.status == BookingStatus.ACCEPTED) View.VISIBLE else View.GONE
            h.btnCancel.setOnClickListener { onCancel(item) }

            h.btnRate.visibility = if (item.status == BookingStatus.COMPLETED && !item.driverReviewed) View.VISIBLE else View.GONE
            h.btnRate.setOnClickListener { onRate(item) }
        }
    }
    companion object { 
        private val DIFF = object : DiffUtil.ItemCallback<Any>() { 
            override fun areItemsTheSame(a: Any, b: Any): Boolean {
                return if (a is RideRequest && b is RideRequest) a.id == b.id
                else if (a is pt.ulisboa.tecnico.sharist.data.model.Booking && b is pt.ulisboa.tecnico.sharist.data.model.Booking) a.id == b.id
                else false
            }
            override fun areContentsTheSame(a: Any, b: Any) = a == b 
        } 
    }
}
