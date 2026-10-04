package com.farrow.app.ui.chats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.farrow.app.domain.model.Conversation
import com.farrow.app.domain.model.TaskStatus
import com.farrow.app.domain.repository.ApiKeyRepository
import com.farrow.app.domain.repository.QuotaRepository
import com.farrow.app.domain.repository.SettingsRepository
import com.farrow.app.domain.repository.TaskRepository
import com.farrow.app.domain.usecase.ObserveConversationsUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import javax.inject.Inject


@HiltViewModel
class ChatsViewModel @Inject constructor(
    observeConversations: ObserveConversationsUseCase,
    tasks: TaskRepository,
    quota: QuotaRepository,
    settings: SettingsRepository,
    keys: ApiKeyRepository,
    private val limiter: com.farrow.app.data.network.ClientRateLimiter,
    private val cooldowns: com.farrow.app.data.network.CooldownTracker,
    private val kilo: com.farrow.app.data.network.KiloProvider,
    private val kiloUsage: com.farrow.app.data.network.KiloUsage,
    private val client: com.farrow.app.data.network.OpenRouterClient,
    updater: com.farrow.app.data.update.AppUpdater,
) : ViewModel() {
    /** Dot on the gear: a newer Farrow release exists. */
    val updateAvailable = updater.updateAvailable
    val query = MutableStateFlow("")

    val conversations: StateFlow<List<Conversation>> = observeConversations(query)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** "Stories": running / paused / rate-limited / queued tasks plus failures from the last 24h. */
    val stories: StateFlow<List<Conversation>> = tasks.observeConversations().map { list ->
        val dayAgo = System.currentTimeMillis() - 24 * 3600_000L
        list.filter { it.task.status.isActive || (it.task.status == TaskStatus.FAILED && it.task.updatedAt > dayAgo) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val tick = flow { while (true) { emit(System.currentTimeMillis()); kotlinx.coroutines.delay(5_000) } }

    /** Quota dot (OpenRouter only; null = hidden: active model is Kilo, paid, or no key). Re-evaluated live on fallback. */
    val quotaDot: StateFlow<QuotaDot?> = combine(quota.quota, settings.limits, settings.modelPriority, keys.keys,
        combine(tick, client.lastModel) { now, last -> now to last }) { q, l, models, k, (now, last) ->
        val available: (String) -> Boolean = { m ->
            if (com.farrow.app.data.network.ModelIds.isKilo(m)) kiloUsage.counter.canUse(now) && kilo.cooldownUntil(m) <= now
            else k.any { key -> cooldowns.keyUntil(key.id) <= now && cooldowns.modelUntil(key.id, m) <= now }
        }
        val active = QuotaIndicator.activeModel(models, available, last)
        val orModels = models.filter(QuotaIndicator::isOpenRouterFree)
        val orLimit = q?.limit ?: l.requestsPerDay
        val orRemaining = q?.remaining ?: k.firstOrNull()?.let { (l.requestsPerDay - limiter.usedToday(it.id)).coerceAtLeast(0) }
        val orRateLimited = k.isNotEmpty() && orModels.isNotEmpty() && k.all { key ->
            cooldowns.keyUntil(key.id) > now || orModels.all { m -> cooldowns.modelUntil(key.id, m) > now }
        }
        QuotaIndicator.compute(active, k.isNotEmpty(), orRemaining, orLimit, q?.estimated == true, orRateLimited, now)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /** True when chats can run: an OpenRouter key, or a keyless Kilo model in the priority list. */
    val hasKeys: StateFlow<Boolean> = combine(keys.keys, settings.modelPriority) { k, m ->
        k.isNotEmpty() || m.any(com.farrow.app.data.network.ModelIds::isKilo)
    }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)
}
