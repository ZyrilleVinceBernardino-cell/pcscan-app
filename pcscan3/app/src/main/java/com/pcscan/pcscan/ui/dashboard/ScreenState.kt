package com.pcscan.pcscan.ui.dashboard

sealed interface ScreenState<out T> {
    data object Idle : ScreenState<Nothing>
    data object Loading : ScreenState<Nothing>
    data class Success<T>(val data: T) : ScreenState<T>
    data class Error(val message: String) : ScreenState<Nothing>
}
