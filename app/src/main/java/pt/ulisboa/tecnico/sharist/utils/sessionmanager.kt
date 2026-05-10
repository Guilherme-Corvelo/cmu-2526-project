package pt.ulisboa.tecnico.sharist.utils

import android.content.Context

class SessionManager(context: Context) {
    private val prefs = context.getSharedPreferences("sharist_session", Context.MODE_PRIVATE)

    companion object {
        const val ROLE_DRIVER = "DRIVER"
        const val ROLE_PASSENGER = "PASSENGER"
    }

    var uid: String?
        get() = prefs.getString("uid", null)
        set(v) = prefs.edit().putString("uid", v).apply()
    var role: String?
        get() = prefs.getString("role", null)
        set(v) = prefs.edit().putString("role", v).apply()
    var displayName: String?
        get() = prefs.getString("name", null)
        set(v) = prefs.edit().putString("name", v).apply()

    val isDriver get() = role == ROLE_DRIVER
    val isPassenger get() = role == ROLE_PASSENGER
    val isLoggedIn get() = uid != null && role != null
    var forceDemoMode: Boolean
        get() = prefs.getBoolean("force_demo_mode", false)
        set(v) = prefs.edit().putBoolean("force_demo_mode", v).apply()

    fun save(uid: String, role: String, name: String) { this.uid = uid; this.role = role; this.displayName = name }
    fun clear() = prefs.edit().clear().apply()
}
