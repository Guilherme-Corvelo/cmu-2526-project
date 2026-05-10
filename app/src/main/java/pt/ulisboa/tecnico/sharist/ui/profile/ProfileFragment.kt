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

        val tvEmail  = view.findViewById<TextView>(R.id.tv_email)
        val btnLogout = view.findViewById<Button>(R.id.btn_logout)

        val session = (requireActivity().application as SharISTApp).sessionManager
        val uid = userRepo.currentUid
        if (session.forceDemoMode) {
            val (name, email) = if (session.role == SessionManager.ROLE_DRIVER) {
                DemoRequestStore.DEMO_DRIVER_NAME to "demo_driver@demo.app"
            } else {
                DemoRequestStore.DEMO_CLIENT_NAME to "demo_client@demo.app"
            }
            tvEmail.text = "$name ($email)"
        } else if (uid != null) {
            viewLifecycleOwner.lifecycleScope.launch {
                val user = userRepo.getUser(uid)
                tvEmail.text = user?.email ?: getString(R.string.unknown_email)
            }
        } else {
            tvEmail.text = getString(R.string.unknown_email)
        }

        btnLogout.setOnClickListener {
            userRepo.signOut()
            startActivity(Intent(requireContext(), AuthActivity::class.java))
            requireActivity().finish()
        }
    }
}
