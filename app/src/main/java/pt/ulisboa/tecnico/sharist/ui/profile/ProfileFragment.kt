package pt.ulisboa.tecnico.sharist.ui.profile

import android.content.Intent
import android.os.Bundle
import android.view.*
import android.widget.Button
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import pt.ulisboa.tecnico.sharist.R
import pt.ulisboa.tecnico.sharist.SharISTApp
import pt.ulisboa.tecnico.sharist.data.model.Review
import pt.ulisboa.tecnico.sharist.data.model.User
import pt.ulisboa.tecnico.sharist.data.model.VehicleType
import pt.ulisboa.tecnico.sharist.ui.auth.AuthActivity
import pt.ulisboa.tecnico.sharist.ui.demo.DemoRequestStore
import pt.ulisboa.tecnico.sharist.utils.SessionManager

class ProfileFragment : Fragment() {

    private val userRepo by lazy {
        (requireActivity().application as SharISTApp).userRepository
    }

    private val requestRepo by lazy {
        (requireActivity().application as SharISTApp).requestRepository
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_profile, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val tvName = view.findViewById<TextView>(R.id.tv_name)
        val tvEmail = view.findViewById<TextView>(R.id.tv_email)
        val tvRole = view.findViewById<TextView>(R.id.tv_role)
        val tvBalance = view.findViewById<TextView>(R.id.tv_balance)
        val tvRatingSummary = view.findViewById<TextView>(R.id.tv_rating_summary)
        val tvHistogram = view.findViewById<TextView>(R.id.tv_histogram)
        val tvVehicles = view.findViewById<TextView>(R.id.tv_vehicles)
        val tvComments = view.findViewById<TextView>(R.id.tv_comments)
        val btnLogout = view.findViewById<Button>(R.id.btn_logout)
        val btnBack = view.findViewById<android.widget.ImageButton>(R.id.btn_back)

        btnBack.setOnClickListener {
            requireActivity().onBackPressedDispatcher.onBackPressed()
        }

        val session = (requireActivity().application as SharISTApp).sessionManager
        val currentUid = userRepo.currentUid
        val targetUid = arguments?.getString("userId") ?: currentUid
        val isOwnProfile = targetUid == currentUid

        if (session.forceDemoMode && isOwnProfile) {
            val demoUser = if (session.role == SessionManager.ROLE_DRIVER) {
                User(DemoRequestStore.DEMO_DRIVER_ID, DemoRequestStore.DEMO_DRIVER_NAME, "demo_driver@demo.app", driver = true, rating = 4.8, ratingCount = 36, vehicleType = VehicleType.SEDAN, vehiclePlate = "DEMO-01")
            } else {
                User(DemoRequestStore.DEMO_CLIENT_ID, DemoRequestStore.DEMO_CLIENT_NAME, "demo_client@demo.app", driver = false, rating = 4.9, ratingCount = 12)
            }
            bindProfile(demoUser, tvName, tvEmail, tvRole, tvBalance, tvRatingSummary, tvHistogram, tvVehicles)
            observeReviews(demoUser.uid, tvComments, tvRatingSummary, tvHistogram)
        } else if (targetUid != null) {
            viewLifecycleOwner.lifecycleScope.launch {
                val user = userRepo.getUser(targetUid)
                if (user != null) {
                    bindProfile(user, tvName, tvEmail, tvRole, tvBalance, tvRatingSummary, tvHistogram, tvVehicles)
                    observeReviews(user.uid, tvComments, tvRatingSummary, tvHistogram)
                } else {
                    tvName.text = getString(R.string.unknown_email)
                    tvEmail.text = getString(R.string.unknown_email)
                }
            }
        }

        btnLogout.visibility = if (isOwnProfile) View.VISIBLE else View.GONE
        btnLogout.setOnClickListener {
            (requireActivity().application as SharISTApp).remoteDataSource.clearListeners()
            userRepo.signOut()
            session.clear()
            val intent = Intent(requireContext(), AuthActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            startActivity(intent)
            requireActivity().finish()
        }
    }

    private fun bindProfile(
        user: User,
        tvName: TextView,
        tvEmail: TextView,
        tvRole: TextView,
        tvBalance: TextView,
        tvRatingSummary: TextView,
        tvHistogram: TextView,
        tvVehicles: TextView
    ) {
        tvName.text = user.displayName
        tvEmail.text = user.email
        tvRole.text = "Role: ${if (user.driver) "Driver" else "Passenger"}"
        tvBalance.text = "Balance: €%.2f".format(user.balance)
        // These will be updated by observeReviews
        tvRatingSummary.text = "Loading rating..."
        tvHistogram.text = ""
        tvVehicles.text = if (user.driver) {
            val typeText = user.vehicleType.displayName
            val seatsText = user.vehicleType.maxSeats
            val plateText = if (user.vehiclePlate.isBlank()) "N/A" else user.vehiclePlate
            "• $typeText ($seatsText seats)\n• Plate: $plateText"
        } else "No registered vehicles"
    }

    private fun observeReviews(uid: String, tvComments: TextView, tvRatingSummary: TextView, tvHistogram: TextView) {
        viewLifecycleOwner.lifecycleScope.launch {
            requestRepo.getReviewsForUser(uid)
                .catch { e ->
                    tvComments.text = "Error loading reviews: ${e.message}"
                }
                .collect { reviews ->
                    if (reviews.isEmpty()) {
                        tvComments.text = "No reviews yet."
                        tvRatingSummary.text = "Rating: N/A (0 ratings)"
                        tvHistogram.text = "No ratings yet"
                    } else {
                        // Calculate stats from actual review objects
                        val count = reviews.size
                        val avg = reviews.map { it.rating }.average()
                        tvRatingSummary.text = "Rating: %.1f (%d ratings)".format(avg, count)
                        tvHistogram.text = buildHistogramFromReviews(reviews)

                        tvComments.text = reviews.joinToString("\n") {
                            val stars = "★".repeat(it.rating) + "☆".repeat(5 - it.rating)
                            "• $stars${if (it.comment.isNotBlank()) ": ${it.comment}" else ""}"
                        }
                    }
                }
        }
    }

    private fun buildHistogramFromReviews(reviews: List<Review>): String {
        val counts = reviews.groupBy { it.rating }.mapValues { it.value.size }
        val f5 = counts[5] ?: 0
        val f4 = counts[4] ?: 0
        val f3 = counts[3] ?: 0
        val f2 = counts[2] ?: 0
        val f1 = counts[1] ?: 0
        return "5★: $f5\n4★: $f4\n3★: $f3\n2★: $f2\n1★: $f1"
    }
}
