package pt.ulisboa.tecnico.sharist.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.setupWithNavController
import com.google.android.material.bottomnavigation.BottomNavigationView
import pt.ulisboa.tecnico.sharist.R
import pt.ulisboa.tecnico.sharist.SharISTApp

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        val session = (application as SharISTApp).sessionManager
        val bottomNav = findViewById<BottomNavigationView>(R.id.bottom_nav)
        val navHost = supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as NavHostFragment
        val navController = navHost.navController
        if (session.isDriver) { bottomNav.inflateMenu(R.menu.bottom_nav_driver); navController.setGraph(R.navigation.nav_graph_driver) }
        else { bottomNav.inflateMenu(R.menu.bottom_nav_passenger); navController.setGraph(R.navigation.nav_graph_passenger) }
        bottomNav.setupWithNavController(navController)
    }
}
