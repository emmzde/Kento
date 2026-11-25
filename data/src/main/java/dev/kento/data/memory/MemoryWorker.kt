package dev.kento.data.memory

import android.content.Context
import androidx.work.*
import dev.kento.data.db.KentoDb
import dev.kento.data.net.*
import dev.kento.data.settings.SettingsStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

class MemoryWorker(ctx: Context, params: WorkerParameters): CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val store = SettingsStore(applicationContext)
        val dao = KentoDb.open(applicationContext).memory()
        val analysis = MemoryAnalysis(dao, (applicationContext as? ProviderHost)?.api ?: Provider())
        return try {
            // Replies that finish while this run works are not lost: a new schedule is dropped while this unique work
            // runs (KEEP), so the pending chats are read again until a pass commits nothing.
            var progressed = true
            while (progressed && !isStopped) {
                progressed = false
                for (chat in dao.pending()) {
                    // The one-off run and the periodic run are separate unique works and may overlap; a chat that the
                    // other run is reading is left to it.
                    if (!analysing.add(chat)) continue
                    try {
                        while (!isStopped && store.profiles.first().let { book ->
                            book.memory.enabled && book.memory.background && book.active.background().isNotBlank() &&
                                analysis.process(chat, book.active)
                        }) progressed = true // Settings are read again between committed batches.
                    } finally { analysing.remove(chat) }
                }
            }
            Result.success()
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            if (runAttemptCount < 3 && (e !is ProviderIssue || e.status !in listOf(400,401,402,403,404,422))) Result.retry()
            else Result.failure(workDataOf("issue" to friendlyError(e)))
        }
    }
    companion object {
        /**
         * Chats being analysed in this process. Nothing else is locked: a chat reply, a picture and the analysis run
         * at the same time (Refuseless and OpenUncensored take parallel requests). Whatever runs an analysis, the
         * cursor guards the facts: MemoryDao.commit checks and moves it in one transaction, so a second analysis of
         * the same messages writes nothing.
         */
        private val analysing = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

        /**
         * [delaySeconds] lets the user finish a burst of messages first, so several replies cost one request; tests
         * pass 0. Nothing waits for it: the chat neither holds nor takes a lock, and the
         * messages themselves are in the chat's history meanwhile.
         */
        fun schedule(context: Context, delaySeconds: Long = 30) {
            val manager = WorkManager.getInstance(context)
            val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
            val task = OneTimeWorkRequestBuilder<MemoryWorker>().setConstraints(constraints)
                .setInitialDelay(delaySeconds, TimeUnit.SECONDS).build()
            manager.enqueueUniqueWork("kento-memory", ExistingWorkPolicy.KEEP, task)
            val periodic = PeriodicWorkRequestBuilder<MemoryWorker>(6, TimeUnit.HOURS).setInitialDelay(6, TimeUnit.HOURS).setConstraints(constraints).build()
            manager.enqueueUniquePeriodicWork("kento-memory-periodic", ExistingPeriodicWorkPolicy.KEEP, periodic)
        }
    }
}
