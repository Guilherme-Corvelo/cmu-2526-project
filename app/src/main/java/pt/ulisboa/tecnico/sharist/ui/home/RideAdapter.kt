package pt.ulisboa.tecnico.sharist.ui.home

import android.content.res.ColorStateList
import android.graphics.Color
import android.view.*
import android.widget.*
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import pt.ulisboa.tecnico.sharist.R
import pt.ulisboa.tecnico.sharist.data.model.Ride
import pt.ulisboa.tecnico.sharist.data.model.RideStatus
import pt.ulisboa.tecnico.sharist.utils.ImageLoader
import pt.ulisboa.tecnico.sharist.utils.NetworkMonitor
import java.text.SimpleDateFormat
import java.util.*

class RideAdapter(
    private val network: NetworkMonitor,
    private val currentUid: String? = null,
    private val onCancelRide: ((Ride) -> Unit)? = null,
    private val onRideClick: (Ride) -> Unit
) : ListAdapter<Ride, RideAdapter.RideViewHolder>(DIFF_CALLBACK) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RideViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_ride, parent, false)
        return RideViewHolder(view)
    }

    override fun onBindViewHolder(holder: RideViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class RideViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val ivDriverPhoto: ImageView = itemView.findViewById(R.id.iv_driver_photo)
        private val tvDriverName:  TextView  = itemView.findViewById(R.id.tv_driver_name)
        private val tvRating:      TextView  = itemView.findViewById(R.id.tv_rating)
        private val tvRoute:       TextView  = itemView.findViewById(R.id.tv_route)
        private val tvDeparture:   TextView  = itemView.findViewById(R.id.tv_departure)
        private val tvSeats:       TextView  = itemView.findViewById(R.id.tv_seats)
        private val tvPrice:       TextView  = itemView.findViewById(R.id.tv_price)
        private val ivMeteredHint: ImageView = itemView.findViewById(R.id.iv_metered_hint)
        private val tvPeriodicBadge: TextView = itemView.findViewById(R.id.tv_periodic_badge)
        private val tvPendingBadge: TextView = itemView.findViewById(R.id.tv_pending_badge)
        private val tvWeatherBadge: TextView = itemView.findViewById(R.id.tv_weather_badge)
        private val btnCancel: Button? = itemView.findViewById(R.id.btn_cancel_ride)
        private val btnDetails: Button? = itemView.findViewById(R.id.btn_details)

        private val dateFmt = SimpleDateFormat("dd MMM, HH:mm", Locale.getDefault())

        fun bind(ride: Ride) {
            tvDriverName.text = ride.driverName
            tvRating.text     = "★ %.1f".format(ride.driverRating)
            tvRoute.text      = "${ride.origin}  →  ${ride.destination}"
            tvDeparture.text  = ride.departureTime?.let { dateFmt.format(it) } ?: "—"
            tvSeats.text      = "${ride.seatsAvailable} seat(s) left"
            tvPrice.text      = "€%.2f / seat".format(ride.pricePerSeat)

            tvPeriodicBadge.visibility = if (ride.periodic) View.VISIBLE else View.GONE
            if (ride.periodic && ride.periodicLabel.isNotBlank()) {
                tvPeriodicBadge.text = ride.periodicLabel
            } else {
                tvPeriodicBadge.text = "Periodic"
            }

            // Weather Warning Badge
            tvWeatherBadge.visibility = if (ride.weatherWarning) View.VISIBLE else View.GONE

            // Handle Pending Badges (Sync or Requests)
            val isDriverView = currentUid != null && ride.driverId == currentUid
            tvPendingBadge.clearAnimation()
            if (isDriverView && ride.hasNewRequests) {
                tvPendingBadge.visibility = View.VISIBLE
                tvPendingBadge.text = "NEW PASSENGERS"
                tvPendingBadge.backgroundTintList = ColorStateList.valueOf(Color.RED)
                
                // Flashing animation
                val anim = android.view.animation.AlphaAnimation(1.0f, 0.2f).apply {
                    duration = 500
                    repeatMode = android.view.animation.Animation.REVERSE
                    repeatCount = android.view.animation.Animation.INFINITE
                }
                tvPendingBadge.startAnimation(anim)
            } else if (ride.isPending) {
                tvPendingBadge.visibility = View.VISIBLE
                tvPendingBadge.text = "PENDING"
                tvPendingBadge.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#FF9800"))
            } else {
                tvPendingBadge.visibility = View.GONE
            }

            // Show cancel button if we have a callback and the ride is cancellable
            val isOwner = currentUid != null && ride.driverId == currentUid
            val isCancellable = ride.status == RideStatus.OPEN || ride.status == RideStatus.FULL
            
            btnCancel?.visibility = if (onCancelRide != null && isOwner && isCancellable) {
                View.VISIBLE
            } else {
                View.GONE
            }
            btnCancel?.setOnClickListener { onCancelRide?.invoke(ride) }

            btnDetails?.setOnClickListener { onRideClick(ride) }

            // Lazy image load with metered awareness
            ImageLoader.load(
                imageView   = ivDriverPhoto,
                url         = ride.driverPhotoUrl,
                placeholder = R.drawable.ic_person_placeholder,
                network     = network,
                onMetered   = { ivMeteredHint.visibility = View.VISIBLE }
            )
            ivMeteredHint.setOnClickListener { ivMeteredHint.visibility = View.GONE }

            itemView.setOnClickListener { onRideClick(ride) }
        }
    }

    companion object {
        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<Ride>() {
            override fun areItemsTheSame(oldItem: Ride, newItem: Ride) = oldItem.id == newItem.id
            override fun areContentsTheSame(oldItem: Ride, newItem: Ride) = oldItem == newItem
        }
    }
}
