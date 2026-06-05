package pt.ulisboa.tecnico.sharist.ui.auth

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.*
import com.google.firebase.FirebaseNetworkException
import kotlinx.coroutines.launch
import pt.ulisboa.tecnico.sharist.R
import pt.ulisboa.tecnico.sharist.SharISTApp
import pt.ulisboa.tecnico.sharist.data.model.User
import pt.ulisboa.tecnico.sharist.data.model.VehicleType
import pt.ulisboa.tecnico.sharist.data.repository.UserRepository
import pt.ulisboa.tecnico.sharist.ui.MainActivity
import pt.ulisboa.tecnico.sharist.ui.demo.DemoRequestStore
import pt.ulisboa.tecnico.sharist.utils.SessionManager

class AuthViewModel(private val userRepo: UserRepository, private val session: SessionManager) : ViewModel() {
    sealed class State { object Idle: State(); object Loading: State(); object Success: State(); data class Error(val msg: String): State() }
    private val _state = MutableLiveData<State>(State.Idle)
    val state: LiveData<State> = _state

    fun signIn(email: String, password: String) {
        _state.value = State.Loading
        session.forceDemoMode = false // Ensure we use real Firebase
        viewModelScope.launch {
            runCatching {
                val r = userRepo.signIn(email, password)
                val uid = r?.user?.uid ?: error("No user")
                val user = userRepo.getUser(uid) ?: error("Profile not found")
                session.save(uid, if (user.driver) SessionManager.ROLE_DRIVER else SessionManager.ROLE_PASSENGER, user.displayName)
            }
                .onSuccess { _state.value = State.Success }
                .onFailure { _state.value = State.Error(it.toAuthErrorMessage("Login failed")) }
        }
    }

    fun register(
        email: String,
        password: String,
        name: String,
        isDriver: Boolean,
        vehicleType: VehicleType,
        vehiclePlate: String
    ) {
        _state.value = State.Loading
        session.forceDemoMode = false // Ensure we use real Firebase
        viewModelScope.launch {
            runCatching {
                val r = userRepo.register(email, password)
                val uid = r?.user?.uid ?: error("No UID")
                userRepo.createProfile(
                    User(
                        uid = uid,
                        displayName = name,
                        email = email,
                        driver = isDriver,
                        balance = 1000.0,
                        vehicleType = if (isDriver) vehicleType else VehicleType.NONE,
                        vehiclePlate = if (isDriver) vehiclePlate else ""
                    )
                )
                session.save(uid, if (isDriver) SessionManager.ROLE_DRIVER else SessionManager.ROLE_PASSENGER, name)
            }
                .onSuccess { _state.value = State.Success }
                .onFailure { _state.value = State.Error(it.toAuthErrorMessage("Registration failed")) }
        }
    }

    private fun Throwable.toAuthErrorMessage(fallback: String): String {
        val rawMessage = message ?: return fallback
        val isNetworkError = this is FirebaseNetworkException ||
                rawMessage.contains("network", ignoreCase = true) ||
                rawMessage.contains("timeout", ignoreCase = true) ||
                rawMessage.contains("interrupted connection", ignoreCase = true) ||
                rawMessage.contains("unreachable host", ignoreCase = true)
        if (isNetworkError) {
            return "Network connection failed. Check your internet connection and try again, or use Demo Client/Driver offline."
        }
        return rawMessage
    }
}

class AuthActivity : AppCompatActivity() {
    private val vm: AuthViewModel by viewModels {
        object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val app = application as SharISTApp
                @Suppress("UNCHECKED_CAST")
                return AuthViewModel(app.userRepository, app.sessionManager) as T
            }
        }
    }

    private var isLoginMode = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_auth)
        val app = application as SharISTApp

        val tabLogin = findViewById<Button>(R.id.tab_login)
        val tabRegister = findViewById<Button>(R.id.tab_register)
        val etEmail = findViewById<EditText>(R.id.et_email)
        val etPassword = findViewById<EditText>(R.id.et_password)
        val etName = findViewById<EditText>(R.id.et_name)
        val tilName = findViewById<View>(R.id.til_name)
        val layoutRegisterOnly = findViewById<View>(R.id.layout_register_only)
        val driverToggle = findViewById<View>(R.id.layout_driver_toggle)
        val switchDriver = findViewById<CompoundButton>(R.id.switch_driver)
        val spinnerVehicleType = findViewById<Spinner>(R.id.spinner_vehicle_type)
        val etVehiclePlate = findViewById<EditText>(R.id.et_vehicle_plate)
        val layoutDriverDetails = findViewById<View>(R.id.layout_driver_details)
        val tvError = findViewById<TextView>(R.id.tv_error)
        val progressBar = findViewById<ProgressBar>(R.id.progress_bar)
        val btnSubmit = findViewById<Button>(R.id.btn_submit)

        val tvDemoHint = findViewById<TextView>(R.id.tv_demo_hint)
        val layoutDemoButtons = findViewById<View>(R.id.layout_demo_buttons)

        // Demo entry shortcuts:
        // - Client button starts the app as a demo passenger.
        // - Driver button starts the app as a demo driver.
        val btnDemoClient = findViewById<Button>(R.id.btn_demo_client)
        val btnDemoDriver = findViewById<Button>(R.id.btn_demo_driver)

        fun switchMode(login: Boolean) {
            isLoginMode = login
            layoutRegisterOnly.visibility = if (login) View.GONE else View.VISIBLE
            btnSubmit.text = if (login) "Sign In" else "Create Account"
            tabLogin.alpha = if (login) 1f else 0.4f
            tabRegister.alpha = if (!login) 1f else 0.4f
            // Keep demo controls visible in both Sign In and Register tabs
            tvDemoHint.visibility = View.VISIBLE
            layoutDemoButtons.visibility = View.VISIBLE
            tvError.visibility = View.GONE
        }

        switchMode(true)

        tabLogin.setOnClickListener { switchMode(true) }
        tabRegister.setOnClickListener { switchMode(false) }

        val vehicleOptions = VehicleType.values().filter { it != VehicleType.NONE }
        spinnerVehicleType.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            vehicleOptions.map { "${it.displayName} (${it.maxSeats} seats)" }
        )

        switchDriver.setOnCheckedChangeListener { _, isChecked ->
            layoutDriverDetails.visibility = if (isChecked) View.VISIBLE else View.GONE
        }

        btnSubmit.setOnClickListener {
            if (isLoginMode) {
                vm.signIn(etEmail.text.toString().trim(), etPassword.text.toString())
            } else {
                val selectedVehicle = vehicleOptions.getOrElse(spinnerVehicleType.selectedItemPosition) { VehicleType.SEDAN }
                vm.register(
                    etEmail.text.toString().trim(),
                    etPassword.text.toString(),
                    etName.text.toString().trim(),
                    switchDriver.isChecked,
                    selectedVehicle,
                    etVehiclePlate.text.toString().trim()
                )
            }
        }

        // Quick demo login as passenger/client (bypasses Firebase auth)
        btnDemoClient.setOnClickListener {
            app.sessionManager.forceDemoMode = true
            app.sessionManager.save(
                DemoRequestStore.DEMO_CLIENT_ID,
                SessionManager.ROLE_PASSENGER,
                DemoRequestStore.DEMO_CLIENT_NAME
            )
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        }

        // Quick demo login as driver (bypasses Firebase auth)
        btnDemoDriver.setOnClickListener {
            app.sessionManager.forceDemoMode = true
            app.sessionManager.save(
                DemoRequestStore.DEMO_DRIVER_ID,
                SessionManager.ROLE_DRIVER,
                DemoRequestStore.DEMO_DRIVER_NAME
            )
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        }

        vm.state.observe(this) { state ->
            progressBar.visibility = if (state is AuthViewModel.State.Loading) View.VISIBLE else View.GONE
            btnSubmit.isEnabled = state !is AuthViewModel.State.Loading
            when (state) {
                is AuthViewModel.State.Loading -> tvError.visibility = View.GONE
                is AuthViewModel.State.Success -> startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
                is AuthViewModel.State.Error -> {
                    tvError.visibility = View.VISIBLE
                    tvError.text = state.msg
                }
                else -> Unit
            }
        }
    }
}
