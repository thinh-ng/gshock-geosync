package com.thinh.gshockgeo

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "GShockGeo"

/** Runs the watch sync; a tide upload takes about 30 s, longer than a broadcast may run. Retries every 15 min. */
class SyncJob : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var work: Job? = null

    override fun onStartJob(params: JobParameters): Boolean {
        running.set(true)
        work = scope.launch {
            val ok = try {
                WatchSync.sync(this@SyncJob)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "sync failed: $e")
                false
            }
            Log.i(TAG, if (ok) "sync done" else "sync incomplete, retrying later")
            running.set(false)
            jobFinished(params, !ok)
            if (ok && rerun.getAndSet(false)) schedule(this@SyncJob)
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        running.set(false)
        work?.cancel()
        return true
    }

    companion object {
        private const val JOB_ID = 1
        private const val RETRY_MILLIS = 15 * 60_000L
        private val running = AtomicBoolean(false)
        private val rerun = AtomicBoolean(false)

        /**
         * Scheduling under a running job's id would stop it mid-upload, so a request that arrives
         * during a sync is queued until that sync finishes.
         */
        fun schedule(context: Context, delayMillis: Long = 0) {
            if (running.get()) {
                rerun.set(true)
                return
            }
            val job = JobInfo.Builder(JOB_ID, ComponentName(context, SyncJob::class.java))
                .setMinimumLatency(delayMillis)
                .setBackoffCriteria(RETRY_MILLIS, JobInfo.BACKOFF_POLICY_LINEAR)
                .setPersisted(true)
                .build()
            context.getSystemService(JobScheduler::class.java).schedule(job)
        }
    }
}
