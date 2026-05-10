package pt.ulisboa.tecnico.sharist.ui.passenger

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.*
import androidx.recyclerview.widget.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import pt.ulisboa.tecnico.sharist.R
import pt.ulisboa.tecnico.sharist.SharISTApp
import pt.ulisboa.tecnico.sharist.data.model.*
import pt.ulisboa.tecnico.sharist.data.repository.RideRequestRepository
import pt.ulisboa.tecnico.sharist.data.repository.UserRepository
import pt.ulisboa.tecnico.sharist.utils.PriceCalculator
import java.text.SimpleDateFormat
import java.util.*

class MyRequestsViewModel(private val repo: RideRequestRepository, private val userRepo: UserRepository) : ViewModel() {
    val requests: StateFlow<List<RideRequest>> = flow { val uid = userRepo.currentUid ?: return@flow; emitAll(repo.getPassengerRequests(uid)) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    fun cancelRequest(id: String) { viewModelScope.launch { repo.cancelRequest(id) } }
    fun submitReview(requestId: String, driverId: String, passengerId: String, stars: Int, comment: String) {
        viewModelScope.launch { repo.submitReview(Review(requestId = requestId, driverId = driverId, passengerId = passengerId, rating = stars, comment = comment)) }
    }
}

class MyRequestsFragment : Fragment() {
    private val vm: MyRequestsViewModel by viewModels { object : ViewModelProvider.Factory { override fun <T : ViewModel> create(c: Class<T>): T { val app=requireActivity().application as SharISTApp; @Suppress("UNCHECKED_CAST") return MyRequestsViewModel(app.requestRepository, app.userRepository) as T } } }
    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View = i.inflate(R.layout.fragment_my_requests, c, false)
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val recycler = view.findViewById<RecyclerView>(R.id.recycler_requests); val tvEmpty = view.findViewById<TextView>(R.id.tv_empty)
        val uid = (requireActivity().application as SharISTApp).sessionManager.uid ?: ""
        val adapter = MyRequestAdapter(onCancel = { vm.cancelRequest(it.id) }, onRate = { showRateDialog(it, uid) })
        recycler.layoutManager = LinearLayoutManager(requireContext()); recycler.adapter = adapter
        viewLifecycleOwner.lifecycleScope.launch { repeatOnLifecycle(Lifecycle.State.STARTED) { vm.requests.collect { adapter.submitList(it); tvEmpty.visibility = if (it.isEmpty()) View.VISIBLE else View.GONE } } }
    }
    private fun showRateDialog(request: RideRequest, passengerId: String) {
        val driverId = request.driverId ?: return
        RateDriverDialog(request.driverName ?: "Driver") { stars, comment -> vm.submitReview(request.id, driverId, passengerId, stars, comment) }
            .show(childFragmentManager, "rate")
    }
}

class MyRequestAdapter(private val onCancel: (RideRequest) -> Unit, private val onRate: (RideRequest) -> Unit) : ListAdapter<RideRequest, MyRequestAdapter.VH>(DIFF) {
    private val dateFmt = SimpleDateFormat("dd MMM, HH:mm", Locale.getDefault())
    inner class VH(v: View) : RecyclerView.ViewHolder(v) { val tvRoute: TextView=v.findViewById(R.id.tv_route); val tvTime: TextView=v.findViewById(R.id.tv_time); val tvPrice: TextView=v.findViewById(R.id.tv_price); val tvDriver: TextView=v.findViewById(R.id.tv_driver); val tvStatus: TextView=v.findViewById(R.id.tv_status); val btnCancel: Button=v.findViewById(R.id.btn_cancel); val btnRate: Button=v.findViewById(R.id.btn_rate) }
    override fun onCreateViewHolder(p: ViewGroup, v: Int) = VH(LayoutInflater.from(p.context).inflate(R.layout.item_my_request, p, false))
    override fun onBindViewHolder(h: VH, pos: Int) { val r=getItem(pos); h.tvRoute.text="${r.origin}  →  ${r.destination}"; h.tvTime.text=r.requestedTime?.let{dateFmt.format(it)}?:"—"; h.tvPrice.text=PriceCalculator.format(r.estimatedPrice); h.tvDriver.text=if(r.driverName!=null)"Driver: ${r.driverName}  ★ %.1f".format(r.driverRating) else "Waiting for a driver…"; h.tvStatus.text=r.status.name; h.btnCancel.visibility=if(r.status==RequestStatus.OPEN)View.VISIBLE else View.GONE; h.btnCancel.setOnClickListener{onCancel(r)}; h.btnRate.visibility=if(r.status==RequestStatus.COMPLETED && !r.reviewed)View.VISIBLE else View.GONE; h.btnRate.setOnClickListener{onRate(r)} }
    companion object { private val DIFF = object : DiffUtil.ItemCallback<RideRequest>() { override fun areItemsTheSame(a: RideRequest,b: RideRequest)=a.id==b.id; override fun areContentsTheSame(a: RideRequest,b: RideRequest)=a==b } }
}
