package pt.ulisboa.tecnico.sharist.ui.home

import android.os.Bundle
import android.view.*
import android.widget.*
import androidx.core.widget.addTextChangedListener
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.*
import androidx.recyclerview.widget.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import pt.ulisboa.tecnico.sharist.R
import pt.ulisboa.tecnico.sharist.SharISTApp
import pt.ulisboa.tecnico.sharist.data.model.*

// ─── ViewModel ────────────────────────────────────────────────────────────────

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class HomeViewModel(
    private val rideRepo: pt.ulisboa.tecnico.sharist.data.repository.RideRepository
) : ViewModel() {

    // Mutable filter state exposed as StateFlow
    private val _filter = MutableStateFlow(RideFilter())
    val filter: StateFlow<RideFilter> = _filter.asStateFlow()

    // Debounced ride list: re-queries Room whenever filter changes
    val rides: StateFlow<List<Ride>> = _filter
        .debounce(300)
        .flatMapLatest { rideRepo.searchRides(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    fun setOrigin(origin: String)           { _filter.update { it.copy(origin = origin) } }
    fun setDestination(destination: String) { _filter.update { it.copy(destination = destination) } }
    fun setMinSeats(seats: Int)             { _filter.update { it.copy(minSeats = seats) } }
    fun setDate(date: java.util.Date?)      { _filter.update { it.copy(date = date) } }
}

// ─── Fragment ─────────────────────────────────────────────────────────────────

class HomeFragment : Fragment() {

    private val viewModel: HomeViewModel by viewModels {
        object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                @Suppress("UNCHECKED_CAST")
                return HomeViewModel(
                    (requireActivity().application as SharISTApp).rideRepository
                ) as T
            }
        }
    }

    private lateinit var adapter: RideAdapter
    private lateinit var etOrigin: EditText
    private lateinit var etDest: EditText
    private lateinit var recyclerView: RecyclerView
    private lateinit var tvEmpty: TextView
    private lateinit var progressBar: ProgressBar

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View =
        inflater.inflate(R.layout.fragment_home, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        etOrigin      = view.findViewById(R.id.et_origin)
        etDest        = view.findViewById(R.id.et_destination)
        recyclerView  = view.findViewById(R.id.recycler_rides)
        tvEmpty       = view.findViewById(R.id.tv_empty)
        progressBar   = view.findViewById(R.id.progress_bar)
        view.findViewById<Button>(R.id.btn_open_map).setOnClickListener {
            findNavController().navigate(R.id.action_home_to_ride_map)
        }

        setupRecycler()
        setupSearch()
        observeViewModel()
    }

    private fun setupRecycler() {
        adapter = RideAdapter(
            network   = (requireActivity().application as SharISTApp).networkMonitor,
            onRideClick = { ride ->
                val bundle = Bundle().apply {
                    putString("RIDE_ID", ride.id)
                }
                findNavController().navigate(R.id.action_home_to_ride_detail, bundle)
            }
        )
        recyclerView.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = this@HomeFragment.adapter
            // Scroll-based lazy loading: detect when near bottom → load more
            addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                    val llm = rv.layoutManager as LinearLayoutManager
                    val total = llm.itemCount
                    val lastVisible = llm.findLastVisibleItemPosition()
                    if (lastVisible >= total - 5) {
                        // TODO: trigger pagination / next Firestore page
                    }
                }
            })
        }
    }

    private fun setupSearch() {
        // Debouncing handled in ViewModel via StateFlow.debounce()
        etOrigin.addTextChangedListener { viewModel.setOrigin(it.toString()) }
        etDest.addTextChangedListener   { viewModel.setDestination(it.toString()) }
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.rides.collect { rides ->
                        adapter.submitList(rides)
                        tvEmpty.visibility = if (rides.isEmpty()) View.VISIBLE else View.GONE
                    }
                }
                launch {
                    viewModel.isLoading.collect { loading ->
                        progressBar.visibility = if (loading) View.VISIBLE else View.GONE
                    }
                }
            }
        }
    }

    private fun findNavController() =
        androidx.navigation.fragment.NavHostFragment.findNavController(this)
}
