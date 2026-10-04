package com.farrow.app.data.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.*
import com.farrow.app.domain.repository.QuotaRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

/** Periodically polls GET /api/v1/key so the low-quota banner/notification stays fresh. */
@HiltWorker
class QuotaPollWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val quota: QuotaRepository,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        quota.refresh()
        return Result.success()
    }

    companion object {
        private const val NAME = "quota-poll"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<QuotaPollWorker>(30, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
