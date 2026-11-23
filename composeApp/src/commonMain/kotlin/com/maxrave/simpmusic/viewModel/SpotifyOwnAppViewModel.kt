package com.maxrave.simpmusic.viewModel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.maxrave.domain.manager.DataStoreManager
import com.maxrave.domain.repository.SpotifyLibraryRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Logs in through the user's own Spotify developer app, which has its own rate-limit quota. */
class SpotifyOwnAppViewModel(
    private val repository: SpotifyLibraryRepository,
    dataStoreManager: DataStoreManager,
) : ViewModel() {
    val clientId: StateFlow<String> =
        dataStoreManager.spotifyOAuthClientId.stateIn(viewModelScope, SharingStarted.Eagerly, "")

    val loggedIn: StateFlow<Boolean> =
        repository.hasOwnAppSession().stateIn(viewModelScope, SharingStarted.Eagerly, false)

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    fun start(
        clientId: String,
        openUrl: (String) -> Unit,
    ) {
        viewModelScope.launch {
            repository
                .startOwnAppLogin(clientId)
                .onSuccess {
                    _message.value =
                        "Approve SimpMusic in the browser. If the app does not come back by itself, " +
                        "paste the link the page ends on below."
                    openUrl(it)
                }.onFailure { _message.value = it.message ?: "Could not start the login." }
        }
    }

    fun complete(callbackUrl: String) {
        if (callbackUrl.isBlank()) return
        viewModelScope.launch {
            repository
                .completeOwnAppLogin(callbackUrl)
                .onSuccess { _message.value = "Logged in with your own Spotify app." }
                .onFailure { _message.value = it.message ?: "Login failed." }
        }
    }

    fun signOut() {
        viewModelScope.launch {
            repository.signOutOwnApp()
            _message.value = "Logged out of your own Spotify app."
        }
    }

    fun clearMessage() {
        _message.value = null
    }
}
