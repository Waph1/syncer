package io.github.waph1.syncer.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import io.github.waph1.syncer.appContainer
import io.github.waph1.syncer.settings.SyncType

class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val container = applicationContext.appContainer
        val reason = inputData.getString(KEY_REASON)
        val types = inputData.getNullableStringArray(KEY_TYPES)
            ?.mapNotNull { name -> SyncType.entries.firstOrNull { it.name == name } }
            ?.toSet()
            ?: SyncType.entries.toSet()
        try {
            container.engine.run(types, force = inputData.getBoolean(KEY_FORCE, false))
        } finally {
            val settings = container.settings.current
            when (reason) {
                REASON_OBSERVER -> types.forEach { container.scheduler.rearmObserver(it, settings) }
                // Self-healing: re-create observers that may have been dropped.
                REASON_PERIODIC -> container.scheduler.apply(settings)
            }
        }
        // Failures are reported in the status/notifications; retrying would only repeat them.
        return Result.success()
    }

    companion object {
        const val KEY_REASON = "reason"
        const val KEY_TYPES = "types"
        const val KEY_FORCE = "force"
        const val REASON_PERIODIC = "periodic"
        const val REASON_OBSERVER = "observer"
        const val REASON_MANUAL = "manual"
    }
}
