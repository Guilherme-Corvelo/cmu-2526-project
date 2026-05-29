package pt.ulisboa.tecnico.sharist.ui.home

import android.view.*
import android.widget.*
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import pt.ulisboa.tecnico.sharist.R
import pt.ulisboa.tecnico.sharist.data.model.Ride
import pt.ulisboa.tecnico.sharist.utils.ImageLoader
import pt.ulisboa.tecnico.sharist.utils.NetworkMonitor
import java.text.SimpleDateFormat
import java.util.*

class RideAdapter(
    private val network: NetworkMonitor,
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

        private val dateFmt = SimpleDateFormat("dd MMM, HH:mm", Locale.getDefault())

        fun bind(ride: Ride) {
            tvDriverName.text = ride.driverName
            tvRating.text     = "★ %.1f".format(ride.driverRating)
            tvRoute.text      = "${ride.origin}  →  ${ride.destination}"
            tvDeparture.text  = ride.departureTime?.let { dateFmt.format(it) } ?: "—"
            tvSeats.text      = "${ride.seatsAvailable} seat(s) left"
            tvPrice.text      = "€%.2f / seat".format(ride.pricePerSeat)

            tvPendingBadge.visibility = if (ride.isPending) View.VISIBLE else View.GONE
            tvPeriodicBadge.visibility = if (ride.periodic) View.VISIBLE else View.GONE
            if (ride.periodic && ride.periodicLabel.isNotBlank()) {
                tvPeriodicBadge.text = ride.periodicLabel
            } else {
                tvPeriodicBadge.text = "Periodic"
            }

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
