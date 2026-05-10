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
import pt.ulisboa.tecnico.sharist.data.model.RequestStatus
import pt.ulisboa.tecnico.sharist.data.model.RideRequest
import pt.ulisboa.tecnico.sharist.ui.demo.DemoRequestStore
import java.text.SimpleDateFormat
import java.util.Locale

class MyRequestsFragment : Fragment() {
    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View = i.inflate(R.layout.fragment_my_requests, c, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val recycler = view.findViewById<RecyclerView>(R.id.recycler_requests)
        val tvEmpty = view.findViewById<TextView>(R.id.tv_empty)
        val adapter = MyRequestAdapter()
        recycler.layoutManager = LinearLayoutManager(requireContext())
        recycler.adapter = adapter

        viewLifecycleOwner.lifecycleScope.launch {
            DemoRequestStore.requests
                .map { list -> list.filter { it.passengerId == DemoRequestStore.DEMO_CLIENT_ID } }
                .collect {
                    adapter.submitList(it)
                    tvEmpty.visibility = if (it.isEmpty()) View.VISIBLE else View.GONE
                    tvEmpty.text = "No demo requests yet"
                }
        }
    }
}

class MyRequestAdapter : ListAdapter<RideRequest, MyRequestAdapter.VH>(DIFF) {
    private val dateFmt = SimpleDateFormat("dd MMM, HH:mm", Locale.getDefault())
    inner class VH(v: View) : RecyclerView.ViewHolder(v) {
        val tvRoute: TextView = v.findViewById(R.id.tv_route)
        val tvTime: TextView = v.findViewById(R.id.tv_time)
        val tvPrice: TextView = v.findViewById(R.id.tv_price)
        val tvDriver: TextView = v.findViewById(R.id.tv_driver)
        val tvStatus: TextView = v.findViewById(R.id.tv_status)
        val btnCancel: Button = v.findViewById(R.id.btn_cancel)
        val btnRate: Button = v.findViewById(R.id.btn_rate)
    }
    override fun onCreateViewHolder(p: ViewGroup, v: Int) = VH(LayoutInflater.from(p.context).inflate(R.layout.item_my_request, p, false))
    override fun onBindViewHolder(h: VH, pos: Int) {
        val r = getItem(pos)
        h.tvRoute.text = "${r.origin} → ${r.destination}"
        h.tvTime.text = r.requestedTime?.let { dateFmt.format(it) } ?: "—"
        h.tvPrice.text = "€ %.2f".format(r.estimatedPrice)
        h.tvDriver.text = r.driverName?.let { "Driver: $it" } ?: "Waiting for driver"
        h.tvStatus.text = r.status.name
        h.btnCancel.visibility = View.GONE
        h.btnRate.visibility = if (r.status == RequestStatus.COMPLETED) View.VISIBLE else View.GONE
    }
    companion object { private val DIFF = object : DiffUtil.ItemCallback<RideRequest>() { override fun areItemsTheSame(a: RideRequest, b: RideRequest)=a.id==b.id; override fun areContentsTheSame(a: RideRequest, b: RideRequest)=a==b } }
}
