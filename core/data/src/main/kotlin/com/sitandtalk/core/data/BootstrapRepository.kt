package com.sitandtalk.core.data

import com.sitandtalk.core.model.AppConfig
import com.sitandtalk.core.model.Bootstrap
import com.sitandtalk.core.model.ServerTime
import com.sitandtalk.core.network.Api
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

/** Server-driven gate: minimum version, maintenance, feature flags, account state. */
@Singleton
class BootstrapRepository @Inject constructor(
    private val api: Api,
    private val config: AppConfig,
) {
    private val _current = MutableStateFlow<Bootstrap?>(null)
    val current: StateFlow<Bootstrap?> = _current.asStateFlow()

    suspend fun refresh(): Bootstrap {
        val result = api.rpc<Bootstrap>("app_bootstrap") { put("p_version_code", config.versionCode) }
        ServerTime.sync(result.serverNow)
        _current.value = result
        return result
    }

    fun flag(key: String): Boolean = _current.value?.flag(key) == true

    fun intSetting(key: String, default: Int): Int =
        _current.value?.settings?.get(key)?.toString()?.trim('"')?.toIntOrNull() ?: default
}
