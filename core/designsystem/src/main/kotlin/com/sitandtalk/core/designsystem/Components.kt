package com.sitandtalk.core.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.sitandtalk.core.model.AppException
import com.sitandtalk.core.model.LoadState
import com.sitandtalk.core.model.ReportReason

// ---------------------------------------------------------------------------------------------
// Buttons
// ---------------------------------------------------------------------------------------------

@Composable
fun StPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
    icon: ImageVector? = null,
) {
    Button(
        onClick = onClick,
        enabled = enabled && !loading,
        modifier = modifier.heightIn(min = 52.dp),
        shape = RoundedCornerShape(16.dp),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
    ) {
        ButtonContent(text, loading, icon, MaterialTheme.colorScheme.onPrimary)
    }
}

@Composable
fun StSecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
    icon: ImageVector? = null,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled && !loading,
        modifier = modifier.heightIn(min = 52.dp),
        shape = RoundedCornerShape(16.dp),
    ) {
        ButtonContent(text, loading, icon, MaterialTheme.colorScheme.primary)
    }
}

@Composable
fun StDangerButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
) {
    Button(
        onClick = onClick,
        enabled = enabled && !loading,
        modifier = modifier.heightIn(min = 52.dp),
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError),
    ) {
        ButtonContent(text, loading, null, MaterialTheme.colorScheme.onError)
    }
}

@Composable
fun StTextButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    TextButton(onClick = onClick, enabled = enabled, modifier = modifier.heightIn(min = 48.dp)) {
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun ButtonContent(text: String, loading: Boolean, icon: ImageVector?, tint: Color) {
    if (loading) {
        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = tint)
        Spacer(Modifier.width(10.dp))
    } else if (icon != null) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
    }
    Text(text, style = MaterialTheme.typography.labelLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
}

// ---------------------------------------------------------------------------------------------
// Inputs
// ---------------------------------------------------------------------------------------------

@Composable
fun StTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    error: String? = null,
    supporting: String? = null,
    singleLine: Boolean = true,
    minLines: Int = 1,
    maxLength: Int? = null,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    isPassword: Boolean = false,
    enabled: Boolean = true,
) {
    var visible by rememberSaveable { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = { new -> onValueChange(if (maxLength != null) new.take(maxLength) else new) },
        label = { Text(label) },
        modifier = modifier.fillMaxWidth(),
        isError = error != null,
        enabled = enabled,
        singleLine = singleLine,
        minLines = minLines,
        shape = RoundedCornerShape(14.dp),
        keyboardOptions = if (isPassword) keyboardOptions.copy(keyboardType = KeyboardType.Password) else keyboardOptions,
        visualTransformation = if (isPassword && !visible) PasswordVisualTransformation() else VisualTransformation.None,
        trailingIcon = if (isPassword) {
            {
                IconButton(onClick = { visible = !visible }) {
                    Icon(
                        if (visible) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                        contentDescription = stringResource(if (visible) R.string.ds_hide_password else R.string.ds_show_password),
                    )
                }
            }
        } else {
            null
        },
        supportingText = if (error != null || supporting != null || maxLength != null) {
            {
                if (error != null) {
                    Text(error)
                } else {
                    Row(Modifier.fillMaxWidth()) {
                        Text(supporting.orEmpty(), modifier = Modifier.weight(1f))
                        if (maxLength != null) Text("${value.length}/$maxLength")
                    }
                }
            }
        } else {
            null
        },
    )
}

@Composable
fun ToggleChip(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        enabled = enabled,
        label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        modifier = modifier.heightIn(min = 40.dp),
    )
}

@Composable
fun SwitchRow(title: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit, subtitle: String? = null, enabled: Boolean = true) {
    Row(
        Modifier
            .fillMaxWidth()
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange)
            .heightIn(min = 56.dp)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = StTheme.extra.textSecondary)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

@Composable
fun NavRow(title: String, onClick: () -> Unit, icon: ImageVector? = null, subtitle: String? = null, tint: Color = Color.Unspecified) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick, role = Role.Button)
            .heightIn(min = 56.dp)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = if (tint == Color.Unspecified) MaterialTheme.colorScheme.primary else tint)
            Spacer(Modifier.width(16.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = if (tint == Color.Unspecified) MaterialTheme.colorScheme.onSurface else tint)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = StTheme.extra.textSecondary)
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Avatars and images
// ---------------------------------------------------------------------------------------------

/** Uploaded avatar, or an initial on a brand color. Never a stock photo of a person. */
@Composable
fun Avatar(path: String?, name: String, modifier: Modifier = Modifier, size: Dp = 44.dp) {
    val resolver = LocalMediaResolver.current
    val url = remember(path) { path?.let { resolver.publicUrl(Buckets.AVATARS, it) } }
    val palette = listOf(StColors.BlueDeep, Color(0xFF007C93), Color(0xFF00805F), Color(0xFFB8431F), Color(0xFF5B4BB7))
    val bg = palette[(name.hashCode() and 0x7fffffff) % palette.size]
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .background(bg)
            .semantics { contentDescription = name },
        contentAlignment = Alignment.Center,
    ) {
        val initial = name.trim().firstOrNull()?.uppercase() ?: "?"
        Text(initial, color = Color.White, fontWeight = FontWeight.Bold, fontSize = (size.value * 0.42f).sp)
        if (url != null) {
            AsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
    }
}

/** Private Storage image loaded through a short-lived signed URL. */
@Composable
fun RemoteImage(bucket: String, path: String, contentDescription: String?, modifier: Modifier = Modifier, contentScale: ContentScale = ContentScale.Crop) {
    val resolver = LocalMediaResolver.current
    var url by remember(bucket, path) { mutableStateOf<String?>(null) }
    LaunchedEffect(bucket, path) {
        url = if (bucket == Buckets.AVATARS) resolver.publicUrl(bucket, path) else resolver.signedUrl(bucket, path)
    }
    Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
        val current = url
        if (current == null) {
            CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
        } else {
            AsyncImage(model = current, contentDescription = contentDescription, contentScale = contentScale, modifier = Modifier.fillMaxSize())
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Screen states
// ---------------------------------------------------------------------------------------------

@Composable
fun LoadingState(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable
fun EmptyState(
    title: String,
    modifier: Modifier = Modifier,
    message: String? = null,
    icon: ImageVector? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(72.dp)) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(34.dp))
                }
            }
            Spacer(Modifier.height(20.dp))
        }
        Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        if (message != null) {
            Spacer(Modifier.height(8.dp))
            Text(message, style = MaterialTheme.typography.bodyMedium, color = StTheme.extra.textSecondary, textAlign = TextAlign.Center)
        }
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(24.dp))
            StPrimaryButton(actionLabel, onAction)
        }
    }
}

@Composable
fun ErrorState(error: AppException, onRetry: (() -> Unit)?, modifier: Modifier = Modifier) {
    EmptyState(
        title = stringResource(R.string.ds_error_title),
        message = errorMessage(error),
        icon = Icons.Rounded.CloudOff,
        actionLabel = if (onRetry != null) stringResource(R.string.ds_retry) else null,
        onAction = onRetry,
        modifier = modifier,
    )
}

@Composable
fun <T> LoadStateContent(
    state: LoadState<T>,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable (T) -> Unit,
) {
    when (state) {
        LoadState.Loading -> LoadingState(modifier)
        is LoadState.Failure -> ErrorState(state.error, onRetry, modifier)
        is LoadState.Success -> content(state.data)
    }
}

@Composable
fun InfoBanner(text: String, modifier: Modifier = Modifier, isWarning: Boolean = false) {
    Surface(
        color = if (isWarning) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer,
        contentColor = if (isWarning) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer,
        shape = RoundedCornerShape(14.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(14.dp))
    }
}

@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier, trailing: @Composable (() -> Unit)? = null) {
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .defaultMinSize(minHeight = 32.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        trailing?.invoke()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StTopBar(title: String, onBack: (() -> Unit)? = null, actions: @Composable () -> Unit = {}) {
    CenterAlignedTopAppBar(
        title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleLarge) },
        navigationIcon = {
            if (onBack != null) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.ds_back))
                }
            }
        },
        actions = { actions() },
        colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = MaterialTheme.colorScheme.background),
    )
}

// ---------------------------------------------------------------------------------------------
// Dialogs
// ---------------------------------------------------------------------------------------------

@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    destructive: Boolean = false,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(confirmLabel, color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.ds_cancel)) } },
    )
}

@Composable
fun reportReasonLabel(reason: ReportReason): String = stringResource(
    when (reason) {
        ReportReason.Harassment -> R.string.ds_reason_harassment
        ReportReason.Hate -> R.string.ds_reason_hate
        ReportReason.SexualContent -> R.string.ds_reason_sexual
        ReportReason.MinorSafety -> R.string.ds_reason_minor_safety
        ReportReason.Violence -> R.string.ds_reason_violence
        ReportReason.SelfHarm -> R.string.ds_reason_self_harm
        ReportReason.Spam -> R.string.ds_reason_spam
        ReportReason.Scam -> R.string.ds_reason_scam
        ReportReason.Impersonation -> R.string.ds_reason_impersonation
        ReportReason.Underage -> R.string.ds_reason_underage
        ReportReason.Other -> R.string.ds_reason_other
    },
)

/** Report form: the reason is required, details are optional (nobody is forced to write anything). */
@Composable
fun ReportDialog(
    onSubmit: (ReportReason, String) -> Unit,
    onDismiss: () -> Unit,
    submitting: Boolean = false,
    alsoBlockLabel: String? = null,
    onAlsoBlockChange: ((Boolean) -> Unit)? = null,
    alsoBlock: Boolean = false,
) {
    var reason by rememberSaveable { mutableStateOf<ReportReason?>(null) }
    var details by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = { if (!submitting) onDismiss() },
        title = { Text(stringResource(R.string.ds_report_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                ReportReason.entries.forEach { r ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .selectable(selected = reason == r, role = Role.RadioButton, onClick = { reason = r })
                            .heightIn(min = 48.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = reason == r, onClick = null)
                        Spacer(Modifier.width(8.dp))
                        Text(reportReasonLabel(r), style = MaterialTheme.typography.bodyMedium)
                    }
                }
                Spacer(Modifier.height(8.dp))
                StTextField(details, { details = it }, stringResource(R.string.ds_report_details), singleLine = false, minLines = 2, maxLength = 1000)
                if (alsoBlockLabel != null && onAlsoBlockChange != null) {
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    SwitchRow(alsoBlockLabel, alsoBlock, onAlsoBlockChange)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { reason?.let { onSubmit(it, details) } }, enabled = reason != null && !submitting) {
                Text(stringResource(R.string.ds_report_send))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !submitting) { Text(stringResource(R.string.ds_cancel)) } },
    )
}
