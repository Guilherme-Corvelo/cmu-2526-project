package pt.ulisboa.tecnico.sharist.ui.profile

import android.content.Intent
import android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
import android.net.Uri
import android.provider.OpenableColumns
import android.os.Bundle
import android.view.*
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts.OpenDocument
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
import pt.ulisboa.tecnico.sharist.ui.demo.DemoRideStore
import pt.ulisboa.tecnico.sharist.utils.ImageLoader
import pt.ulisboa.tecnico.sharist.utils.SessionManager

class ProfileFragment : Fragment() {

    private val app by lazy { requireActivity().application as SharISTApp }
    private val userRepo by lazy { app.userRepository }
    private val requestRepo by lazy { app.requestRepository }
    private val session by lazy { app.sessionManager }

    private var currentProfile: User? = null
    private var isOwnProfile: Boolean = false
    private var pendingPhotoTarget: PhotoTarget = PhotoTarget.PROFILE

    private enum class PhotoTarget { PROFILE, CAR }

    private val pickImage = registerForActivityResult(OpenDocument()) { uri: Uri? ->
        val user = currentProfile ?: return@registerForActivityResult
        if (!isOwnProfile || uri == null) return@registerForActivityResult

        viewLifecycleOwner.lifecycleScope.launch {
            runCatching {
                persistImageUri(uri)
                val stableUri = copyProfileImageToAppStorage(uri, pendingPhotoTarget)
                val updated = when (pendingPhotoTarget) {
                    PhotoTarget.PROFILE -> user.copy(photoUrl = stableUri.toString())
                    PhotoTarget.CAR -> user.copy(carPhotoUrl = stableUri.toString())
                }
                userRepo.updateProfile(updated)
                updated
            }.onSuccess { updated ->
                currentProfile = updated
                bindImages(requireView(), updated)
                Toast.makeText(requireContext(), "Photo saved", Toast.LENGTH_SHORT).show()
            }.onFailure { e ->
                Toast.makeText(requireContext(), "Could not save photo: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun persistImageUri(uri: Uri) {
        runCatching {
            requireContext().contentResolver.takePersistableUriPermission(
                uri,
                FLAG_GRANT_READ_URI_PERMISSION
            )
        }
    }

    private fun copyProfileImageToAppStorage(uri: Uri, target: PhotoTarget): Uri {
        val userId = currentProfile?.uid?.ifBlank { "current" } ?: "current"
        val safeUserId = userId.replace(Regex("[^A-Za-z0-9_.-]"), "_")
        val extension = extensionForUri(uri)
        val fileName = when (target) {
            PhotoTarget.PROFILE -> "${safeUserId}_profile$extension"
            PhotoTarget.CAR -> "${safeUserId}_car$extension"
        }
        val imageDir = java.io.File(requireContext().filesDir, "profile_images").apply { mkdirs() }
        val dest = java.io.File(imageDir, fileName)
        requireContext().contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "Could not open selected image" }
            dest.outputStream().use { output -> input.copyTo(output) }
        }
        return Uri.fromFile(dest)
    }

    private fun extensionForUri(uri: Uri): String {
        val fromName = runCatching {
            requireContext().contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull()
        val dotExtension = fromName
            ?.substringAfterLast('.', missingDelimiterValue = "")
            ?.takeIf { it.length in 1..5 && it.all { ch -> ch.isLetterOrDigit() } }
            ?.let { ".$it" }
        if (dotExtension != null) return dotExtension

        return when (requireContext().contentResolver.getType(uri)) {
            "image/png" -> ".png"
            "image/webp" -> ".webp"
            "image/gif" -> ".gif"
            else -> ".jpg"
        }
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
        val btnProfilePhoto = view.findViewById<Button>(R.id.btn_pick_profile_photo)
        val btnCarPhoto = view.findViewById<Button>(R.id.btn_pick_car_photo)
        val btnLogout = view.findViewById<Button>(R.id.btn_logout)
        val btnBack = view.findViewById<android.widget.ImageButton>(R.id.btn_back)

        btnBack.setOnClickListener {
            requireActivity().onBackPressedDispatcher.onBackPressed()
        }

        val session = app.sessionManager
        val currentUid = userRepo.currentUid ?: session.uid
        val explicitTargetUid = arguments?.getString("userId")
        val targetUid = explicitTargetUid ?: currentUid
        isOwnProfile = explicitTargetUid == null || targetUid == currentUid

        btnProfilePhoto.visibility = if (isOwnProfile) View.VISIBLE else View.GONE
        btnCarPhoto.visibility = if (isOwnProfile) View.VISIBLE else View.GONE
        btnProfilePhoto.setOnClickListener {
            pendingPhotoTarget = PhotoTarget.PROFILE
            pickImage.launch(arrayOf("image/*"))
        }
        btnCarPhoto.setOnClickListener {
            pendingPhotoTarget = PhotoTarget.CAR
            pickImage.launch(arrayOf("image/*"))
        }

        if (session.forceDemoMode && isOwnProfile) {
            val defaultDemoUser = if (session.role == SessionManager.ROLE_DRIVER) {
                User(uid = DemoRequestStore.DEMO_DRIVER_ID, displayName = DemoRequestStore.DEMO_DRIVER_NAME, email = "demo_driver@demo.app", driver = true, rating = 4.8, ratingCount = 36, vehicleType = VehicleType.SEDAN, vehiclePlate = "DEMO-01")
            } else {
                User(uid = DemoRequestStore.DEMO_CLIENT_ID, displayName = DemoRequestStore.DEMO_CLIENT_NAME, email = "demo_client@demo.app", driver = false, rating = 4.9, ratingCount = 12)
            }
            val demoUser = DemoRideStore.getUser(defaultDemoUser.uid) ?: defaultDemoUser
            bindProfile(demoUser, tvName, tvEmail, tvRole, tvBalance, tvRatingSummary, tvHistogram, tvVehicles)
            bindImages(view, demoUser)
            observeReviews(demoUser.uid, tvComments, tvRatingSummary, tvHistogram)
        } else if (targetUid != null) {
            viewLifecycleOwner.lifecycleScope.launch {
                val user = userRepo.getUser(targetUid)
                if (user != null) {
                    bindProfile(user, tvName, tvEmail, tvRole, tvBalance, tvRatingSummary, tvHistogram, tvVehicles)
                    bindImages(view, user)
                    observeReviews(user.uid, tvComments, tvRatingSummary, tvHistogram)
                } else {
                    tvName.text = getString(R.string.unknown_email)
                    tvEmail.text = getString(R.string.unknown_email)
                }
            }
        }

        btnLogout.visibility = if (isOwnProfile) View.VISIBLE else View.GONE
        btnLogout.setOnClickListener {
            app.remoteDataSource.clearListeners()
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
        currentProfile = user
        tvName.text = user.displayName
        tvEmail.text = user.email
        tvRole.text = "Role: ${if (user.driver) "Driver" else "Passenger"} • Reliability: %.0f%%".format(user.trustScore * 100)
        val viewingPassengerAsDriver = !isOwnProfile && session.role == SessionManager.ROLE_DRIVER && !user.driver
        tvBalance.visibility = if (viewingPassengerAsDriver) View.GONE else View.VISIBLE
        if (!viewingPassengerAsDriver) {
            tvBalance.text = "Balance: €%.2f".format(user.balance)
        }
        tvRatingSummary.text = "Loading rating..."
        tvHistogram.text = ""
        tvVehicles.text = if (user.driver) {
            val typeText = user.vehicleType.displayName
            val seatsText = user.vehicleType.maxSeats
            val plateText = if (user.vehiclePlate.isBlank()) "N/A" else user.vehiclePlate
            "• $typeText ($seatsText seats)\n• Plate: $plateText"
        } else "No registered vehicles"
    }

    private fun bindImages(view: View, user: User) {
        val profilePhoto = view.findViewById<ImageView>(R.id.iv_profile_photo)
        val carPhoto = view.findViewById<ImageView>(R.id.iv_car_photo)
        ImageLoader.load(profilePhoto, user.photoUrl, R.drawable.ic_person_placeholder, app.networkMonitor) {
            it.contentDescription = "Profile photo placeholder. Tap to download on metered data."
        }
        ImageLoader.load(carPhoto, user.carPhotoUrl, R.drawable.ic_car_placeholder, app.networkMonitor) {
            it.contentDescription = "Vehicle photo placeholder. Tap to download on metered data."
        }
        carPhoto.visibility = if (user.driver || !user.carPhotoUrl.isNullOrBlank()) View.VISIBLE else View.GONE
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
                        val count = reviews.size
                        val avg = reviews.map { it.rating }.average()
                        tvRatingSummary.text = "Rating: %.1f (%d ratings)".format(avg, count)
                        tvHistogram.text = buildHistogramFromReviews(reviews)

                        tvComments.text = reviews.take(7).joinToString("\n") {
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
