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
import pt.ulisboa.tecnico.sharist.data.model.User
import pt.ulisboa.tecnico.sharist.data.repository.UserRepository
import pt.ulisboa.tecnico.sharist.MainActivity

// ─── ViewModel ────────────────────────────────────────────────────────────────

class AuthViewModel(private val userRepo: UserRepository) : ViewModel() {

    sealed class AuthState {
        object Idle : AuthState()
        object Loading : AuthState()
        data class Success(val uid: String) : AuthState()
        data class Error(val message: String) : AuthState()
    }

    private val _state = MutableLiveData<AuthState>(AuthState.Idle)
    val state: LiveData<AuthState> = _state

    fun signIn(email: String, password: String, loginAsDriver: Boolean) {
        _state.value = AuthState.Loading
        viewModelScope.launch {
            runCatching { userRepo.signIn(email, password) }
                .onSuccess { result ->
                    val uid = result?.user?.uid ?: userRepo.currentUid
                    if (uid != null) {
                        val user = userRepo.getUser(uid)
                        if (user == null) {
                            _state.value = AuthState.Error("User profile not found")
                        } else if (user.isDriver != loginAsDriver) {
                            val expectedRole = if (loginAsDriver) "driver" else "client"
                            _state.value = AuthState.Error("This account is not registered as $expectedRole")
                        } else {
                            _state.value = AuthState.Success(uid)
                        }
                    } else {
                        _state.value = AuthState.Error("Login failed: Invalid credentials")
                    }
                }
                .onFailure { e ->
                    _state.value = AuthState.Error(e.message ?: "Login failed")
                }
        }
    }

    fun register(email: String, password: String, displayName: String, isDriver: Boolean) {
        if (email.isBlank() || password.length < 6 || displayName.isBlank()) {
            _state.value = AuthState.Error("Please fill all fields (password ≥ 6 chars)")
            return
        }
        _state.value = AuthState.Loading
        viewModelScope.launch {
            runCatching {
                val result = userRepo.register(email, password)
                val uid = result?.user?.uid ?: userRepo.currentUid ?: error("Registration failed")
                userRepo.createProfile(
                    User(uid = uid, displayName = displayName,
                         email = email, isDriver = isDriver)
                )
                uid
            }
                .onSuccess { uid -> _state.value = AuthState.Success(uid) }
                .onFailure { e -> _state.value = AuthState.Error(e.message ?: "Registration failed") }
        }
    }
}

// ─── Activity ─────────────────────────────────────────────────────────────────

class AuthActivity : AppCompatActivity() {

    private val viewModel: AuthViewModel by viewModels {
        object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                @Suppress("UNCHECKED_CAST")
                return AuthViewModel(
                    (application as pt.ulisboa.tecnico.sharist.SharISTApp).userRepository
                ) as T
            }
        }
    }

    // Views – would normally be in layout XML; shown here for clarity
    private lateinit var tabLogin: Button
    private lateinit var tabRegister: Button
    private lateinit var etEmail: EditText
    private lateinit var etPassword: EditText
    private lateinit var etName: EditText
    private lateinit var switchDriver: Switch
    private lateinit var radioLoginRole: RadioGroup
    private lateinit var radioLoginDriver: RadioButton
    private lateinit var btnSubmit: Button
    private lateinit var tvError: TextView
    private lateinit var progressBar: ProgressBar

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_auth)

        bindViews()
        observeState()
        setupTabs()

        btnSubmit.setOnClickListener { onSubmit() }
    }

    private fun bindViews() {
        tabLogin    = findViewById(R.id.tab_login)
        tabRegister = findViewById(R.id.tab_register)
        etEmail     = findViewById(R.id.et_email)
        etPassword  = findViewById(R.id.et_password)
        etName      = findViewById(R.id.et_name)
        switchDriver = findViewById(R.id.switch_driver)
        radioLoginRole = findViewById(R.id.radio_login_role)
        radioLoginDriver = findViewById(R.id.radio_login_driver)
        btnSubmit   = findViewById(R.id.btn_submit)
        tvError     = findViewById(R.id.tv_error)
        progressBar = findViewById(R.id.progress_bar)
    }

    private var isLoginMode = true

    private fun setupTabs() {
        val updateTabs = {
            if (isLoginMode) {
                tabLogin.alpha = 1.0f
                tabRegister.alpha = 0.5f
                etName.visibility = View.GONE
                switchDriver.visibility = View.GONE
                radioLoginRole.visibility = View.VISIBLE
                btnSubmit.text = "Sign in"
            } else {
                tabLogin.alpha = 0.5f
                tabRegister.alpha = 1.0f
                etName.visibility = View.VISIBLE
                switchDriver.visibility = View.VISIBLE
                radioLoginRole.visibility = View.GONE
                btnSubmit.text = "Create account"
            }
            tvError.visibility = View.GONE
        }

        tabLogin.setOnClickListener {
            isLoginMode = true
            updateTabs()
        }
        tabRegister.setOnClickListener {
            isLoginMode = false
            updateTabs()
        }
        updateTabs()
    }

    private fun onSubmit() {
        val email    = etEmail.text.toString().trim()
        val password = etPassword.text.toString()
        if (isLoginMode) {
            val loginAsDriver = radioLoginRole.checkedRadioButtonId == R.id.radio_login_driver
            viewModel.signIn(email, password, loginAsDriver)
        } else {
            viewModel.register(
                email      = email,
                password   = password,
                displayName = etName.text.toString().trim(),
                isDriver   = switchDriver.isChecked
            )
        }
    }

    private fun observeState() {
        viewModel.state.observe(this) { state ->
            progressBar.visibility = if (state is AuthViewModel.AuthState.Loading) View.VISIBLE else View.GONE
            btnSubmit.isEnabled    = state !is AuthViewModel.AuthState.Loading

            when (state) {
                is AuthViewModel.AuthState.Success -> goToMain()
                is AuthViewModel.AuthState.Error   -> {
                    tvError.visibility = View.VISIBLE
                    tvError.text = state.message
                }
                else -> tvError.visibility = View.GONE
            }
        }
    }

    private fun goToMain() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}
