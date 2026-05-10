package pt.ulisboa.tecnico.sharist.ui.passenger

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import pt.ulisboa.tecnico.sharist.R

class RateDriverDialog(
    private val driverName: String,
    private val onSubmit: (stars: Int, comment: String) -> Unit
) : BottomSheetDialogFragment() {
    private var selectedStars = 5
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View =
        inflater.inflate(R.layout.dialog_rate_driver, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val tvTitle = view.findViewById<TextView>(R.id.tv_title)
        val starBtns = listOf(
            view.findViewById<Button>(R.id.btn_star1), view.findViewById(R.id.btn_star2), view.findViewById(R.id.btn_star3), view.findViewById(R.id.btn_star4), view.findViewById(R.id.btn_star5)
        )
        val etComment = view.findViewById<EditText>(R.id.et_comment)
        val btnSubmit = view.findViewById<Button>(R.id.btn_submit_review)
        tvTitle.text = "Rate your ride with $driverName"
        fun highlight(n: Int) { selectedStars = n; starBtns.forEachIndexed { idx, b -> b.text = if (idx < n) "★" else "☆"; b.alpha = if (idx < n) 1f else 0.4f } }
        highlight(5)
        starBtns.forEachIndexed { idx, b -> b.setOnClickListener { highlight(idx + 1) } }
        btnSubmit.setOnClickListener { onSubmit(selectedStars, etComment.text.toString().trim()); dismiss() }
    }
}
