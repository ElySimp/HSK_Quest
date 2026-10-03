package com.faldo.hsk_quest.ui.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.faldo.hsk_quest.data.repository.AuthRepository
import com.faldo.hsk_quest.util.Constants
import com.faldo.hsk_quest.util.Resource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Shared logic for Login and Register screens.
 * Validation mirrors the backend RegisterRequest constraints.
 */
class AuthViewModel(private val repository: AuthRepository) : ViewModel() {

    /** Client-side validation failures, resolved to strings by the Fragment. */
    enum class ValidationError { EMPTY, USERNAME, PASSWORD }

    sealed class UiState {
        data object Idle : UiState()
        data object Loading : UiState()
        data object Success : UiState()
        data class Invalid(val error: ValidationError) : UiState()
        data class Failed(val message: String) : UiState()
    }

    private val _state = MutableStateFlow<UiState>(UiState.Idle)
    val state: StateFlow<UiState> = _state.asStateFlow()

    fun login(username: String, password: String) {
        val u = username.trim()
        if (u.isEmpty() || password.isEmpty()) {
            _state.value = UiState.Invalid(ValidationError.EMPTY)
            return
        }
        execute { repository.login(u, password) }
    }

    fun register(username: String, password: String, hskTarget: Int) {
        val u = username.trim()
        val error = when {
            u.isEmpty() || password.isEmpty() -> ValidationError.EMPTY
            !Constants.USERNAME_REGEX.matches(u) -> ValidationError.USERNAME
            password.length < Constants.PASSWORD_MIN_LENGTH -> ValidationError.PASSWORD
            else -> null
        }
        if (error != null) {
            _state.value = UiState.Invalid(error)
            return
        }
        execute { repository.register(u, password, hskTarget) }
    }

    /** Resets a terminal state after the UI has consumed it. */
    fun consumed() {
        _state.value = UiState.Idle
    }

    private fun execute(call: suspend () -> Resource<Unit>) {
        if (_state.value == UiState.Loading) return
        _state.value = UiState.Loading
        viewModelScope.launch {
            _state.value = when (val result = call()) {
                is Resource.Success -> UiState.Success
                is Resource.Error -> UiState.Failed(result.message)
                Resource.Loading -> UiState.Loading
            }
        }
    }

    companion object {
        fun factory(repository: AuthRepository): ViewModelProvider.Factory = viewModelFactory {
            initializer { AuthViewModel(repository) }
        }
    }
}
