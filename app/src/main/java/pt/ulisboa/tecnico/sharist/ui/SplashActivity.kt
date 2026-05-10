package pt.ulisboa.tecnico.sharist.ui

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.auth.FirebaseAuth
import pt.ulisboa.tecnico.sharist.SharISTApp
import pt.ulisboa.tecnico.sharist.ui.auth.AuthActivity

class SplashActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val session = (application as SharISTApp).sessionManager
        val next = if (FirebaseAuth.getInstance().currentUser != null && session.isLoggedIn) MainActivity::class.java else AuthActivity::class.java
        startActivity(Intent(this, next)); finish()
    }
}
