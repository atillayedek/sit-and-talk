package com.sitandtalk.feature.rooms

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.material3.AlertDialog
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sitandtalk.core.designsystem.StPrimaryButton
import com.sitandtalk.core.designsystem.StTextButton
import com.sitandtalk.core.designsystem.StTextField
import com.sitandtalk.core.designsystem.StTheme
import com.sitandtalk.core.designsystem.StTopBar
import com.sitandtalk.core.designsystem.SupportedLanguages
import com.sitandtalk.core.designsystem.SwitchRow
import com.sitandtalk.core.designsystem.ToggleChip
import com.sitandtalk.core.designsystem.errorMessage
import com.sitandtalk.core.designsystem.languageLabel
import com.sitandtalk.core.model.RoomVisibility
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * Room creation form. With [planEvent] = true the same form schedules a future room event instead.
 * With [eventId] it opens the live room for an event the user scheduled.
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun CreateRoomScreen(
    onBack: () -> Unit,
    onCreated: (String) -> Unit,
    planEvent: Boolean = false,
    eventId: String? = null,
    eventTitle: String? = null,
    viewModel: CreateRoomViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val errorText = state.error?.let { errorMessage(it) }
    var pickDate by rememberSaveable { mutableStateOf(false) }
    var pickTime by rememberSaveable { mutableStateOf(false) }
    var chosenDate by rememberSaveable { mutableStateOf<String?>(null) }

    LaunchedEffect(eventId) { if (eventId != null) viewModel.forEvent(eventId, eventTitle.orEmpty()) }
    LaunchedEffect(errorText) {
        if (errorText != null) {
            snackbar.showSnackbar(errorText)
            viewModel.consumeError()
        }
    }
    LaunchedEffect(state.createdRoomId) { state.createdRoomId?.let(onCreated) }
    LaunchedEffect(state.eventCreated) { if (state.eventCreated) onBack() }

    Scaffold(
        topBar = { StTopBar(stringResource(if (planEvent) R.string.events_create else R.string.create_title), onBack = onBack) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
        ) {
            StTextField(state.title, { v -> viewModel.update { it.copy(title = v) } },
                stringResource(if (planEvent) R.string.events_title_field else R.string.create_name), maxLength = if (planEvent) 80 else 60)
            Spacer(Modifier.height(8.dp))
            StTextField(state.description, { v -> viewModel.update { it.copy(description = v) } }, stringResource(R.string.create_description),
                singleLine = false, minLines = 2, maxLength = if (planEvent) 500 else 300)
            Spacer(Modifier.height(12.dp))
            Text(stringResource(R.string.create_topic), style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                state.interests.forEach { i ->
                    ToggleChip(interestName(state.interests, i.slug).orEmpty(), state.topic == i.slug,
                        { viewModel.update { it.copy(topic = if (it.topic == i.slug) null else i.slug) } })
                }
            }
            Spacer(Modifier.height(12.dp))
            Text(stringResource(R.string.create_language), style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SupportedLanguages.forEach { code -> ToggleChip(languageLabel(code), state.language == code, { viewModel.update { it.copy(language = code) } }) }
            }

            if (planEvent) {
                Spacer(Modifier.height(16.dp))
                Text(stringResource(R.string.events_when), style = MaterialTheme.typography.labelLarge)
                val formatted = state.eventStartsAt?.atZone(ZoneId.systemDefault())?.format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT))
                if (formatted != null) Text(formatted, style = MaterialTheme.typography.bodyLarge)
                StTextButton(stringResource(R.string.events_pick_date), { pickDate = true })
                Spacer(Modifier.height(16.dp))
                StPrimaryButton(stringResource(R.string.events_submit), viewModel::submitEvent, loading = state.submitting,
                    enabled = state.title.trim().length >= 3 && state.eventStartsAt != null, modifier = Modifier.fillMaxWidth())
            } else {
                Spacer(Modifier.height(12.dp))
                StTextField(state.tags, { v -> viewModel.update { it.copy(tags = v) } }, stringResource(R.string.create_tags), maxLength = 120)
                Spacer(Modifier.height(12.dp))
                Text(stringResource(R.string.create_visibility), style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(RoomVisibility.Public to R.string.create_public, RoomVisibility.Invite to R.string.create_invite, RoomVisibility.Password to R.string.create_password_opt)
                        .forEach { (v, label) -> ToggleChip(stringResource(label), state.visibility == v, { viewModel.update { it.copy(visibility = v) } }) }
                }
                if (state.visibility == RoomVisibility.Password) {
                    StTextField(state.password, { v -> viewModel.update { it.copy(password = v) } }, stringResource(R.string.create_password), isPassword = true, maxLength = 64)
                }
                Spacer(Modifier.height(12.dp))
                Text(stringResource(R.string.create_max_participants, state.maxParticipants))
                Slider(value = state.maxParticipants.toFloat(), onValueChange = { v -> viewModel.update { it.copy(maxParticipants = v.toInt()) } }, valueRange = 2f..50f, steps = 47)
                Text(stringResource(R.string.create_max_speakers, state.maxSpeakers))
                Slider(value = state.maxSpeakers.toFloat(), onValueChange = { v -> viewModel.update { it.copy(maxSpeakers = v.toInt()) } }, valueRange = 1f..20f, steps = 18)
                SwitchRow(stringResource(R.string.create_hand_raise), state.handRaise, { v -> viewModel.update { it.copy(handRaise = v) } })
                SwitchRow(stringResource(R.string.create_text_chat), state.textChat, { v -> viewModel.update { it.copy(textChat = v) } })
                Text(stringResource(R.string.create_mic_note), style = MaterialTheme.typography.bodySmall, color = StTheme.extra.textSecondary)
                Spacer(Modifier.height(16.dp))
                StPrimaryButton(stringResource(R.string.create_submit), viewModel::submit, loading = state.submitting, enabled = state.valid, modifier = Modifier.fillMaxWidth())
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (pickDate) {
        val dateState = rememberDatePickerState(initialSelectedDateMillis = System.currentTimeMillis())
        DatePickerDialog(
            onDismissRequest = { pickDate = false },
            confirmButton = {
                TextButton(onClick = {
                    chosenDate = dateState.selectedDateMillis?.let { Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate().toString() }
                    pickDate = false
                    if (chosenDate != null) pickTime = true
                }) { Text(stringResource(com.sitandtalk.core.designsystem.R.string.ds_ok)) }
            },
        ) { DatePicker(dateState) }
    }
    if (pickTime) {
        val timeState = rememberTimePickerState(is24Hour = true)
        AlertDialog(
            onDismissRequest = { pickTime = false },
            title = { Text(stringResource(R.string.events_pick_time)) },
            text = { TimePicker(timeState) },
            confirmButton = {
                TextButton(onClick = {
                    val date = chosenDate?.let { LocalDate.parse(it) }
                    if (date != null) {
                        val start = date.atTime(LocalTime.of(timeState.hour, timeState.minute)).atZone(ZoneId.systemDefault()).toInstant()
                        viewModel.update { it.copy(eventStartsAt = start) }
                    }
                    pickTime = false
                }) { Text(stringResource(com.sitandtalk.core.designsystem.R.string.ds_ok)) }
            },
        )
    }
}
