package com.sitandtalk.core.data

import com.sitandtalk.core.model.AdminAppeal
import com.sitandtalk.core.model.AdminDashboard
import com.sitandtalk.core.model.AdminReport
import com.sitandtalk.core.model.AdminRoom
import com.sitandtalk.core.model.AppSetting
import com.sitandtalk.core.model.AuditLogEntry
import com.sitandtalk.core.model.FeatureFlag
import com.sitandtalk.core.model.MyReport
import com.sitandtalk.core.model.ReportReason
import com.sitandtalk.core.model.ReportTarget
import com.sitandtalk.core.model.Restriction
import com.sitandtalk.core.network.Api
import com.sitandtalk.core.network.apiCall
import com.sitandtalk.core.network.putNullable
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.Order
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

private val ReportTarget.wire: String
    get() = when (this) {
        ReportTarget.User -> "user"
        ReportTarget.Message -> "message"
        ReportTarget.Post -> "post"
        ReportTarget.Comment -> "comment"
        ReportTarget.Story -> "story"
        ReportTarget.Room -> "room"
        ReportTarget.RoomMessage -> "room_message"
        ReportTarget.Call -> "call"
    }

@Singleton
class ModerationRepository @Inject constructor(private val api: Api) {
    suspend fun report(target: ReportTarget, targetId: String, reason: ReportReason, details: String): String =
        api.rpc("create_report") {
            put("p_target_type", target.wire)
            put("p_target_id", targetId)
            put("p_reason", reason.wire)
            put("p_details", details.trim())
        }

    suspend fun myReports(): List<MyReport> = api.rpc("my_reports")
    suspend fun myRestrictions(): List<Restriction> = api.rpc("my_restrictions")

    suspend fun appeal(restrictionId: String, body: String): String = api.rpc("create_appeal") {
        put("p_restriction", restrictionId)
        put("p_body", body.trim())
    }
}

/** Staff API. Every call is re-authorized and audited on the server; the UI hiding is only cosmetic. */
@Singleton
class AdminRepository @Inject constructor(private val api: Api) {
    suspend fun dashboard(): AdminDashboard = api.rpc("admin_dashboard")

    suspend fun reports(status: String = "open"): List<AdminReport> = api.rpc("admin_list_reports") {
        put("p_status", status)
        put("p_limit", 100)
    }

    suspend fun resolveReport(reportId: String, dismiss: Boolean, note: String) = api.rpcUnit("admin_resolve_report") {
        put("p_report", reportId)
        put("p_dismiss", dismiss)
        put("p_note", note)
    }

    suspend fun restrict(userId: String, kind: String, hours: Int?, reason: String, reportId: String?) = api.rpcUnit("admin_restrict_user") {
        put("p_user", userId)
        put("p_kind", kind)
        if (hours != null) put("p_hours", hours) else put("p_hours", 0)
        put("p_reason", reason)
        putNullable("p_report", reportId)
    }

    suspend fun liftRestriction(restrictionId: String, reason: String) = api.rpcUnit("admin_lift_restriction") {
        put("p_restriction", restrictionId)
        put("p_reason", reason)
    }

    suspend fun removeContent(type: ReportTarget, id: String, reason: String, reportId: String?) = api.rpcUnit("admin_remove_content") {
        put("p_type", type.wire)
        put("p_id", id)
        put("p_reason", reason)
        putNullable("p_report", reportId)
    }

    suspend fun appeals(): List<AdminAppeal> = api.rpc("admin_list_appeals")

    suspend fun decideAppeal(appealId: String, overturn: Boolean, note: String) = api.rpcUnit("admin_decide_appeal") {
        put("p_appeal", appealId)
        put("p_overturn", overturn)
        put("p_note", note)
    }

    suspend fun rooms(): List<AdminRoom> = api.rpc("admin_list_rooms")
    suspend fun userSummary(userId: String): JsonObject = api.rpc("admin_user_summary") { put("p_user", userId) }

    suspend fun flags(): List<FeatureFlag> = apiCall {
        api.client.from("feature_flags").select(Columns.list("key", "enabled", "description")) { order("key", Order.ASCENDING) }
            .decodeList<FeatureFlag>()
    }

    suspend fun setFlag(key: String, enabled: Boolean, reason: String) = api.rpcUnit("admin_set_flag") {
        put("p_key", key)
        put("p_enabled", enabled)
        put("p_reason", reason)
    }

    suspend fun settings(): List<AppSetting> = apiCall {
        api.client.from("app_settings").select(Columns.list("key", "value", "description")) { order("key", Order.ASCENDING) }
            .decodeList<AppSetting>()
    }

    suspend fun setSetting(key: String, value: JsonElement, reason: String) = api.rpcUnit("admin_set_setting") {
        put("p_key", key)
        put("p_value", value)
        put("p_reason", reason)
    }

    suspend fun publishAnnouncement(titleTr: String, bodyTr: String, titleEn: String, bodyEn: String, active: Boolean) =
        api.rpcUnit("admin_publish_announcement") {
            put("p_title_tr", titleTr)
            put("p_body_tr", bodyTr)
            put("p_title_en", titleEn)
            put("p_body_en", bodyEn)
            put("p_active", active)
        }

    suspend fun auditLog(): List<AuditLogEntry> = apiCall {
        api.client.from("audit_logs").select(
            Columns.list("id", "actor_id", "action", "target_type", "target_id", "reason", "result", "created_at"),
        ) {
            order("created_at", Order.DESCENDING)
            limit(100)
        }.decodeList<AuditLogEntry>()
    }

    suspend fun upsertInterest(slug: String, nameTr: String, nameEn: String, category: String, active: Boolean, sort: Int) =
        api.rpcUnit("admin_upsert_interest") {
            put("p_slug", slug)
            put("p_name_tr", nameTr)
            put("p_name_en", nameEn)
            put("p_category", category)
            put("p_active", active)
            put("p_sort", sort)
        }

    suspend fun upsertGift(code: String, nameTr: String, nameEn: String, iconKey: String, price: Int, active: Boolean, sort: Int) =
        api.rpcUnit("admin_upsert_gift") {
            put("p_code", code)
            put("p_name_tr", nameTr)
            put("p_name_en", nameEn)
            put("p_icon_key", iconKey)
            put("p_price", price)
            put("p_active", active)
            put("p_sort", sort)
        }
}
