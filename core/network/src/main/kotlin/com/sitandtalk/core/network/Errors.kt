package com.sitandtalk.core.network

import com.sitandtalk.core.model.AppException
import io.github.jan.supabase.auth.exception.AuthRestException
import io.github.jan.supabase.auth.exception.AuthWeakPasswordException
import io.github.jan.supabase.exceptions.HttpRequestException
import io.github.jan.supabase.exceptions.RestException
import io.github.jan.supabase.postgrest.exception.PostgrestRestException
import io.ktor.client.plugins.HttpRequestTimeoutException
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException

/**
 * Converts any failure into an [AppException] with a stable code. Raw serializer / HTTP messages are
 * never shown to users; they only reach developer logs through [SafeLog].
 */
fun Throwable.toAppException(): AppException = when (this) {
    is AppException -> this
    is CancellationException -> throw this
    is HttpRequestTimeoutException -> AppException(AppException.TIMEOUT, cause = this)
    is HttpRequestException -> AppException(AppException.NETWORK, cause = this)
    is IOException -> AppException(AppException.NETWORK, cause = this)
    is AuthWeakPasswordException -> AppException("weak_password", cause = this)
    is AuthRestException -> AppException(authCode(this), cause = this)
    is PostgrestRestException -> postgrestError(this)
    is RestException -> restError(this)
    else -> AppException(AppException.UNKNOWN, cause = this)
}

private fun authCode(e: AuthRestException): String {
    val code = e.errorCode?.value ?: e.error
    return when {
        code == "invalid_credentials" || e.error == "invalid_grant" -> "invalid_credentials"
        code == "email_not_confirmed" -> "email_not_confirmed"
        code == "user_already_exists" || code == "email_exists" -> "email_exists"
        code == "over_email_send_rate_limit" || code == "over_request_rate_limit" -> AppException.RATE_LIMITED
        code == "same_password" -> "same_password"
        code == "weak_password" -> "weak_password"
        code == "user_banned" -> "user_banned"
        code == "session_not_found" || code == "refresh_token_not_found" || code == "refresh_token_already_used" ->
            AppException.SESSION_EXPIRED
        code == "flow_state_not_found" || code == "flow_state_expired" || code == "otp_expired" -> "link_expired"
        code == "signup_disabled" -> "signup_disabled"
        code == "validation_failed" -> "validation_failed"
        else -> "auth_failed"
    }
}

private fun postgrestError(e: PostgrestRestException): AppException {
    // RPCs raise app_private.err(code): SQLSTATE P0001 with a snake_case code as the message.
    val message = e.error
    return when {
        e.code == "P0001" && message == "rate_limited" -> {
            val retry = e.details?.let { runCatching { it.jsonPrimitive.intOrNull }.getOrNull() }
            AppException(AppException.RATE_LIMITED, retryAfterSeconds = retry, cause = e)
        }
        e.code == "P0001" && message.matches(Regex("[a-z_]{2,64}")) -> AppException(message, cause = e)
        e.code == "23505" -> AppException("duplicate", cause = e)
        e.code == "42501" -> AppException("forbidden", cause = e)
        e.statusCode == 401 -> AppException(AppException.UNAUTHORIZED, cause = e)
        else -> AppException(AppException.UNKNOWN, cause = e)
    }
}

private fun restError(e: RestException): AppException {
    // Edge Functions answer with {"code","message","request_id","retry_after"}.
    val body = runCatching { AppJson.parseToJsonElement(e.error).jsonObject }.getOrNull()
    val code = body?.get("code")?.jsonPrimitive?.content
    val retry = body?.get("retry_after")?.let { runCatching { it.jsonPrimitive.int }.getOrNull() }
    val requestId = body?.get("request_id")?.jsonPrimitive?.content
    return when {
        code != null && code.matches(Regex("[a-z_]{2,64}")) -> AppException(code, retry, requestId, e)
        e.statusCode == 401 -> AppException(AppException.UNAUTHORIZED, cause = e)
        e.statusCode == 404 -> AppException("function_unavailable", cause = e)
        e.statusCode == 429 -> AppException(AppException.RATE_LIMITED, retry, cause = e)
        e.statusCode >= 500 -> AppException("server_error", cause = e)
        else -> AppException(AppException.UNKNOWN, cause = e)
    }
}

/** Runs [block], converting failures into [AppException]. */
suspend inline fun <T> apiCall(crossinline block: suspend () -> T): T = try {
    block()
} catch (e: Throwable) {
    val mapped = e.toAppException()
    SafeLog.error("api", mapped.code, e)
    throw mapped
}
