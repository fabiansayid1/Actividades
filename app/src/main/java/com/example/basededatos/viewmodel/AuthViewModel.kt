package com.example.basededatos.viewmodel

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.basededatos.network.UserProfile
import com.example.basededatos.network.UserStore
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

data class AuthUiState(
    val isLoading: Boolean = false,
    val profile: UserProfile? = null,
    val registeredEmail: String? = null,
    val errorMessage: String? = null
)

class AuthViewModel(
    private val userStore: UserStore
) : ViewModel() {

    private val _state = MutableLiveData(AuthUiState())
    val state: LiveData<AuthUiState> = _state

    fun login(email: String, password: String) {
        viewModelScope.launch {
            _state.value = AuthUiState(isLoading = true)
            try {
                val profile = userStore.login(email, password)
                _state.value = if (profile == null) {
                    AuthUiState(errorMessage = "Correo o contraseña incorrectos.")
                } else {
                    AuthUiState(profile = profile)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _state.value = AuthUiState(errorMessage = "No se pudo iniciar sesión. Inténtalo de nuevo.")
            }
        }
    }

    fun restoreLocalSession(email: String) {
        viewModelScope.launch {
            _state.value = AuthUiState(isLoading = true)
            try {
                val profile = userStore.getProfile(email)
                _state.value = if (profile == null) {
                    AuthUiState(errorMessage = "La cuenta guardada ya no existe. Inicia sesión de nuevo.")
                } else {
                    AuthUiState(profile = profile)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _state.value = AuthUiState(errorMessage = "No se pudo recuperar la sesión guardada.")
            }
        }
    }

    fun register(profile: UserProfile, password: String) {
        viewModelScope.launch {
            _state.value = AuthUiState(isLoading = true)
            try {
                val saved = userStore.register(profile, password)
                _state.value = if (saved) {
                    AuthUiState(registeredEmail = profile.email)
                } else {
                    AuthUiState(errorMessage = "Ese correo ya está registrado.")
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _state.value = AuthUiState(errorMessage = "No se pudo crear la cuenta. Inténtalo de nuevo.")
            }
        }
    }

    fun loginWithGoogleToken(idToken: String) {
        viewModelScope.launch {
            _state.value = AuthUiState(isLoading = true)
            try {
                val credential = GoogleAuthProvider.getCredential(idToken, null)
                val firebaseUser = FirebaseAuth.getInstance()
                    .signInWithCredential(credential)
                    .await()
                    .user
                    ?: throw IllegalStateException("Firebase no devolvió una cuenta de usuario.")
                loadGoogleProfile(firebaseUser)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _state.value = AuthUiState(
                    errorMessage = "No se pudo validar la cuenta con Firebase. Revisa la configuración e inténtalo de nuevo."
                )
            }
        }
    }

    fun loginWithFirebaseUser(firebaseUser: FirebaseUser) {
        viewModelScope.launch {
            _state.value = AuthUiState(isLoading = true)
            try {
                loadGoogleProfile(firebaseUser)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _state.value = AuthUiState(
                    errorMessage = "No se pudo abrir tu perfil de Google. Cierra sesión e inténtalo de nuevo."
                )
            }
        }
    }

    private suspend fun loadGoogleProfile(firebaseUser: FirebaseUser) {
        val email = firebaseUser.email?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("La cuenta de Google no tiene un correo electrónico.")
        val profile = userStore.saveGoogleProfile(
            name = firebaseUser.displayName.orEmpty(),
            email = email,
            googleUid = firebaseUser.uid
        )
        _state.value = AuthUiState(profile = profile)
    }
}
