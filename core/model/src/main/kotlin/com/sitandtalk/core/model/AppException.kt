package com.sitandtalk.core.model

/**
 * A failure with a stable, machine-readable [code]. Server errors raised by RPCs and Edge Functions
 * carry their code through unchanged; the UI maps codes to localized messages.
 */
class AppException(
    val code: String,
    val retryAfterSeconds: Int? = null,
    val requestId: String? = null,
    cause: Throwable? = null,
) : Exception(code, cause) {
    companion object {
        const val NETWORK = "network"
        const val TIMEOUT = "timeout"
        const val UNAUTHORIZED = "unauthorized"
        const val SESSION_EXPIRED = "session_expired"
        const val NOT_CONFIGURED = "service_not_configured"
        const val UNKNOWN = "unknown"
        const val RATE_LIMITED = "rate_limited"
    }
}

/** Screen-level loading state shared by every data screen. */
sealed interface LoadState<out T> {
    data object Loading : LoadState<Nothing>
    data class Success<T>(val data: T, val fromCache: Boolean = false) : LoadState<T>
    data class Failure(val error: AppException) : LoadState<Nothing>
}
