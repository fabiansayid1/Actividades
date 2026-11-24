package com.example.basededatos.view

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.example.basededatos.databinding.ActivityMainBinding
import com.example.basededatos.network.AuthSessionStore
import com.example.basededatos.network.GoogleAuthSession
import com.example.basededatos.network.UserProfile
import com.example.basededatos.viewmodel.AppViewModelFactory
import com.example.basededatos.viewmodel.AuthViewModel
import com.google.android.material.snackbar.Snackbar
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenParsingException
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var authViewModel: AuthViewModel
    private lateinit var authSessionStore: AuthSessionStore
    private var googleCredentialRequestActive = false
    private var restoringSession = false

    private val registerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val email = result.data?.getStringExtra(ProfileExtras.EMAIL).orEmpty()
            if (email.isNotBlank()) {
                binding.etCorreo.setText(email)
                binding.etPassword.text?.clear()
                Snackbar.make(
                    binding.root,
                    "Cuenta creada. Inicia sesión con tu correo y contraseña.",
                    Snackbar.LENGTH_LONG
                ).show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        authSessionStore = AuthSessionStore(application)
        authViewModel = ViewModelProvider(
            this,
            AppViewModelFactory(application)
        )[AuthViewModel::class.java]

        authViewModel.state.observe(this) { state ->
            renderLoading(state.isLoading)
            state.errorMessage?.let {
                if (restoringSession) authSessionStore.clear()
                restoringSession = false
                Snackbar.make(binding.root, it, Snackbar.LENGTH_LONG).show()
            }
            state.profile?.let(::openHome)
        }

        binding.btnIngresar.setOnClickListener {
            val email = binding.etCorreo.text?.toString()?.trim().orEmpty()
            val password = binding.etPassword.text?.toString().orEmpty()
            if (email.isBlank() || password.isBlank()) {
                Snackbar.make(binding.root, "Escribe tu correo y contraseña.", Snackbar.LENGTH_LONG).show()
            } else {
                authViewModel.login(email, password)
            }
        }
        binding.tvCrearCuenta.setOnClickListener {
            registerLauncher.launch(Intent(this, RegisterActivity::class.java))
        }
        binding.btnGoogle.setOnClickListener { startGoogleSignIn() }
        restoreSession()
    }

    private fun restoreSession() {
        val savedSession = authSessionStore.read()
        val firebaseUser = GoogleAuthSession.currentUser(this)
        if (savedSession != null && savedSession.provider == "google") {
            val matchingFirebaseUser = firebaseUser?.takeIf {
                it.email.equals(savedSession.email, ignoreCase = true) &&
                    (savedSession.uid.isNullOrBlank() || savedSession.uid == it.uid)
            }
            if (matchingFirebaseUser != null) {
                restoringSession = true
                authViewModel.loginWithFirebaseUser(matchingFirebaseUser)
            } else {
                authSessionStore.clear()
                firebaseUser?.let {
                    restoringSession = true
                    authViewModel.loginWithFirebaseUser(it)
                }
            }
        } else if (savedSession != null) {
            restoringSession = true
            authViewModel.restoreLocalSession(savedSession.email)
        } else {
            firebaseUser?.let {
                restoringSession = true
                authViewModel.loginWithFirebaseUser(it)
            }
        }
    }

    private fun startGoogleSignIn() {
        if (!GoogleAuthSession.isConfigured(this)) {
            Snackbar.make(
                binding.root,
                "Firebase aún no está conectado. Copia google-services.json dentro de app/ y sincroniza Gradle.",
                Snackbar.LENGTH_LONG
            ).show()
            return
        }

        val serverClientId = GoogleAuthSession.webClientId(this)
        if (serverClientId.isNullOrBlank()) {
            Snackbar.make(
                binding.root,
                "Firebase no encontró el cliente web. Habilita Google en Authentication y vuelve a descargar google-services.json.",
                Snackbar.LENGTH_LONG
            ).show()
            return
        }

        val option = GetSignInWithGoogleOption.Builder(serverClientId).build()
        val request = GetCredentialRequest.Builder()
            .addCredentialOption(option)
            .build()
        val credentialManager = CredentialManager.create(this)
        googleCredentialRequestActive = true
        renderLoading(authViewModel.state.value?.isLoading == true)

        lifecycleScope.launch {
            try {
                val result = credentialManager.getCredential(
                    context = this@MainActivity,
                    request = request
                )
                val credential = result.credential
                if (credential is CustomCredential &&
                    credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
                ) {
                    val googleCredential = GoogleIdTokenCredential.createFrom(credential.data)
                    authViewModel.loginWithGoogleToken(googleCredential.idToken)
                } else {
                    Snackbar.make(binding.root, "Google devolvió un tipo de credencial no compatible.", Snackbar.LENGTH_LONG).show()
                }
            } catch (_: GoogleIdTokenParsingException) {
                Snackbar.make(binding.root, "No se pudo leer la credencial de Google. Inténtalo otra vez.", Snackbar.LENGTH_LONG).show()
            } catch (_: GetCredentialCancellationException) {
                // The user closed the Google account picker.
            } catch (_: GetCredentialException) {
                Snackbar.make(binding.root, "No se pudo abrir el inicio de sesión de Google. Revisa Play Services e inténtalo de nuevo.", Snackbar.LENGTH_LONG).show()
            } finally {
                googleCredentialRequestActive = false
                renderLoading(authViewModel.state.value?.isLoading == true)
            }
        }
    }

    private fun renderLoading(viewModelLoading: Boolean) {
        val isLoading = viewModelLoading || googleCredentialRequestActive
        binding.progressLogin.visibility = if (isLoading) View.VISIBLE else View.GONE
        binding.btnIngresar.isEnabled = !isLoading
        binding.btnGoogle.isEnabled = !isLoading
    }

    private fun openHome(profile: UserProfile) {
        authSessionStore.save(profile)
        startActivity(Intent(this, HomeActivity::class.java).putProfile(profile))
        finish()
    }
}

fun Intent.putProfile(profile: UserProfile): Intent = apply {
    putExtra(ProfileExtras.NAME, profile.name)
    putExtra(ProfileExtras.UNIVERSITY, profile.university)
    putExtra(ProfileExtras.EMAIL, profile.email)
    putStringArrayListExtra(ProfileExtras.SUBJECTS, ArrayList(profile.subjects))
    putExtra(ProfileExtras.AUTH_PROVIDER, profile.authProvider)
    putExtra(ProfileExtras.AUTH_UID, profile.authUid)
}
