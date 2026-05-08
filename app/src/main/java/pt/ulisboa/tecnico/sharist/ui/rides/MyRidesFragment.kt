package pt.ulisboa.tecnico.sharist.ui.rides

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment

class MyRidesFragment : Fragment() {
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val textView = TextView(requireContext()).apply {
            text = "My Rides - Coming Soon"
            gravity = android.view.Gravity.CENTER
            textSize = 24f
        }
        return textView
    }
}
