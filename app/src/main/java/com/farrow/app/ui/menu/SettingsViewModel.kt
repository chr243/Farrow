package com.farrow.app.ui.menu

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.farrow.app.chathead.ChatHeadController
import com.farrow.app.data.network.RateLimitHeaderStore
import com.farrow.app.domain.model.ChatHeadMode
import com.farrow.app.domain.model.LimitSettings
import com.farrow.app.domain.repository.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val keys: ApiKeyRepository,
    private val settings: SettingsRepository,
    private val quota: QuotaRepository,
    private val rateLimits: RateLimitRepository,
    headerStore: RateLimitHeaderStore,
    val chatHeads: ChatHeadController,
    val appPrefs: com.farrow.app.data.prefs.AppPrefs,
    private val kiloUsage: com.farrow.app.data.network.KiloUsage,
) : ViewModel() {
    /** Kilo requests in the last hour (in-app counter, refreshed every 5 s while visible). */
    val kiloUsedThisHour: StateFlow<Int> = kotlinx.coroutines.flow.flow {
        while (true) { emit(kiloUsage.counter.used()); kotlinx.coroutines.delay(5_000) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val chatHeadMode = settings.chatHeadMode.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ChatHeadMode.AUTO)
    fun setChatHeadMode(mode: ChatHeadMode) = viewModelScope.launch { settings.setChatHeadMode(mode) }

    val apiKeys = keys.keys
    val models = settings.modelPriority.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val limits = settings.limits.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), LimitSettings())
    val quotaInfo = quota.quota.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    val lastHeaders = headerStore.latest
    val events = rateLimits.observeEvents().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val refreshing = MutableStateFlow(false)

    fun addKey(label: String, key: String) = viewModelScope.launch { keys.add(label, key) }
    fun removeKey(id: String) = viewModelScope.launch { keys.remove(id) }
    fun moveKey(id: String, delta: Int) = viewModelScope.launch { keys.move(id, delta) }
    fun setPrimary(id: String) = viewModelScope.launch { keys.setPrimary(id) }
    fun renameKey(id: String, label: String) = viewModelScope.launch { keys.rename(id, label) }

    fun addModel(model: String) = viewModelScope.launch {
        val m = model.trim()
        if (m.isNotEmpty()) settings.setModelPriority(models.value + m)
    }
    fun removeModel(index: Int) = viewModelScope.launch {
        settings.setModelPriority(models.value.toMutableList().apply { if (index in indices) removeAt(index) })
    }
    fun moveModel(index: Int, delta: Int) = viewModelScope.launch {
        val list = models.value.toMutableList()
        val j = index + delta
        if (index in list.indices && j in list.indices) { list.add(j, list.removeAt(index)); settings.setModelPriority(list) }
    }
    fun resetModels() = viewModelScope.launch { settings.resetModelPriority() }

    fun saveLimits(l: LimitSettings) = viewModelScope.launch { settings.updateLimits { l } }

    fun refreshQuota() = viewModelScope.launch {
        refreshing.value = true
        try { quota.refresh() } finally { refreshing.value = false }
    }

    fun clearLog() = viewModelScope.launch { rateLimits.clear() }
}
