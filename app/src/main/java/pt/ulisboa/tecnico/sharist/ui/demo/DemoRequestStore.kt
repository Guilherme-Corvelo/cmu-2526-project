package pt.ulisboa.tecnico.sharist.ui.demo

import kotlinx.coroutines.flow.MutableStateFlow
import pt.ulisboa.tecnico.sharist.data.model.RequestStatus
import pt.ulisboa.tecnico.sharist.data.model.RideRequest
import java.util.Date
import java.util.UUID

object DemoRequestStore {
    const val DEMO_CLIENT_ID = "demo_client_uid"
    const val DEMO_CLIENT_NAME = "Demo Client"
    const val DEMO_DRIVER_ID = "demo_driver_uid"
    const val DEMO_DRIVER_NAME = "Demo Driver"

    val requests = MutableStateFlow(
        listOf(
            RideRequest(
                id = "demo_req_2",
                passengerId = "demo_client_2",
                passengerName = "Demo Client 2",
                origin = "Saldanha",
                destination = "Campo Grande",
                requestedTime = Date(System.currentTimeMillis() + 35 * 60 * 1000),
                estimatedPrice = 5.1,
                status = RequestStatus.OPEN,
                createdAt = Date()
            ),
            RideRequest(
                id = "demo_req_3",
                passengerId = "demo_client_3",
                passengerName = "Demo Client 3",
                origin = "Alvalade",
                destination = "Oriente",
                requestedTime = Date(System.currentTimeMillis() + 50 * 60 * 1000),
                estimatedPrice = 6.0,
                status = RequestStatus.OPEN,
                createdAt = Date()
            )
        )
    )

    fun canPassengerCreateRequest(passengerId: String = DEMO_CLIENT_ID): Boolean {
        return requests.value.none {
            it.passengerId == passengerId && (it.status == RequestStatus.OPEN || it.status == RequestStatus.ACCEPTED)
        }
    }

    fun hasActiveRideForDriver(driverId: String = DEMO_DRIVER_ID): Boolean {
        return requests.value.any { it.driverId == driverId && it.status == RequestStatus.ACCEPTED }
    }

    fun submitRequest(origin: String, destination: String, requestedTime: Date = Date()): Boolean {
        if (!canPassengerCreateRequest()) return false
        requests.value = listOf(
            RideRequest(
                id = "demo_req_${UUID.randomUUID()}",
                passengerId = DEMO_CLIENT_ID,
                passengerName = DEMO_CLIENT_NAME,
                origin = origin,
                destination = destination,
                requestedTime = requestedTime,
                estimatedPrice = 3.5,
                status = RequestStatus.OPEN,
                createdAt = Date()
            )
        ) + requests.value
        return true
    }

    fun acceptRequest(requestId: String): Boolean {
        if (hasActiveRideForDriver()) return false
        var accepted = false
        requests.value = requests.value.map {
            if (it.id == requestId && it.status == RequestStatus.OPEN) {
                accepted = true
                it.copy(
                    status = RequestStatus.ACCEPTED,
                    driverId = DEMO_DRIVER_ID,
                    driverName = DEMO_DRIVER_NAME,
                    driverRating = 4.8
                )
            } else it
        }
        return accepted
    }

    fun denyRequest(requestId: String) {
        requests.value = requests.value.map {
            if (it.id == requestId && it.status == RequestStatus.OPEN) it.copy(status = RequestStatus.CANCELLED) else it
        }
    }

    fun completeRequest(requestId: String): Boolean {
        var completed = false
        requests.value = requests.value.map {
            if (it.id == requestId && it.driverId == DEMO_DRIVER_ID && it.status == RequestStatus.ACCEPTED) {
                completed = true
                it.copy(status = RequestStatus.COMPLETED)
            } else it
        }
        return completed
    }
}
