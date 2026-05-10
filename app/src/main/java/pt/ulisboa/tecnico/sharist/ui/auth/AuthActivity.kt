package pt.ulisboa.tecnico.sharist.ui.auth

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.*
import kotlinx.coroutines.launch
import pt.ulisboa.tecnico.sharist.R
import pt.ulisboa.tecnico.sharist.SharISTApp
import pt.ulisboa.tecnico.sharist.data.model.User
import pt.ulisboa.tecnico.sharist.data.repository.UserRepository
import pt.ulisboa.tecnico.sharist.ui.MainActivity
import pt.ulisboa.tecnico.sharist.utils.SessionManager

class AuthViewModel(private val userRepo: UserRepository, private val session: SessionManager) : ViewModel() {
    sealed class State { object Idle: State(); object Loading: State(); object Success: State(); data class Error(val msg: String): State() }
    private val _state = MutableLiveData<State>(State.Idle)
    val state: LiveData<State> = _state
    fun signIn(email: String, password: String) {
        _state.value = State.Loading
        viewModelScope.launch {
            runCatching { userRepo.signIn(email, password) }
                .onSuccess { r ->
                    val uid = r?.user?.uid
                    if (uid == null) {
                        _state.value = State.Error("No user found")
                        return@onSuccess
                    }
                    val user = userRepo.getUser(uid)
                    if (user == null) {
                        _state.value = State.Error("Profile not found")
                        return@onSuccess
                    }
                    session.save(uid, if (user.isDriver) SessionManager.ROLE_DRIVER else SessionManager.ROLE_PASSENGER, user.displayName)
                    _state.value = State.Success
                }
                .onFailure { _state.value = State.Error(it.message ?: "Login failed") }
        }
    }

    fun register(email: String, password: String, name: String, isDriver: Boolean) {
        _state.value = State.Loading
        viewModelScope.launch {
            runCatching {
                val r = userRepo.register(email, password)
                val uid = r?.user?.uid ?: error("No UID returned from registration")
                userRepo.createProfile(User(uid = uid, displayName = name, email = email, isDriver = isDriver))
                uid
            }.onSuccess { uid ->
                session.save(uid, if (isDriver) SessionManager.ROLE_DRIVER else SessionManager.ROLE_PASSENGER, name)
                _state.value = State.Success
            }.onFailure {
                _state.value = State.Error(it.message ?: "Registration failed")
            }
        }
    }
}

class AuthActivity : AppCompatActivity() {
    private val vm: AuthViewModel by viewModels { object : ViewModelProvider.Factory { override fun <T : ViewModel> create(c: Class<T>): T { val app = application as SharISTApp; @Suppress("UNCHECKED_CAST") return AuthViewModel(app.userRepository, app.sessionManager) as T } } }
    private var isLoginMode = true
    private var selectedDriver = false
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); setContentView(R.layout.activity_auth)
        val tabLogin = findViewById<Button>(R.id.tab_login); val tabRegister = findViewById<Button>(R.id.tab_register); val etEmail = findViewById<EditText>(R.id.et_email); val etPassword = findViewById<EditText>(R.id.et_password); val registerGroup = findViewById<View>(R.id.layout_register_only); val etName = findViewById<EditText>(R.id.et_name); val btnPassenger = findViewById<Button>(R.id.btn_role_passenger); val btnDriver = findViewById<Button>(R.id.btn_role_driver); val tvError = findViewById<TextView>(R.id.tv_error); val progressBar = findViewById<ProgressBar>(R.id.progress_bar); val btnSubmit = findViewById<Button>(R.id.btn_submit)
        fun setRole(driver: Boolean) { selectedDriver = driver; btnPassenger.alpha = if (!driver) 1f else 0.4f; btnDriver.alpha = if (driver) 1f else 0.4f }
        fun switchMode(login: Boolean) { isLoginMode = login; registerGroup.visibility = if (login) View.GONE else View.VISIBLE; btnSubmit.text = if (login) "Sign In" else "Create Account"; tabLogin.alpha = if (login) 1f else 0.4f; tabRegister.alpha = if (!login) 1f else 0.4f; tvError.visibility = View.GONE }
        setRole(false); switchMode(true)
        btnPassenger.setOnClickListener { setRole(false) }; btnDriver.setOnClickListener { setRole(true) }; tabLogin.setOnClickListener { switchMode(true) }; tabRegister.setOnClickListener { switchMode(false) }
        btnSubmit.setOnClickListener { if (isLoginMode) vm.signIn(etEmail.text.toString().trim(), etPassword.text.toString()) else vm.register(etEmail.text.toString().trim(), etPassword.text.toString(), etName.text.toString().trim(), selectedDriver) }
        vm.state.observe(this) { state -> progressBar.visibility = if (state is AuthViewModel.State.Loading) View.VISIBLE else View.GONE; btnSubmit.isEnabled = state !is AuthViewModel.State.Loading; when (state) { is AuthViewModel.State.Success -> startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)); is AuthViewModel.State.Error -> { tvError.visibility = View.VISIBLE; tvError.text = state.msg }; else -> Unit } }
    }
}
