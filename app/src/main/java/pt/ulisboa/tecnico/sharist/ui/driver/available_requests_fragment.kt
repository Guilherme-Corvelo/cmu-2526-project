package pt.ulisboa.tecnico.sharist.ui.driver

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
import pt.ulisboa.tecnico.sharist.data.model.RideRequest
import pt.ulisboa.tecnico.sharist.ui.demo.DemoRequestStore

class AvailableRequestsFragment : Fragment() {
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_available_requests, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val recycler = view.findViewById<RecyclerView>(R.id.recycler_requests)
        val tvEmpty = view.findViewById<TextView>(R.id.tv_empty)
        val adapter = DriverRequestAdapter(
            onAccept = { DemoRequestStore.acceptRequest(it.id) },
            onDeny = { DemoRequestStore.denyRequest(it.id) }
        )
        recycler.layoutManager = LinearLayoutManager(requireContext())
        recycler.adapter = adapter

        viewLifecycleOwner.lifecycleScope.launch {
            DemoRequestStore.requests.map { list -> list.filter { it.status.name == "OPEN" } }.collect {
                adapter.submitList(it)
                tvEmpty.text = "No open demo requests"
                tvEmpty.visibility = if (it.isEmpty()) View.VISIBLE else View.GONE
            }
        }
    }
}

class DriverRequestAdapter(
    private val onAccept: (RideRequest) -> Unit,
    private val onDeny: (RideRequest) -> Unit = {}
) : ListAdapter<RideRequest, DriverRequestAdapter.VH>(DIFF) {
    inner class VH(v: View) : RecyclerView.ViewHolder(v) {
        val tvRoute: TextView = v.findViewById(R.id.tv_route)
        val tvDriver: TextView = v.findViewById(R.id.tv_driver)
        val btnCancel: Button = v.findViewById(R.id.btn_cancel)
        val btnRate: Button = v.findViewById(R.id.btn_rate)
    }
    override fun onCreateViewHolder(p: ViewGroup, v: Int) = VH(LayoutInflater.from(p.context).inflate(R.layout.item_my_request, p, false))
    override fun onBindViewHolder(h: VH, pos: Int) {
        val r = getItem(pos)
        h.tvRoute.text = "${r.passengerName}: ${r.origin} → ${r.destination}"
        h.tvDriver.text = "Tap accept to move this ride to My Activities"
        h.btnCancel.visibility = View.VISIBLE
        h.btnCancel.text = "Accept"
        h.btnCancel.setOnClickListener { onAccept(r) }
        h.btnRate.visibility = View.VISIBLE
        h.btnRate.text = "Deny"
        h.btnRate.setOnClickListener { onDeny(r) }
    }
    companion object { private val DIFF = object : DiffUtil.ItemCallback<RideRequest>() { override fun areItemsTheSame(a: RideRequest, b: RideRequest)=a.id==b.id; override fun areContentsTheSame(a: RideRequest, b: RideRequest)=a==b } }
}
