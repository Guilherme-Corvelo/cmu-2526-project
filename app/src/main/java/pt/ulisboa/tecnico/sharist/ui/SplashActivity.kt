package pt.ulisboa.tecnico.sharist.ui

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import pt.ulisboa.tecnico.sharist.SharISTApp
import pt.ulisboa.tecnico.sharist.MainActivity
import pt.ulisboa.tecnico.sharist.ui.auth.AuthActivity

class SplashActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        val userRepo = (application as SharISTApp).userRepository
        val next = if (userRepo.currentUid != null) {
            MainActivity::class.java
        } else {
            AuthActivity::class.java
        }
        startActivity(Intent(this, next))
        finish()
    }
}
