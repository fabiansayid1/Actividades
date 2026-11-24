package com.example.basededatos.network

import android.content.Context

data class SavedAuthSession(
    val email: String,
    val provider: String,
    val uid: String?
)

class AuthSessionStore(context: Context) {

    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE
    )

    fun save(profile: UserProfile) {
        preferences.edit()
            .putString(KEY_EMAIL, profile.email.trim().lowercase())
            .putString(KEY_PROVIDER, profile.authProvider)
            .putString(KEY_UID, profile.authUid)
            .apply()
    }

    fun read(): SavedAuthSession? {
        val email = preferences.getString(KEY_EMAIL, null)?.takeIf { it.isNotBlank() }
            ?: return null
        return SavedAuthSession(
            email = email,
            provider = preferences.getString(KEY_PROVIDER, "password") ?: "password",
            uid = preferences.getString(KEY_UID, null)
        )
    }

    fun clear() {
        preferences.edit().clear().apply()
    }

    private companion object {
        const val PREFERENCES_NAME = "study_pulse_auth_session"
        const val KEY_EMAIL = "email"
        const val KEY_PROVIDER = "provider"
        const val KEY_UID = "uid"
    }
}
