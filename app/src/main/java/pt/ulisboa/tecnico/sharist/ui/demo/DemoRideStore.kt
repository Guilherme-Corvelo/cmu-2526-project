package pt.ulisboa.tecnico.sharist.ui.demo

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import pt.ulisboa.tecnico.sharist.data.model.*
import java.util.Date
import java.util.UUID

object DemoRideStore {
    private val DEMO_CLIENT = User(
        uid = DemoRequestStore.DEMO_CLIENT_ID,
        displayName = DemoRequestStore.DEMO_CLIENT_NAME,
        email = "client@demo.app",
        driver = false,
        balance = 1000.0
    )

    private val DEMO_DRIVER = User(
        uid = DemoRequestStore.DEMO_DRIVER_ID,
        displayName = DemoRequestStore.DEMO_DRIVER_NAME,
        email = "driver@demo.app",
        driver = true,
        rating = 4.8,
        ratingCount = 36
    )

    private val users = linkedMapOf(
        DEMO_CLIENT.uid to DEMO_CLIENT,
        DEMO_DRIVER.uid to DEMO_DRIVER
    )

    private val ridesFlow = MutableStateFlow(
        mutableListOf(
            Ride(
                id = "ride_demo_1",
                driverId = DEMO_DRIVER.uid,
                driverName = DEMO_DRIVER.displayName,
                origin = "IST Alameda",
                destination = "Saldanha",
                departureTime = Date(System.currentTimeMillis() + 45 * 60 * 1000),
                seatsTotal = 3,
                seatsAvailable = 2,
                pricePerSeat = 2.5,
                status = RideStatus.OPEN
            ),
            Ride(
                id = "ride_demo_2",
                driverId = DEMO_DRIVER.uid,
                driverName = DEMO_DRIVER.displayName,
                origin = "Campo Grande",
                destination = "Oriente",
                departureTime = Date(System.currentTimeMillis() + 2 * 60 * 60 * 1000),
                seatsTotal = 4,
                seatsAvailable = 4,
                pricePerSeat = 3.0,
                status = RideStatus.OPEN
            )
        )
    )

    private val bookingsFlow = MutableStateFlow(mutableListOf<Booking>())
    private val reviewsFlow = MutableStateFlow(mutableListOf<Review>())

    fun resetDemoData() {
        bookingsFlow.value = mutableListOf()
        reviewsFlow.value = mutableListOf()
    }

    fun createOrUpdateUser(user: User) {
        users[user.uid] = user
    }

    fun getUser(uid: String): User? = users[uid]

    fun findUserByEmail(email: String): User? =
        users.values.firstOrNull { it.email.equals(email, ignoreCase = true) }

    fun updateBalance(uid: String, delta: Double) {
        val current = users[uid] ?: return
        users[uid] = current.copy(balance = current.balance + delta)
    }

    fun addReview(review: Review) {
        val id = review.id.ifBlank { "mock_review_${UUID.randomUUID()}" }
        val finalReview = review.copy(id = id, createdAt = Date())
        reviewsFlow.value = (reviewsFlow.value + finalReview).toMutableList()
        
        // Update user rating
        users[review.driverId]?.let { user ->
            val newCount = user.ratingCount + 1
            val newRating = ((user.rating * user.ratingCount) + review.rating) / newCount
            users[review.driverId] = user.copy(rating = newRating, ratingCount = newCount)
        }

        // Update the reviewed status on the request or booking
        DemoRequestStore.requests.value = DemoRequestStore.requests.value.map {
            if (it.id == review.requestId) {
                if (it.driverId == review.passengerId) it.copy(driverReviewed = true)
                else it.copy(passengerReviewed = true)
            } else it
        }

        bookingsFlow.value = bookingsFlow.value.map {
            if (it.id == review.requestId) {
                if (it.driverId == review.passengerId) it.copy(driverReviewed = true)
                else it.copy(passengerReviewed = true)
            } else it
        }.toMutableList()
    }

    fun observeReviewsForUser(userId: String): Flow<List<Review>> =
        reviewsFlow.map { reviews -> reviews.filter { it.driverId == userId }.sortedByDescending { it.createdAt?.time ?: 0L } }

    fun observeRides(filter: RideFilter): Flow<List<Ride>> {
        return ridesFlow.map { rides ->
            rides.filter { ride ->
                matchesText(ride.origin, filter.origin) &&
                    matchesText(ride.destination, filter.destination) &&
                    ride.seatsAvailable >= filter.minSeats &&
                    (filter.maxPrice == null || ride.pricePerSeat <= filter.maxPrice)
            }
        }
    }

    fun getRide(rideId: String): Ride? = ridesFlow.value.firstOrNull { it.id == rideId }

    fun observeDriverRides(driverId: String): Flow<List<Ride>> =
        ridesFlow.map { rides -> rides.filter { it.driverId == driverId } }

    fun createRide(ride: Ride): String {
        val id = ride.id.ifBlank { "mock_ride_${UUID.randomUUID()}" }
        ridesFlow.value = (ridesFlow.value + ride.copy(id = id)).toMutableList()
        return id
    }

    fun decrementSeat(rideId: String) {
        ridesFlow.value = ridesFlow.value.map { ride ->
            if (ride.id == rideId && ride.seatsAvailable > 0) {
                val newSeats = ride.seatsAvailable - 1
                ride.copy(
                    seatsAvailable = newSeats,
                    status = if (newSeats == 0) RideStatus.FULL else RideStatus.OPEN
                )
            } else {
                ride
            }
        }.toMutableList()
    }

    fun cancelRide(rideId: String) {
        ridesFlow.value = ridesFlow.value.map { ride ->
            if (ride.id == rideId) ride.copy(status = RideStatus.CANCELLED) else ride
        }.toMutableList()
    }

    fun completeRide(rideId: String) {
        val now = Date()
        val currentBookings = bookingsFlow.value.filter { it.rideId == rideId }
        
        ridesFlow.value = ridesFlow.value.map { ride ->
            if (ride.id == rideId) {
                val updatedRide = ride.copy(status = RideStatus.COMPLETED)
                
                if (ride.periodic) {
                    val nextDate = calculateNextOccurrence(ride.departureTime, ride.periodicLabel)
                    val nextRideId = "mock_ride_${UUID.randomUUID()}"
                    
                    // Create next ride
                    val nextRide = ride.copy(
                        id = nextRideId,
                        status = RideStatus.OPEN,
                        departureTime = nextDate,
                        seatsAvailable = ride.seatsTotal,
                        createdAt = now
                    )
                    // We'll add this to the list after the map
                    
                    // Handle bookings
                    bookingsFlow.value = bookingsFlow.value.map { booking ->
                        if (booking.rideId == rideId) {
                            val isActive = booking.status == BookingStatus.ACCEPTED || 
                                           booking.status == BookingStatus.PICKED_UP || 
                                           booking.status == BookingStatus.EN_ROUTE
                            
                            val updated = if (isActive) {
                                // Driver gets paid upon completion in demo
                                if (!booking.driverPaid) {
                                    updateBalance(booking.driverId, booking.totalPrice)
                                    booking.copy(status = BookingStatus.COMPLETED, driverPaid = true)
                                } else {
                                    booking.copy(status = BookingStatus.COMPLETED)
                                }
                            } else if (booking.status == BookingStatus.PENDING) {
                                booking.copy(status = BookingStatus.REJECTED)
                            } else {
                                booking
                            }

                            // Carry over recurring
                            if (booking.recurring && (isActive || booking.status == BookingStatus.COMPLETED)) {
                                val nextBooking = booking.copy(
                                    id = "mock_booking_${UUID.randomUUID()}",
                                    rideId = nextRideId,
                                    status = BookingStatus.PENDING,
                                    departureTime = nextDate,
                                    createdAt = now,
                                    passengerReviewed = false,
                                    driverReviewed = false,
                                    passengerPaid = false,
                                    driverPaid = false
                                )
                                // Add to list (we'll do this outside)
                                demoNextBookings.add(nextBooking)
                            }
                            updated
                        } else booking
                    }.toMutableList()
                    
                    demoNextRides.add(nextRide)
                } else {
                    // Non-periodic: just complete bookings
                    bookingsFlow.value = bookingsFlow.value.map { booking ->
                        if (booking.rideId == rideId) {
                            val isActive = booking.status == BookingStatus.ACCEPTED || 
                                           booking.status == BookingStatus.PICKED_UP || 
                                           booking.status == BookingStatus.EN_ROUTE
                            
                            if (isActive) {
                                if (!booking.driverPaid) {
                                    updateBalance(booking.driverId, booking.totalPrice)
                                    booking.copy(status = BookingStatus.COMPLETED, driverPaid = true)
                                } else {
                                    booking.copy(status = BookingStatus.COMPLETED)
                                }
                            } else if (booking.status == BookingStatus.PENDING) {
                                booking.copy(status = BookingStatus.REJECTED)
                            } else booking
                        } else booking
                    }.toMutableList()
                }
                updatedRide
            } else ride
        }.toMutableList()

        if (demoNextRides.isNotEmpty()) {
            ridesFlow.value = (ridesFlow.value + demoNextRides).toMutableList()
            demoNextRides.clear()
        }
        if (demoNextBookings.isNotEmpty()) {
            bookingsFlow.value = (bookingsFlow.value + demoNextBookings).toMutableList()
            demoNextBookings.clear()
        }
    }

    private val demoNextRides = mutableListOf<Ride>()
    private val demoNextBookings = mutableListOf<Booking>()

    private fun calculateNextOccurrence(currentDate: Date?, label: String): Date {
        val cal = java.util.Calendar.getInstance()
        if (currentDate != null) cal.time = currentDate
        
        when (label.lowercase()) {
            "daily" -> cal.add(java.util.Calendar.DAY_OF_YEAR, 1)
            "weekdays" -> {
                do {
                    cal.add(java.util.Calendar.DAY_OF_YEAR, 1)
                } while (cal.get(java.util.Calendar.DAY_OF_WEEK) == java.util.Calendar.SATURDAY || 
                         cal.get(java.util.Calendar.DAY_OF_WEEK) == java.util.Calendar.SUNDAY)
            }
            "weekly" -> cal.add(java.util.Calendar.WEEK_OF_YEAR, 1)
            "biweekly" -> cal.add(java.util.Calendar.WEEK_OF_YEAR, 2)
            "monthly" -> cal.add(java.util.Calendar.MONTH, 1)
            else -> cal.add(java.util.Calendar.DAY_OF_YEAR, 1)
        }
        return cal.time
    }

    fun startRide(rideId: String) {
        ridesFlow.value = ridesFlow.value.map { ride ->
            if (ride.id == rideId) ride.copy(status = RideStatus.EN_ROUTE) else ride
        }.toMutableList()
    }

    fun createBooking(booking: Booking): String {
        val id = booking.id.ifBlank { "mock_booking_${UUID.randomUUID()}" }
        
        // Upfront payment
        updateBalance(booking.passengerId, -booking.totalPrice)
        
        val finalBooking = booking.copy(
            id = id,
            passengerPaid = true,
            passengerRefunded = false
        )
        bookingsFlow.value = (bookingsFlow.value + finalBooking).toMutableList()
        return id
    }

    fun updateBookingStatus(bookingId: String, status: BookingStatus, currentUid: String? = null) {
        bookingsFlow.value = bookingsFlow.value.map { booking ->
            if (booking.id == bookingId) {
                // State machine validation
                val current = booking.status
                val valid = when (status) {
                    BookingStatus.ACCEPTED -> current == BookingStatus.PENDING
                    BookingStatus.REJECTED -> current == BookingStatus.PENDING || current == BookingStatus.REJECTED
                    BookingStatus.EN_ROUTE -> current == BookingStatus.ACCEPTED
                    BookingStatus.PICKED_UP -> current == BookingStatus.EN_ROUTE
                    BookingStatus.COMPLETED -> current == BookingStatus.PICKED_UP || current == BookingStatus.ACCEPTED || current == BookingStatus.EN_ROUTE || current == BookingStatus.COMPLETED
                    BookingStatus.CANCELLED -> (current != BookingStatus.COMPLETED && current != BookingStatus.REJECTED) || current == BookingStatus.CANCELLED
                    else -> false
                }
                if (!valid) return@map booking

                var updated = booking.copy(status = status)
                
                if (status == BookingStatus.ACCEPTED) {
                    val ride = getRide(booking.rideId)
                    if (ride != null) {
                        if (ride.seatsAvailable < booking.seatsRequested) {
                            return@map booking.copy(status = BookingStatus.REJECTED)
                        }
                        decrementSeats(booking.rideId, booking.seatsRequested)
                    }
                }

                if (status == BookingStatus.COMPLETED) {
                    val amount = booking.totalPrice
                    val uid = currentUid
                    
                    if (uid == booking.driverId && !updated.driverPaid) {
                        updateBalance(booking.driverId, amount)
                        updated = updated.copy(driverPaid = true)
                    }
                    // Passenger already paid upfront
                } else if ((status == BookingStatus.CANCELLED || status == BookingStatus.REJECTED) && 
                    booking.passengerPaid && !booking.passengerRefunded) {
                    
                    val uid = currentUid
                    // Only the passenger can trigger their own refund
                    if (uid == booking.passengerId) {
                        updateBalance(booking.passengerId, booking.totalPrice)
                        updated = updated.copy(passengerRefunded = true)
                    }
                } else if (status == BookingStatus.CANCELLED && (current == BookingStatus.ACCEPTED || current == BookingStatus.EN_ROUTE || current == BookingStatus.PICKED_UP)) {
                    // Return seats
                    incrementSeats(booking.rideId, booking.seatsRequested)
                }
                
                updated
            } else booking
        }.toMutableList()
    }

    private fun decrementSeats(rideId: String, count: Int) {
        ridesFlow.value = ridesFlow.value.map { ride ->
            if (ride.id == rideId) {
                val newSeats = (ride.seatsAvailable - count).coerceAtLeast(0)
                ride.copy(
                    seatsAvailable = newSeats,
                    status = if (newSeats == 0) RideStatus.FULL else RideStatus.OPEN
                )
            } else ride
        }.toMutableList()
    }

    private fun incrementSeats(rideId: String, count: Int) {
        ridesFlow.value = ridesFlow.value.map { ride ->
            if (ride.id == rideId) {
                val newSeats = (ride.seatsAvailable + count).coerceAtMost(ride.seatsTotal)
                ride.copy(
                    seatsAvailable = newSeats,
                    status = RideStatus.OPEN
                )
            } else ride
        }.toMutableList()
    }

    fun observePassengerBookings(passengerId: String): Flow<List<Booking>> =
        bookingsFlow.map { bookings -> bookings.filter { it.passengerId == passengerId } }

    fun observeRideBookings(rideId: String): Flow<List<Booking>> =
        bookingsFlow.map { bookings -> bookings.filter { it.rideId == rideId } }

    fun observeDriverBookings(driverId: String): Flow<List<Booking>> =
        bookingsFlow.map { bookings -> bookings.filter { it.driverId == driverId } }

    private fun matchesText(field: String, query: String): Boolean {
        if (query.isBlank()) return true
        return field.contains(query.trim(), ignoreCase = true)
    }
}
