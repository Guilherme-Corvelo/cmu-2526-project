package pt.ulisboa.tecnico.sharist.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import pt.ulisboa.tecnico.sharist.data.model.FavoriteLocation
import pt.ulisboa.tecnico.sharist.data.model.Ride
import pt.ulisboa.tecnico.sharist.data.model.RideFilter
import pt.ulisboa.tecnico.sharist.data.model.RideRequest
import pt.ulisboa.tecnico.sharist.data.repository.FavoriteLocationRepository
import pt.ulisboa.tecnico.sharist.data.repository.RideRepository
import pt.ulisboa.tecnico.sharist.data.repository.RideRequestRepository

class RideMapViewModel(
    private val rideRepo: RideRepository,
    private val requestRepo: RideRequestRepository,
    private val favoriteRepo: FavoriteLocationRepository,
    private val userId: String?
) : ViewModel() {

    val rides: StateFlow<List<Ride>> = rideRepo.getRides(RideFilter())
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val requests: StateFlow<List<RideRequest>> = requestRepo.getOpenRequests()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val favorites: StateFlow<List<FavoriteLocation>> = if (userId != null) {
        favoriteRepo.getFavorites(userId)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    } else {
        MutableStateFlow(emptyList())
    }

    fun addFavorite(name: String, address: String, lat: Double, lng: Double) {
        val uid = userId ?: return
        viewModelScope.launch {
            val fav = FavoriteLocation(
                id = java.util.UUID.randomUUID().toString(),
                userId = uid,
                name = name,
                address = address,
                latitude = lat,
                longitude = lng
            )
            favoriteRepo.addFavorite(fav)
        }
    }
}
