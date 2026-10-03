package com.faldo.hsk_quest.util

import androidx.fragment.app.Fragment
import com.faldo.hsk_quest.AppContainer
import com.faldo.hsk_quest.HskQuestApp
import com.faldo.hsk_quest.R
import com.faldo.hsk_quest.data.repository.AuthRepository

/** Shortcut to the app-wide dependency container from any Fragment. */
val Fragment.appContainer: AppContainer
    get() = (requireActivity().application as HskQuestApp).container

/** Converts repository error sentinels into user-facing strings. */
fun Fragment.readableError(message: String): String = when (message) {
    AuthRepository.NETWORK_ERROR -> getString(R.string.err_network)
    "" -> getString(R.string.err_unknown)
    else -> message
}
