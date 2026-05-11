package pt.ulisboa.tecnico.sharist.ui.passenger

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.fragment.app.Fragment
import pt.ulisboa.tecnico.sharist.R
import pt.ulisboa.tecnico.sharist.ui.demo.DemoRequestStore

class RequestRideFragment : Fragment() {
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_request_ride, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val etOrigin = view.findViewById<EditText>(R.id.et_origin)
        val etDestination = view.findViewById<EditText>(R.id.et_destination)
        view.findViewById<Button>(R.id.btn_request).setOnClickListener {
            val origin = etOrigin.text.toString().trim()
            val destination = etDestination.text.toString().trim()
            if (origin.isBlank() || destination.isBlank()) {
                Toast.makeText(requireContext(), "Fill origin and destination", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val created = DemoRequestStore.submitRequest(origin, destination)
            if (!created) {
                Toast.makeText(
                    requireContext(),
                    "You already have an active request/ride. Complete or cancel it first.",
                    Toast.LENGTH_LONG
                ).show()
                return@setOnClickListener
            }
            etOrigin.text?.clear()
            etDestination.text?.clear()
            Toast.makeText(requireContext(), "Demo request created", Toast.LENGTH_SHORT).show()
        }
    }
}
