package com.faldo.hsk_quest.util

/**
 * Wrapper describing the state of an async operation for the UI layer.
 */
sealed class Resource<out T> {
    data object Loading : Resource<Nothing>()
    data class Success<T>(val data: T) : Resource<T>()
    data class Error(val message: String) : Resource<Nothing>()
}
