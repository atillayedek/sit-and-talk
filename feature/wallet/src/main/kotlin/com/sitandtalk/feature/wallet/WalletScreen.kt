package com.sitandtalk.feature.wallet

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.text.format.DateUtils
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.sitandtalk.core.data.PurchaseEvent
import com.sitandtalk.core.data.StoreProduct
import com.sitandtalk.core.data.WalletRepository
import com.sitandtalk.core.designsystem.InfoBanner
import com.sitandtalk.core.designsystem.LoadingState
import com.sitandtalk.core.designsystem.SectionHeader
import com.sitandtalk.core.designsystem.StPrimaryButton
import com.sitandtalk.core.designsystem.StSecondaryButton
import com.sitandtalk.core.designsystem.StTheme
import com.sitandtalk.core.designsystem.StTopBar
import com.sitandtalk.core.designsystem.errorMessage
import com.sitandtalk.core.model.AppException
import com.sitandtalk.core.model.Entitlements
import com.sitandtalk.core.model.ServerTime
import com.sitandtalk.core.model.WalletTx
import com.sitandtalk.core.network.toAppException
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class WalletState(
    val entitlements: Entitlements? = null,
    val products: List<StoreProduct>? = null,
    val history: List<WalletTx> = emptyList(),
    val loading: Boolean = true,
    val storeError: AppException? = null,
    val error: AppException? = null,
)

@HiltViewModel
class WalletViewModel @Inject constructor(private val wallet: WalletRepository) : ViewModel() {
    private val _state = MutableStateFlow(WalletState())
    val state: StateFlow<WalletState> = _state.asStateFlow()
    val events: StateFlow<PurchaseEvent?> = wallet.events

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true) }
            try {
                val ent = wallet.refresh()
                _state.update { it.copy(entitlements = ent, history = runCatching { wallet.history() }.getOrDefault(emptyList())) }
                try {
                    _state.update { it.copy(products = wallet.storeProducts(), storeError = null) }
                } catch (e: Exception) {
                    _state.update { it.copy(products = emptyList(), storeError = e.toAppException()) }
                }
            } catch (e: Exception) {
                _state.update { it.copy(error = e.toAppException()) }
            } finally {
                _state.update { it.copy(loading = false) }
            }
        }
    }

    fun buy(activity: Activity, product: StoreProduct) = wallet.launchPurchase(activity, product)
    fun restore() = wallet.restorePurchases()
    fun consumeEvent() {
        wallet.consumeEvent()
        refresh()
    }
    fun consumeError() = _state.update { it.copy(error = null) }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

@Composable
private fun txLabel(kind: String): String = stringResource(
    when (kind) {
        "purchase" -> R.string.wallet_tx_purchase
        "gift_sent" -> R.string.wallet_tx_gift_sent
        "refund_reversal" -> R.string.wallet_tx_refund_reversal
        else -> R.string.wallet_tx_admin
    },
)

@Composable
fun WalletScreen(onBack: () -> Unit, viewModel: WalletViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val event by viewModel.events.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    val eventText = when (val e = event) {
        PurchaseEvent.Pending -> stringResource(R.string.wallet_pending)
        PurchaseEvent.Cancelled -> stringResource(R.string.wallet_cancelled)
        is PurchaseEvent.Verified -> stringResource(R.string.wallet_verified)
        is PurchaseEvent.Failed -> errorMessage(e.error)
        null -> null
    }
    val errorText = state.error?.let { errorMessage(it) }
    LaunchedEffect(eventText) { if (eventText != null) { snackbar.showSnackbar(eventText); viewModel.consumeEvent() } }
    LaunchedEffect(errorText) { if (errorText != null) { snackbar.showSnackbar(errorText); viewModel.consumeError() } }

    Scaffold(topBar = { StTopBar(stringResource(R.string.wallet_title), onBack = onBack) }, snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        val ent = state.entitlements
        if (ent == null) {
            LoadingState(Modifier.padding(padding))
            return@Scaffold
        }
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
            Card(shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp)) {
                    Text(stringResource(R.string.wallet_balance), style = MaterialTheme.typography.labelLarge)
                    Text(stringResource(R.string.wallet_coins, ent.balance.toInt()), style = MaterialTheme.typography.headlineMedium)
                    Text(stringResource(R.string.wallet_coins_note), style = MaterialTheme.typography.bodySmall, color = StTheme.extra.textSecondary)
                }
            }
            Spacer(Modifier.height(12.dp))
            Card(shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp)) {
                    Text(stringResource(if (ent.isPremium) R.string.wallet_premium_active else R.string.wallet_premium), style = MaterialTheme.typography.titleLarge)
                    ent.subscription?.let { sub ->
                        Text(stringResource(R.string.wallet_premium_status, sub.status))
                        sub.expiresAt?.let { Text(stringResource(R.string.wallet_premium_until, DateUtils.formatDateTime(context, ServerTime.parse(it)?.toEpochMilli() ?: 0, DateUtils.FORMAT_SHOW_DATE))) }
                    }
                    Text(stringResource(R.string.wallet_premium_benefits), style = MaterialTheme.typography.bodySmall, color = StTheme.extra.textSecondary)
                }
            }
            SectionHeader(stringResource(R.string.wallet_products))
            val products = state.products
            when {
                products == null -> LoadingState(Modifier.height(80.dp))
                state.storeError?.code == "billing_unavailable" -> InfoBanner(stringResource(R.string.wallet_billing_unavailable), isWarning = true)
                products.isEmpty() -> InfoBanner(stringResource(R.string.wallet_store_unconfigured))
                else -> products.forEach { p ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(p.title, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                if (p.kind == "subscription") stringResource(R.string.wallet_subscription) else stringResource(R.string.wallet_coins, p.coins ?: 0),
                                style = MaterialTheme.typography.bodySmall, color = StTheme.extra.textSecondary,
                            )
                        }
                        // Price text comes from Google Play, never hard-coded.
                        StPrimaryButton(p.price, { context.findActivity()?.let { viewModel.buy(it, p) } })
                    }
                }
            }
            StSecondaryButton(stringResource(R.string.wallet_restore), viewModel::restore, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp))
            SectionHeader(stringResource(R.string.wallet_history))
            if (state.history.isEmpty()) Text(stringResource(R.string.wallet_history_empty), color = StTheme.extra.textSecondary)
            state.history.forEach { tx ->
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(txLabel(tx.kind))
                        Text(DateUtils.getRelativeTimeSpanString(ServerTime.parse(tx.createdAt)?.toEpochMilli() ?: 0).toString(),
                            style = MaterialTheme.typography.bodySmall, color = StTheme.extra.textSecondary)
                    }
                    Text((if (tx.amount > 0) "+" else "") + tx.amount, style = MaterialTheme.typography.titleMedium)
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
