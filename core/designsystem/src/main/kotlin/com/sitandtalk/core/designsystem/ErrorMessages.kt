package com.sitandtalk.core.designsystem

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.sitandtalk.core.model.AppException

/** Maps a stable error code to user-facing text. Raw exception messages are never shown. */
@Composable
fun errorMessage(error: AppException): String {
    val base = stringResource(errorRes(error.code))
    val retry = error.retryAfterSeconds
    return if (error.code == AppException.RATE_LIMITED && retry != null && retry > 0) {
        stringResource(R.string.ds_retry_in_seconds, base, retry)
    } else {
        base
    }
}

fun errorRes(code: String): Int = when (code) {
    "network" -> R.string.err_network
    "timeout" -> R.string.err_timeout
    "unauthorized" -> R.string.err_unauthorized
    "session_expired" -> R.string.err_session_expired
    "not_authenticated" -> R.string.err_not_authenticated
    "service_not_configured" -> R.string.err_service_not_configured
    "server_error" -> R.string.err_server_error
    "function_unavailable" -> R.string.err_function_unavailable
    "unknown" -> R.string.err_unknown
    "forbidden" -> R.string.err_forbidden
    "not_found" -> R.string.err_not_found
    "rate_limited" -> R.string.err_rate_limited
    "duplicate" -> R.string.err_duplicate
    "invalid_request" -> R.string.err_invalid_request
    "conflict" -> R.string.err_conflict
    "invalid_credentials" -> R.string.err_invalid_credentials
    "email_not_confirmed" -> R.string.err_email_not_confirmed
    "email_exists" -> R.string.err_email_exists
    "weak_password" -> R.string.err_weak_password
    "same_password" -> R.string.err_same_password
    "user_banned" -> R.string.err_user_banned
    "link_expired" -> R.string.err_link_expired
    "signup_disabled" -> R.string.err_signup_disabled
    "validation_failed" -> R.string.err_validation_failed
    "auth_failed" -> R.string.err_auth_failed
    "reauthentication_required" -> R.string.err_reauthentication_required
    "already_onboarded" -> R.string.err_already_onboarded
    "invalid_username" -> R.string.err_invalid_username
    "invalid_display_name" -> R.string.err_invalid_display_name
    "invalid_birth_date" -> R.string.err_invalid_birth_date
    "underage" -> R.string.err_underage
    "languages_required" -> R.string.err_languages_required
    "too_many_interests" -> R.string.err_too_many_interests
    "consent_required" -> R.string.err_consent_required
    "username_taken" -> R.string.err_username_taken
    "username_change_cooldown" -> R.string.err_username_change_cooldown
    "profile_incomplete" -> R.string.err_profile_incomplete
    "account_restricted" -> R.string.err_account_restricted
    "invalid_target" -> R.string.err_invalid_target
    "already_friends" -> R.string.err_already_friends
    "feature_disabled" -> R.string.err_feature_disabled
    "already_in_call" -> R.string.err_already_in_call
    "in_room" -> R.string.err_in_room
    "invalid_alias" -> R.string.err_invalid_alias
    "match_expired" -> R.string.err_match_expired
    "call_not_extendable" -> R.string.err_call_not_extendable
    "call_ended" -> R.string.err_call_ended
    "invalid_state" -> R.string.err_invalid_state
    "invalid_mode" -> R.string.err_invalid_mode
    "call_not_allowed" -> R.string.err_call_not_allowed
    "peer_busy" -> R.string.err_peer_busy
    "room_closed" -> R.string.err_room_closed
    "room_banned" -> R.string.err_room_banned
    "wrong_room_password" -> R.string.err_wrong_room_password
    "invite_required" -> R.string.err_invite_required
    "room_full" -> R.string.err_room_full
    "not_room_member" -> R.string.err_not_room_member
    "cannot_unmute" -> R.string.err_cannot_unmute
    "speakers_full" -> R.string.err_speakers_full
    "invalid_room_password" -> R.string.err_invalid_room_password
    "premium_required" -> R.string.err_premium_required
    "invalid_event_time" -> R.string.err_invalid_event_time
    "invalid_reaction" -> R.string.err_invalid_reaction
    "messaging_not_allowed" -> R.string.err_messaging_not_allowed
    "invalid_group_size" -> R.string.err_invalid_group_size
    "edit_window_passed" -> R.string.err_edit_window_passed
    "unsend_window_passed" -> R.string.err_unsend_window_passed
    "invalid_message" -> R.string.err_invalid_message
    "invalid_media" -> R.string.err_invalid_media
    "invalid_media_path" -> R.string.err_invalid_media_path
    "insufficient_balance" -> R.string.err_insufficient_balance
    "unknown_product" -> R.string.err_unknown_product
    "purchase_owned_by_other_account" -> R.string.err_purchase_owned_by_other_account
    "invalid_purchase" -> R.string.err_invalid_purchase
    "product_mismatch" -> R.string.err_product_mismatch
    "play_api_failed" -> R.string.err_play_api_failed
    "billing_unavailable" -> R.string.err_billing_unavailable
    "reason_required" -> R.string.err_reason_required
    "appeal_exists" -> R.string.err_appeal_exists
    "deletion_failed" -> R.string.err_deletion_failed
    "rtc_failed" -> R.string.err_rtc_failed
    "mic_permission" -> R.string.err_mic_permission
    "camera_permission" -> R.string.err_camera_permission
    "token_generation_failed" -> R.string.err_token_generation_failed
    "agora_rest_not_configured" -> R.string.err_agora_rest_not_configured
    "upload_failed" -> R.string.err_upload_failed
    else -> R.string.err_unknown
}
