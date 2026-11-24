package com.example.basededatos.network

import android.content.Context
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.exceptions.ClearCredentialException
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser

object GoogleAuthSession {

    fun isConfigured(context: Context): Boolean = FirebaseApp.getApps(context).isNotEmpty()

    fun currentUser(context: Context): FirebaseUser? =
        if (isConfigured(context)) FirebaseAuth.getInstance().currentUser else null

    fun webClientId(context: Context): String? {
        val resourceId = context.resources.getIdentifier(
            "default_web_client_id",
            "string",
            context.packageName
        )
        if (resourceId == 0) return null
        return context.getString(resourceId).takeIf { it.isNotBlank() }
    }

    suspend fun signOut(context: Context) {
        if (isConfigured(context)) FirebaseAuth.getInstance().signOut()
        try {
            CredentialManager.create(context).clearCredentialState(ClearCredentialStateRequest())
        } catch (_: ClearCredentialException) {
            // Firebase has already been signed out. Credential state can be cleared again next time.
        } catch (_: Exception) {
            // Credential providers may be unavailable on some devices; keep the Firebase session cleared.
        }
    }
}
