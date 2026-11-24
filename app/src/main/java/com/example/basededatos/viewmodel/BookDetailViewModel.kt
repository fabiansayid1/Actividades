package com.example.basededatos.viewmodel

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.basededatos.model.BookItem
import com.example.basededatos.network.BooksApi
import com.example.basededatos.network.UserStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import retrofit2.HttpException
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException

data class BookDetailUiState(
    val isLoading: Boolean = false,
    val book: BookItem? = null,
    val errorMessage: String? = null
)

class BookDetailViewModel(
    private val booksApi: BooksApi,
    private val googleBooksApiKey: String,
    private val userStore: UserStore
) : ViewModel() {

    private val _state = MutableLiveData(BookDetailUiState())
    val state: LiveData<BookDetailUiState> = _state

    fun loadBook(volumeId: String) {
        if (volumeId.isBlank()) {
            _state.value = BookDetailUiState(errorMessage = "No se encontró el identificador del libro.")
            return
        }
        viewModelScope.launch {
            _state.value = BookDetailUiState(isLoading = true)
            try {
                val book = withContext(Dispatchers.IO) {
                    booksApi.getBookDetails(
                        volumeId = volumeId,
                        apiKey = googleBooksApiKey.takeIf { it.isNotBlank() }
                    )
                }
                userStore.cacheApiBooks(listOf("detail" to listOf(book)))
                _state.value = BookDetailUiState(book = book)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                val cachedBook = userStore.getCachedApiBook(volumeId)
                val message = when (error) {
                    is HttpException -> "Google Books respondió HTTP ${error.code()}. Inténtalo de nuevo."
                    is UnknownHostException -> "No se encontró el servidor de Google Books. Revisa tu conexión." 
                    is SocketTimeoutException -> "Google Books tardó demasiado en responder."
                    is SSLHandshakeException -> "No se pudo establecer una conexión segura con Google Books."
                    is IOException -> "La conexión con Google Books se interrumpió."
                    else -> "No se pudo cargar la información de este libro."
                }
                _state.value = BookDetailUiState(
                    book = cachedBook,
                    errorMessage = if (cachedBook != null) {
                        "Sin conexión con Google Books. Se muestra la ficha guardada."
                    } else message
                )
            }
        }
    }
}
