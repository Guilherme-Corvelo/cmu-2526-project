package pt.ulisboa.tecnico.sharist.ui.profile

import android.content.Intent
import android.os.Bundle
import android.view.*
import android.widget.Button
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import pt.ulisboa.tecnico.sharist.R
import pt.ulisboa.tecnico.sharist.SharISTApp
import pt.ulisboa.tecnico.sharist.data.model.User
import pt.ulisboa.tecnico.sharist.ui.auth.AuthActivity
import pt.ulisboa.tecnico.sharist.ui.demo.DemoRequestStore
import pt.ulisboa.tecnico.sharist.utils.SessionManager

class ProfileFragment : Fragment() {

    private val userRepo by lazy {
        (requireActivity().application as SharISTApp).userRepository
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_profile, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val tvName = view.findViewById<TextView>(R.id.tv_name)
        val tvEmail = view.findViewById<TextView>(R.id.tv_email)
        val tvRole = view.findViewById<TextView>(R.id.tv_role)
        val tvRatingSummary = view.findViewById<TextView>(R.id.tv_rating_summary)
        val tvHistogram = view.findViewById<TextView>(R.id.tv_histogram)
        val tvVehicles = view.findViewById<TextView>(R.id.tv_vehicles)
        val tvComments = view.findViewById<TextView>(R.id.tv_comments)
        val btnLogout = view.findViewById<Button>(R.id.btn_logout)

        val session = (requireActivity().application as SharISTApp).sessionManager
        val uid = userRepo.currentUid
        if (session.forceDemoMode) {
            val demoUser = if (session.role == SessionManager.ROLE_DRIVER) {
                User(DemoRequestStore.DEMO_DRIVER_ID, DemoRequestStore.DEMO_DRIVER_NAME, "demo_driver@demo.app", driver = true, rating = 4.8, ratingCount = 36)
            } else {
                User(DemoRequestStore.DEMO_CLIENT_ID, DemoRequestStore.DEMO_CLIENT_NAME, "demo_client@demo.app", driver = false, rating = 4.9, ratingCount = 12)
            }
            bindProfile(demoUser, tvName, tvEmail, tvRole, tvRatingSummary, tvHistogram, tvVehicles, tvComments)
        } else if (uid != null) {
            viewLifecycleOwner.lifecycleScope.launch {
                val user = userRepo.getUser(uid)
                if (user != null) {
                    bindProfile(user, tvName, tvEmail, tvRole, tvRatingSummary, tvHistogram, tvVehicles, tvComments)
                } else {
                    tvName.text = getString(R.string.unknown_email)
                    tvEmail.text = getString(R.string.unknown_email)
                }
            }
        }

        btnLogout.setOnClickListener {
            userRepo.signOut()
            startActivity(Intent(requireContext(), AuthActivity::class.java))
            requireActivity().finish()
        }
    }

    private fun bindProfile(
        user: User,
        tvName: TextView,
        tvEmail: TextView,
        tvRole: TextView,
        tvRatingSummary: TextView,
        tvHistogram: TextView,
        tvVehicles: TextView,
        tvComments: TextView
    ) {
        tvName.text = user.displayName
        tvEmail.text = user.email
        tvRole.text = "Role: ${if (user.driver) "Driver" else "Passenger"}"
        tvRatingSummary.text = "Rating: %.1f (%d ratings)".format(user.rating, user.ratingCount)
        tvHistogram.text = buildHistogram(user.rating, user.ratingCount)
        tvVehicles.text = if (user.driver) "• Toyota Prius\n• Renault Clio" else "No registered vehicles"
        tvComments.text = "• Great communicator\n• Punctual and safe\n• Friendly and respectful"
    }

    private fun buildHistogram(avg: Double, count: Int): String {
        if (count == 0) return "No ratings yet"
        val five = (count * (avg / 5.0)).toInt().coerceIn(0, count)
        val remaining = count - five
        val four = (remaining * 0.55).toInt()
        val three = (remaining * 0.25).toInt()
        val two = (remaining * 0.15).toInt()
        val one = (remaining - four - three - two).coerceAtLeast(0)
        return "5★: $five\n4★: $four\n3★: $three\n2★: $two\n1★: $one"
    }
}
