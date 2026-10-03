package com.faldo.hsk_quest.ui.auth

import android.view.View
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.faldo.hsk_quest.R
import com.faldo.hsk_quest.util.readableError

/**
 * Shared rendering for the Login/Register UI states.
 */
internal fun Fragment.renderAuthState(
    state: AuthViewModel.UiState,
    button: View,
    progress: View,
    errorView: TextView,
    inputs: List<View>,
) {
    val loading = state is AuthViewModel.UiState.Loading
    button.isEnabled = !loading
    button.alpha = if (loading) 0.6f else 1f
    progress.visibility = if (loading) View.VISIBLE else View.GONE
    inputs.forEach { it.isEnabled = !loading }

    val message = when (state) {
        is AuthViewModel.UiState.Invalid -> when (state.error) {
            AuthViewModel.ValidationError.EMPTY -> getString(R.string.auth_err_empty)
            AuthViewModel.ValidationError.USERNAME -> getString(R.string.auth_err_username)
            AuthViewModel.ValidationError.PASSWORD -> getString(R.string.auth_err_password)
        }
        is AuthViewModel.UiState.Failed -> readableError(state.message)
        else -> null
    }
    errorView.text = message
    errorView.visibility = if (message != null) View.VISIBLE else View.GONE
    if (message != null) {
        // Small horizontal shake to draw attention to the error.
        errorView.translationX = 0f
        errorView.animate().translationX(12f).setDuration(50).withEndAction {
            errorView.animate().translationX(-12f).setDuration(50).withEndAction {
                errorView.animate().translationX(0f).setDuration(50).start()
            }.start()
        }.start()
    }
}
