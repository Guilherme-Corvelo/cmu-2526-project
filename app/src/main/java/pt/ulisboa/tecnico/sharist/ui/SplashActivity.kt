package pt.ulisboa.tecnico.sharist.ui

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import pt.ulisboa.tecnico.sharist.SharISTApp
import pt.ulisboa.tecnico.sharist.ui.auth.AuthActivity

class SplashActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as SharISTApp
        val session = app.sessionManager
        val remote = app.remoteDataSource
        
        val next = if (remote.currentUid != null && session.isLoggedIn) {
            MainActivity::class.java
        } else {
            AuthActivity::class.java
        }
        startActivity(Intent(this, next))
        finish()
    }
}
