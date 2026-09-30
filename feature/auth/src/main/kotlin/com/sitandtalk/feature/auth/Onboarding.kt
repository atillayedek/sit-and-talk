package com.sitandtalk.feature.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.sitandtalk.core.data.OnboardingInput
import com.sitandtalk.core.data.ProfileRepository
import com.sitandtalk.core.designsystem.LoadingState
import com.sitandtalk.core.designsystem.StPrimaryButton
import com.sitandtalk.core.designsystem.StTextButton
import com.sitandtalk.core.designsystem.StTextField
import com.sitandtalk.core.designsystem.StTheme
import com.sitandtalk.core.designsystem.StTopBar
import com.sitandtalk.core.designsystem.SupportedLanguages
import com.sitandtalk.core.designsystem.ToggleChip
import com.sitandtalk.core.designsystem.errorMessage
import com.sitandtalk.core.designsystem.interestLabel
import com.sitandtalk.core.designsystem.languageLabel
import com.sitandtalk.core.model.AppException
import com.sitandtalk.core.model.Interest
import com.sitandtalk.core.network.toAppException
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.Period
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import javax.inject.Inject

data class OnboardingState(
    val step: Int = 0,
    val displayName: String = "",
    val username: String = "",
    val birthDate: LocalDate? = null,
    val interests: Set<String> = emptySet(),
    val languages: Set<String> = setOf(Locale.getDefault().language.takeIf { it in SupportedLanguages } ?: "tr"),
    val catalog: List<Interest>? = null,
    val catalogError: AppException? = null,
    val submitting: Boolean = false,
    val error: AppException? = null,
    val done: Boolean = false,
) {
    val usernameValid get() = username.matches(Regex("[a-z0-9_.]{3,24}"))
    val displayNameValid get() = displayName.trim().length in 2..32
    val isAdult get() = birthDate?.let { Period.between(it, LocalDate.now()).years >= 18 } == true
}

@HiltViewModel
class OnboardingViewModel @Inject constructor(private val profiles: ProfileRepository) : ViewModel() {
    private val _state = MutableStateFlow(OnboardingState())
    val state: StateFlow<OnboardingState> = _state.asStateFlow()

    init {
        loadCatalog()
    }

    fun loadCatalog() {
        viewModelScope.launch {
            _state.update { it.copy(catalogError = null) }
            try {
                val list = profiles.interests()
                _state.update { it.copy(catalog = list) }
            } catch (e: Exception) {
                _state.update { it.copy(catalogError = e.toAppException()) }
            }
        }
    }

    fun setDisplayName(v: String) = _state.update { it.copy(displayName = v) }
    fun setUsername(v: String) = _state.update { it.copy(username = v.lowercase().filter { c -> c.isLetterOrDigit() || c == '_' || c == '.' }) }
    fun setBirthDate(d: LocalDate) = _state.update { it.copy(birthDate = d) }
    fun toggleInterest(slug: String) = _state.update {
        val next = if (slug in it.interests) it.interests - slug else if (it.interests.size < 15) it.interests + slug else it.interests
        it.copy(interests = next)
    }
    fun toggleLanguage(code: String) = _state.update {
        it.copy(languages = if (code in it.languages) it.languages - code else it.languages + code)
    }
    fun next() = _state.update { it.copy(step = (it.step + 1).coerceAtMost(LAST_STEP)) }
    fun back() = _state.update { it.copy(step = (it.step - 1).coerceAtLeast(0)) }
    fun consumeError() = _state.update { it.copy(error = null) }

    fun finish() {
        val s = _state.value
        val birth = s.birthDate ?: return
        if (s.submitting) return
        viewModelScope.launch {
            _state.update { it.copy(submitting = true) }
            try {
                profiles.completeOnboarding(
                    OnboardingInput(s.username, s.displayName, birth, s.interests.toList(), s.languages.toList()),
                )
                _state.update { it.copy(done = true) }
            } catch (e: Exception) {
                val mapped = e.toAppException()
                _state.update {
                    it.copy(error = mapped, step = when (mapped.code) {
                        "username_taken", "invalid_username", "invalid_display_name" -> 0
                        "underage", "invalid_birth_date" -> 1
                        else -> it.step
                    })
                }
            } finally {
                _state.update { it.copy(submitting = false) }
            }
        }
    }

    companion object {
        const val LAST_STEP = 4
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun OnboardingScreen(onCompleted: () -> Unit, onSignOut: () -> Unit, viewModel: OnboardingViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val errorText = state.error?.let { errorMessage(it) }
    var showPicker by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(errorText) {
        if (errorText != null) {
            snackbar.showSnackbar(errorText)
            viewModel.consumeError()
        }
    }
    LaunchedEffect(state.done) { if (state.done) onCompleted() }

    Scaffold(
        topBar = { StTopBar(stringResource(R.string.onb_title), onBack = if (state.step > 0) viewModel::back else null) },
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
            LinearProgressIndicator(progress = { (state.step + 1) / (OnboardingViewModel.LAST_STEP + 1f) }, modifier = Modifier.fillMaxWidth())
            Text(
                stringResource(R.string.onb_step, state.step + 1, OnboardingViewModel.LAST_STEP + 1),
                style = MaterialTheme.typography.labelMedium,
                color = StTheme.extra.textSecondary,
                modifier = Modifier.padding(vertical = 8.dp),
            )
            when (state.step) {
                0 -> {
                    StepHeader(R.string.onb_identity_title, R.string.onb_identity_body)
                    StTextField(state.displayName, viewModel::setDisplayName, stringResource(R.string.onb_display_name), maxLength = 32,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words))
                    Spacer(Modifier.height(8.dp))
                    StTextField(state.username, viewModel::setUsername, stringResource(R.string.onb_username), maxLength = 24,
                        supporting = stringResource(R.string.onb_username_hint))
                    NextButton(enabled = state.usernameValid && state.displayNameValid, onClick = viewModel::next)
                }
                1 -> {
                    StepHeader(R.string.onb_birth_title, R.string.onb_birth_body)
                    val formatted = state.birthDate?.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG))
                    if (formatted != null) Text(stringResource(R.string.onb_birth_selected, formatted), style = MaterialTheme.typography.bodyLarge)
                    StTextButton(stringResource(R.string.onb_birth_pick), { showPicker = true })
                    NextButton(enabled = state.birthDate != null, onClick = viewModel::next)
                }
                2 -> {
                    StepHeader(R.string.onb_interests_title, R.string.onb_interests_body)
                    val catalog = state.catalog
                    when {
                        catalog != null -> FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            catalog.forEach { interest ->
                                ToggleChip(
                                    label = interestLabel(interest),
                                    selected = interest.slug in state.interests,
                                    onClick = { viewModel.toggleInterest(interest.slug) },
                                )
                            }
                        }
                        state.catalogError != null -> {
                            Text(errorMessage(state.catalogError!!), color = MaterialTheme.colorScheme.error)
                            StTextButton(stringResource(com.sitandtalk.core.designsystem.R.string.ds_retry), viewModel::loadCatalog)
                        }
                        else -> Column(Modifier.height(120.dp)) { LoadingState() }
                    }
                    NextButton(enabled = true, onClick = viewModel::next)
                }
                3 -> {
                    StepHeader(R.string.onb_languages_title, R.string.onb_languages_body)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SupportedLanguages.forEach { code ->
                            ToggleChip(languageLabel(code), code in state.languages, { viewModel.toggleLanguage(code) })
                        }
                    }
                    NextButton(enabled = state.languages.isNotEmpty(), onClick = viewModel::next)
                }
                else -> {
                    StepHeader(R.string.onb_consent_title, R.string.onb_consent_body)
                    StPrimaryButton(
                        stringResource(R.string.onb_finish),
                        viewModel::finish,
                        loading = state.submitting,
                        enabled = state.isAdult && state.usernameValid && state.languages.isNotEmpty(),
                        modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                    )
                }
            }
            Row(Modifier.padding(vertical = 16.dp)) {
                StTextButton(stringResource(R.string.auth_verify_back), onSignOut)
            }
        }
    }

    if (showPicker) {
        val pickerState = rememberDatePickerState(
            initialSelectedDateMillis = state.birthDate?.atStartOfDay(ZoneOffset.UTC)?.toInstant()?.toEpochMilli(),
            yearRange = (LocalDate.now().year - 100)..LocalDate.now().year,
        )
        DatePickerDialog(
            onDismissRequest = { showPicker = false },
            confirmButton = {
                TextButton(onClick = {
                    pickerState.selectedDateMillis?.let {
                        viewModel.setBirthDate(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate())
                    }
                    showPicker = false
                }) { Text(stringResource(com.sitandtalk.core.designsystem.R.string.ds_ok)) }
            },
            dismissButton = { TextButton(onClick = { showPicker = false }) { Text(stringResource(com.sitandtalk.core.designsystem.R.string.ds_cancel)) } },
        ) {
            DatePicker(state = pickerState)
        }
    }
}

@Composable
private fun StepHeader(title: Int, body: Int) {
    Text(stringResource(title), style = MaterialTheme.typography.headlineSmall)
    Spacer(Modifier.height(6.dp))
    Text(stringResource(body), style = MaterialTheme.typography.bodyMedium, color = StTheme.extra.textSecondary)
    Spacer(Modifier.height(16.dp))
}

@Composable
private fun NextButton(enabled: Boolean, onClick: () -> Unit) {
    StPrimaryButton(stringResource(R.string.onb_next), onClick, enabled = enabled, modifier = Modifier.fillMaxWidth().padding(top = 20.dp))
}
