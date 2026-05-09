package pt.ulisboa.tecnico.sharist.ui.profile

import android.content.Intent
import android.os.Bundle
import android.view.*
import android.widget.Button
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.google.firebase.auth.FirebaseAuth
import pt.ulisboa.tecnico.sharist.R
import pt.ulisboa.tecnico.sharist.SharISTApp
import pt.ulisboa.tecnico.sharist.ui.auth.AuthActivity

class ProfileFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_profile, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val tvEmail  = view.findViewById<TextView>(R.id.tv_email)
        val btnLogout = view.findViewById<Button>(R.id.btn_logout)

        tvEmail.text = FirebaseAuth.getInstance().currentUser?.email ?: "Not logged in"

        btnLogout.setOnClickListener {
            FirebaseAuth.getInstance().signOut()
            startActivity(Intent(requireContext(), AuthActivity::class.java))
            requireActivity().finish()
        }
    }
}
