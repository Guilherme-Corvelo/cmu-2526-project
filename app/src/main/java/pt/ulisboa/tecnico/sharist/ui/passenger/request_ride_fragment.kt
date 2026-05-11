package pt.ulisboa.tecnico.sharist.ui.passenger

import android.os.Bundle
import android.app.TimePickerDialog
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import pt.ulisboa.tecnico.sharist.R
import pt.ulisboa.tecnico.sharist.ui.demo.DemoRequestStore
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class RequestRideFragment : Fragment() {
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_request_ride, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val etOrigin = view.findViewById<EditText>(R.id.et_origin)
        val etDestination = view.findViewById<EditText>(R.id.et_destination)
        val tvSelectedTime = view.findViewById<TextView>(R.id.tv_selected_time)
        val selectedTime = Calendar.getInstance()
        val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

        fun renderSelectedTime() {
            tvSelectedTime.text = "Requested time: ${timeFormat.format(selectedTime.time)}"
        }

        renderSelectedTime()

        view.findViewById<Button>(R.id.btn_pick_time).setOnClickListener {
            TimePickerDialog(
                requireContext(),
                { _, hourOfDay, minute ->
                    selectedTime.set(Calendar.HOUR_OF_DAY, hourOfDay)
                    selectedTime.set(Calendar.MINUTE, minute)
                    selectedTime.set(Calendar.SECOND, 0)
                    selectedTime.set(Calendar.MILLISECOND, 0)
                    renderSelectedTime()
                },
                selectedTime.get(Calendar.HOUR_OF_DAY),
                selectedTime.get(Calendar.MINUTE),
                true
            ).show()
        }

        view.findViewById<Button>(R.id.btn_request).setOnClickListener {
            val origin = etOrigin.text.toString().trim()
            val destination = etDestination.text.toString().trim()
            if (origin.isBlank() || destination.isBlank()) {
                Toast.makeText(requireContext(), "Fill origin and destination", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val created = DemoRequestStore.submitRequest(origin, destination, selectedTime.time)
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
