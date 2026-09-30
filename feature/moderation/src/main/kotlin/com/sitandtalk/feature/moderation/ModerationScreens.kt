package com.sitandtalk.feature.moderation

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.sitandtalk.core.data.AdminRepository
import com.sitandtalk.core.data.BootstrapRepository
import com.sitandtalk.core.data.ModerationRepository
import com.sitandtalk.core.designsystem.EmptyState
import com.sitandtalk.core.designsystem.InfoBanner
import com.sitandtalk.core.designsystem.LoadStateContent
import com.sitandtalk.core.designsystem.SectionHeader
import com.sitandtalk.core.designsystem.StPrimaryButton
import com.sitandtalk.core.designsystem.StSecondaryButton
import com.sitandtalk.core.designsystem.StTextButton
import com.sitandtalk.core.designsystem.StTextField
import com.sitandtalk.core.designsystem.StTheme
import com.sitandtalk.core.designsystem.StTopBar
import com.sitandtalk.core.designsystem.SwitchRow
import com.sitandtalk.core.designsystem.errorMessage
import com.sitandtalk.core.model.AdminAppeal
import com.sitandtalk.core.model.AdminDashboard
import com.sitandtalk.core.model.AdminReport
import com.sitandtalk.core.model.AdminRoom
import com.sitandtalk.core.model.AppException
import com.sitandtalk.core.model.AppSetting
import com.sitandtalk.core.model.AuditLogEntry
import com.sitandtalk.core.model.FeatureFlag
import com.sitandtalk.core.model.LoadState
import com.sitandtalk.core.model.MyReport
import com.sitandtalk.core.model.ReportTarget
import com.sitandtalk.core.model.Restriction
import com.sitandtalk.core.model.ServerTime
import com.sitandtalk.core.network.AppJson
import com.sitandtalk.core.network.toAppException
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

private fun ago(iso: String?): String =
    DateUtils.getRelativeTimeSpanString(ServerTime.parse(iso)?.toEpochMilli() ?: 0).toString()

@Composable
fun restrictionLabel(kind: String): String = stringResource(
    when (kind) {
        "warning" -> R.string.kind_warning
        "messaging" -> R.string.kind_messaging
        "matching" -> R.string.kind_matching
        "room_creation" -> R.string.kind_room_creation
        "posting" -> R.string.kind_posting
        "suspension" -> R.string.kind_suspension
        else -> R.string.kind_ban
    },
)

// ---------------------------------------------------------------------------------------------
// Safety centre (every user)
// ---------------------------------------------------------------------------------------------

data class SafetyState(
    val restrictions: LoadState<List<Restriction>> = LoadState.Loading,
    val reports: List<MyReport> = emptyList(),
    val error: AppException? = null,
    val info: Boolean = false,
)

@HiltViewModel
class SafetyViewModel @Inject constructor(private val repo: ModerationRepository) : ViewModel() {
    private val _state = MutableStateFlow(SafetyState())
    val state: StateFlow<SafetyState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _state.update {
                it.copy(
                    restrictions = try {
                        LoadState.Success(repo.myRestrictions())
                    } catch (e: Exception) {
                        LoadState.Failure(e.toAppException())
                    },
                    reports = runCatching { repo.myReports() }.getOrDefault(emptyList()),
                )
            }
        }
    }

    fun appeal(restriction: Restriction, body: String) {
        viewModelScope.launch {
            try {
                repo.appeal(restriction.id, body)
                _state.update { it.copy(info = true) }
                refresh()
            } catch (e: Exception) {
                _state.update { it.copy(error = e.toAppException()) }
            }
        }
    }

    fun consume() = _state.update { it.copy(error = null, info = false) }
}

@Composable
fun SafetyScreen(onBack: () -> Unit, viewModel: SafetyViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val errorText = state.error?.let { errorMessage(it) }
    val sent = stringResource(R.string.safety_appeal_sent)
    LaunchedEffect(errorText, state.info) {
        val msg = errorText ?: if (state.info) sent else null
        if (msg != null) { snackbar.showSnackbar(msg); viewModel.consume() }
    }
    var appealFor by remember { mutableStateOf<Restriction?>(null) }
    Scaffold(topBar = { StTopBar(stringResource(R.string.safety_title), onBack = onBack) }, snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        LoadStateContent(state.restrictions, viewModel::refresh, Modifier.padding(padding)) { restrictions ->
            LazyColumn(Modifier.fillMaxSize().padding(padding)) {
                item { SectionHeader(stringResource(R.string.safety_restrictions)) }
                if (restrictions.isEmpty()) item { Text(stringResource(R.string.safety_no_restrictions), Modifier.padding(horizontal = 16.dp), color = StTheme.extra.textSecondary) }
                items(restrictions, key = { it.id }) { r ->
                    Card(shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
                        Column(Modifier.padding(16.dp)) {
                            Text(restrictionLabel(r.kind), style = MaterialTheme.typography.titleMedium)
                            Text(r.reason)
                            Text(if (r.endsAt != null) stringResource(R.string.safety_until, ago(r.endsAt)) else stringResource(R.string.safety_permanent),
                                style = MaterialTheme.typography.bodySmall, color = StTheme.extra.textSecondary)
                            val appeal = r.appeal
                            if (appeal != null) {
                                Text(stringResource(R.string.safety_appeal_status, appeal.status) + (appeal.decisionNote?.let { " — $it" } ?: ""), style = MaterialTheme.typography.bodySmall)
                            } else if (r.kind != "warning") {
                                StTextButton(stringResource(R.string.safety_appeal), { appealFor = r })
                            }
                        }
                    }
                }
                item { SectionHeader(stringResource(R.string.safety_reports)) }
                if (state.reports.isEmpty()) item { Text(stringResource(R.string.safety_no_reports), Modifier.padding(horizontal = 16.dp), color = StTheme.extra.textSecondary) }
                items(state.reports, key = { it.id }) { rep ->
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                        Text(com.sitandtalk.core.designsystem.reportReasonLabel(com.sitandtalk.core.model.ReportReason.entries.firstOrNull { it.wire == rep.reason }
                            ?: com.sitandtalk.core.model.ReportReason.Other))
                        Text(
                            stringResource(when (rep.status) {
                                "reviewing" -> R.string.safety_status_reviewing
                                "actioned" -> R.string.safety_status_actioned
                                "dismissed" -> R.string.safety_status_dismissed
                                else -> R.string.safety_status_open
                            }) + " · " + ago(rep.createdAt),
                            style = MaterialTheme.typography.bodySmall, color = StTheme.extra.textSecondary,
                        )
                    }
                }
            }
        }
    }
    appealFor?.let { r ->
        var body by rememberSaveable { mutableStateOf("") }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { appealFor = null },
            title = { Text(stringResource(R.string.safety_appeal)) },
            text = { StTextField(body, { body = it }, stringResource(R.string.safety_appeal_hint), singleLine = false, minLines = 3, maxLength = 2000) },
            confirmButton = { StTextButton(stringResource(com.sitandtalk.core.designsystem.R.string.ds_report_send), { viewModel.appeal(r, body); appealFor = null }, enabled = body.trim().length >= 10) },
            dismissButton = { StTextButton(stringResource(com.sitandtalk.core.designsystem.R.string.ds_cancel), { appealFor = null }) },
        )
    }
}

// ---------------------------------------------------------------------------------------------
// Admin panel (staff only; the server re-checks the role on every call)
// ---------------------------------------------------------------------------------------------

enum class AdminTab { Overview, Reports, Appeals, Rooms, Config, Audit }

data class AdminState(
    val tab: AdminTab = AdminTab.Overview,
    val dashboard: LoadState<AdminDashboard> = LoadState.Loading,
    val reports: LoadState<List<AdminReport>> = LoadState.Loading,
    val appeals: LoadState<List<AdminAppeal>> = LoadState.Loading,
    val rooms: LoadState<List<AdminRoom>> = LoadState.Loading,
    val flags: List<FeatureFlag> = emptyList(),
    val settings: List<AppSetting> = emptyList(),
    val audit: LoadState<List<AuditLogEntry>> = LoadState.Loading,
    val isAdmin: Boolean = false,
    val error: AppException? = null,
    val done: Boolean = false,
)

@HiltViewModel
class AdminViewModel @Inject constructor(private val admin: AdminRepository, bootstrap: BootstrapRepository) : ViewModel() {
    private val _state = MutableStateFlow(AdminState(isAdmin = bootstrap.current.value?.staffRole == "admin"))
    val state: StateFlow<AdminState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun setTab(tab: AdminTab) {
        _state.update { it.copy(tab = tab) }
        refresh()
    }

    private suspend fun <T> load(block: suspend () -> T): LoadState<T> = try {
        LoadState.Success(block())
    } catch (e: Exception) {
        LoadState.Failure(e.toAppException())
    }

    fun refresh() {
        viewModelScope.launch {
            when (_state.value.tab) {
                AdminTab.Overview -> _state.update { it.copy(dashboard = load { admin.dashboard() }) }
                AdminTab.Reports -> _state.update { it.copy(reports = load { admin.reports() }) }
                AdminTab.Appeals -> _state.update { it.copy(appeals = load { admin.appeals() }) }
                AdminTab.Rooms -> _state.update { it.copy(rooms = load { admin.rooms() }) }
                AdminTab.Config -> {
                    val flags = runCatching { admin.flags() }.getOrDefault(emptyList())
                    val settings = runCatching { admin.settings() }.getOrDefault(emptyList())
                    _state.update { it.copy(flags = flags, settings = settings) }
                }
                AdminTab.Audit -> _state.update { it.copy(audit = load { admin.auditLog() }) }
            }
        }
    }

    fun act(block: suspend AdminRepository.() -> Unit) {
        viewModelScope.launch {
            try {
                admin.block()
                _state.update { it.copy(done = true) }
                refresh()
            } catch (e: Exception) {
                _state.update { it.copy(error = e.toAppException()) }
            }
        }
    }

    fun consume() = _state.update { it.copy(error = null, done = false) }
}

@Composable
fun AdminScreen(onBack: () -> Unit, viewModel: AdminViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val errorText = state.error?.let { errorMessage(it) }
    val doneText = stringResource(R.string.admin_done)
    LaunchedEffect(errorText, state.done) {
        val msg = errorText ?: if (state.done) doneText else null
        if (msg != null) { snackbar.showSnackbar(msg); viewModel.consume() }
    }
    Scaffold(topBar = { StTopBar(stringResource(R.string.admin_title), onBack = onBack) }, snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            PrimaryScrollableTabRow(selectedTabIndex = state.tab.ordinal, edgePadding = 8.dp) {
                AdminTab.entries.forEach { tab ->
                    Tab(selected = state.tab == tab, onClick = { viewModel.setTab(tab) }, text = {
                        Text(stringResource(when (tab) {
                            AdminTab.Overview -> R.string.admin_tab_overview
                            AdminTab.Reports -> R.string.admin_tab_reports
                            AdminTab.Appeals -> R.string.admin_tab_appeals
                            AdminTab.Rooms -> R.string.admin_tab_rooms
                            AdminTab.Config -> R.string.admin_tab_config
                            AdminTab.Audit -> R.string.admin_tab_audit
                        }))
                    })
                }
            }
            when (state.tab) {
                AdminTab.Overview -> LoadStateContent(state.dashboard, viewModel::refresh) { d -> Overview(d) }
                AdminTab.Reports -> LoadStateContent(state.reports, viewModel::refresh) { list -> Reports(list, state.isAdmin, viewModel) }
                AdminTab.Appeals -> LoadStateContent(state.appeals, viewModel::refresh) { list -> Appeals(list, viewModel) }
                AdminTab.Rooms -> LoadStateContent(state.rooms, viewModel::refresh) { list -> Rooms(list, viewModel) }
                AdminTab.Config -> Config(state, viewModel)
                AdminTab.Audit -> LoadStateContent(state.audit, viewModel::refresh) { list ->
                    LazyColumn {
                        items(list, key = { it.id }) { e ->
                            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                                Text("${e.action} · ${e.targetType} ${e.targetId.orEmpty().take(8)}", style = MaterialTheme.typography.bodyMedium)
                                Text(listOfNotNull(ago(e.createdAt), e.reason, e.result).joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = StTheme.extra.textSecondary)
                            }
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Overview(d: AdminDashboard) {
    val rows = listOf(
        R.string.admin_users_total to d.usersTotal, R.string.admin_users_new to d.usersNew7d, R.string.admin_active_24h to d.active24h,
        R.string.admin_active_calls to d.activeCalls, R.string.admin_queue to d.queueWaiting, R.string.admin_open_rooms to d.openRooms,
        R.string.admin_open_reports to d.openReports, R.string.admin_open_appeals to d.openAppeals, R.string.admin_restricted to d.restrictedAccounts,
        R.string.admin_pending_content to d.pendingContent, R.string.admin_purchases to d.purchases30d, R.string.admin_subscriptions to d.activeSubscriptions,
        R.string.admin_push_failed to d.pushFailed24h, R.string.admin_push_pending to d.pushPending,
    )
    LazyColumn {
        items(rows) { (label, value) ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                Text(stringResource(label), Modifier.weight(1f))
                Text(value.toString(), style = MaterialTheme.typography.titleMedium)
            }
            HorizontalDivider()
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Reports(list: List<AdminReport>, isAdmin: Boolean, vm: AdminViewModel) {
    if (list.isEmpty()) {
        EmptyState(stringResource(R.string.admin_no_reports))
        return
    }
    LazyColumn {
        items(list, key = { it.id }) { r ->
            var reason by rememberSaveable(r.id) { mutableStateOf("") }
            Card(shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
                Column(Modifier.padding(14.dp)) {
                    Text("${r.targetType.name} · ${r.reason}", style = MaterialTheme.typography.titleSmall)
                    Text(stringResource(R.string.admin_report_meta, ago(r.createdAt), r.priority, r.targetReportCount),
                        style = MaterialTheme.typography.bodySmall, color = StTheme.extra.textSecondary)
                    r.targetUser?.let { Text("@" + it.username + " (" + it.displayName + ")") }
                    if (r.details.isNotBlank()) Text(r.details)
                    if (r.context.isNotEmpty()) {
                        Text(stringResource(R.string.admin_context), style = MaterialTheme.typography.labelMedium)
                        Text(AppJson.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), r.context).take(600), style = MaterialTheme.typography.bodySmall)
                    }
                    StTextField(reason, { reason = it }, stringResource(R.string.admin_reason), maxLength = 300)
                    val ok = reason.trim().length >= 3
                    val user = r.targetUser?.id
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        StTextButton(stringResource(R.string.admin_dismiss), { vm.act { resolveReport(r.id, true, reason) } }, enabled = ok)
                        if (user != null) {
                            StTextButton(stringResource(R.string.admin_warn), { vm.act { restrict(user, "warning", null, reason, r.id) } }, enabled = ok)
                            StTextButton(stringResource(R.string.admin_restrict_24h), { vm.act { restrict(user, "matching", 24, reason, r.id) } }, enabled = ok)
                            StTextButton(stringResource(R.string.admin_suspend_7d), { vm.act { restrict(user, "suspension", 24 * 7, reason, r.id) } }, enabled = ok)
                            if (isAdmin) StTextButton(stringResource(R.string.admin_ban), { vm.act { restrict(user, "ban", null, reason, r.id) } }, enabled = ok)
                        }
                        if (r.targetType !in setOf(ReportTarget.User, ReportTarget.Call)) {
                            StTextButton(stringResource(R.string.admin_remove_content), { vm.act { removeContent(r.targetType, r.targetId, reason, r.id) } }, enabled = ok)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Appeals(list: List<AdminAppeal>, vm: AdminViewModel) {
    if (list.isEmpty()) {
        EmptyState(stringResource(R.string.admin_no_appeals))
        return
    }
    LazyColumn {
        items(list, key = { it.id }) { a ->
            var note by rememberSaveable(a.id) { mutableStateOf("") }
            Card(shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
                Column(Modifier.padding(14.dp)) {
                    Text("@" + a.user?.username.orEmpty() + " · " + ago(a.createdAt), style = MaterialTheme.typography.titleSmall)
                    a.restriction?.let { Text(restrictionLabel(it.kind) + ": " + it.reason, style = MaterialTheme.typography.bodySmall) }
                    Text(a.body)
                    StTextField(note, { note = it }, stringResource(R.string.admin_reason), maxLength = 300)
                    Row {
                        StTextButton(stringResource(R.string.admin_uphold), { vm.act { decideAppeal(a.id, false, note) } }, enabled = note.isNotBlank())
                        StTextButton(stringResource(R.string.admin_overturn), { vm.act { decideAppeal(a.id, true, note) } }, enabled = note.isNotBlank())
                    }
                }
            }
        }
    }
}

@Composable
private fun Rooms(list: List<AdminRoom>, vm: AdminViewModel) {
    if (list.isEmpty()) {
        EmptyState(stringResource(R.string.admin_no_rooms))
        return
    }
    LazyColumn {
        items(list, key = { it.id }) { r ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(r.title, style = MaterialTheme.typography.titleSmall)
                    Text("@" + r.owner?.username.orEmpty() + " · ${r.participantCount} · ${r.openReports}", style = MaterialTheme.typography.bodySmall)
                }
                StTextButton(stringResource(R.string.admin_close_room), { vm.act { removeContent(ReportTarget.Room, r.id, "moderation", null) } })
            }
            HorizontalDivider()
        }
    }
}

@Composable
private fun Config(state: AdminState, vm: AdminViewModel) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        if (!state.isAdmin) InfoBanner(stringResource(R.string.admin_admin_only), Modifier.padding(16.dp))
        SectionHeader(stringResource(R.string.admin_flags))
        state.flags.forEach { f ->
            SwitchRow(f.key, f.enabled, { v -> vm.act { setFlag(f.key, v, "admin panel") } }, subtitle = f.description, enabled = state.isAdmin)
        }
        SectionHeader(stringResource(R.string.admin_settings))
        state.settings.forEach { s ->
            var text by rememberSaveable(s.key, s.value.toString()) { mutableStateOf(s.value.toString()) }
            Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                StTextField(text, { text = it }, s.key, supporting = s.description, singleLine = false, enabled = state.isAdmin)
                StTextButton(stringResource(R.string.admin_setting_save), {
                    val parsed = runCatching { AppJson.parseToJsonElement(text) }.getOrNull()
                    if (parsed != null) vm.act { setSetting(s.key, parsed, "admin panel") }
                }, enabled = state.isAdmin && text != s.value.toString())
            }
        }
        SectionHeader(stringResource(R.string.admin_announcement))
        var title by rememberSaveable { mutableStateOf("") }
        var body by rememberSaveable { mutableStateOf("") }
        Column(Modifier.padding(horizontal = 16.dp)) {
            StTextField(title, { title = it }, stringResource(R.string.admin_announcement_title), enabled = state.isAdmin, maxLength = 80)
            StTextField(body, { body = it }, stringResource(R.string.admin_announcement_body), singleLine = false, minLines = 3, enabled = state.isAdmin, maxLength = 500)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StPrimaryButton(stringResource(R.string.admin_announcement_publish), { vm.act { publishAnnouncement(title, body, title, body, true) } },
                    enabled = state.isAdmin && title.isNotBlank() && body.isNotBlank())
                StSecondaryButton(stringResource(R.string.admin_announcement_hide), { vm.act { publishAnnouncement("", "", "", "", false) } }, enabled = state.isAdmin)
            }
        }
        SectionHeader(stringResource(R.string.admin_catalog))
        var slug by rememberSaveable { mutableStateOf("") }
        var nameTr by rememberSaveable { mutableStateOf("") }
        var nameEn by rememberSaveable { mutableStateOf("") }
        var price by rememberSaveable { mutableStateOf("") }
        var icon by rememberSaveable { mutableStateOf("") }
        Column(Modifier.padding(horizontal = 16.dp)) {
            StTextField(slug, { slug = it.lowercase() }, stringResource(R.string.admin_slug), enabled = state.isAdmin, maxLength = 40)
            StTextField(nameTr, { nameTr = it }, stringResource(R.string.admin_name_tr), enabled = state.isAdmin, maxLength = 40)
            StTextField(nameEn, { nameEn = it }, stringResource(R.string.admin_name_en), enabled = state.isAdmin, maxLength = 40)
            StTextButton(stringResource(R.string.admin_interest_add), { vm.act { upsertInterest(slug, nameTr, nameEn, "general", true, 500) } },
                enabled = state.isAdmin && slug.isNotBlank() && nameTr.isNotBlank() && nameEn.isNotBlank())
            StTextField(price, { price = it.filter { c -> c.isDigit() }.take(6) }, stringResource(R.string.admin_price), enabled = state.isAdmin)
            StTextField(icon, { icon = it }, stringResource(R.string.admin_icon), enabled = state.isAdmin, maxLength = 40)
            StTextButton(stringResource(R.string.admin_gift_add), { vm.act { upsertGift(slug, nameTr, nameEn, icon, price.toIntOrNull() ?: 0, true, 100) } },
                enabled = state.isAdmin && slug.isNotBlank() && (price.toIntOrNull() ?: 0) > 0 && icon.isNotBlank())
        }
        Spacer(Modifier.height(32.dp))
    }
}
