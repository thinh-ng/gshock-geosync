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
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

private const val TAG = "GShockGeo"

/** Runs the watch sync; a tide upload takes about 30 s, longer than a broadcast may run. Retries every 15 min. */
class SyncJob : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var work: Job? = null

    override fun onStartJob(params: JobParameters): Boolean {
        synchronized(lock) { running = true }
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
            jobFinished(params, !ok)
            // A failed sync is retried by the backoff, which also covers any request queued meanwhile.
            val again = synchronized(lock) {
                running = false
                val queued = rerun
                rerun = false
                queued && ok
            }
            if (again) schedule(this@SyncJob)
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        synchronized(lock) { running = false }
        work?.cancel()
        return true
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val JOB_ID = 1
        private const val RETRY_MILLIS = 15 * 60_000L
        private val lock = Any()
        private var running = false
        private var rerun = false

        /**
         * Scheduling under a running job's id would stop it mid-upload, so a request that arrives
         * during a sync is queued until that sync finishes.
         */
        fun schedule(context: Context, delayMillis: Long = 0) {
            synchronized(lock) {
                if (running) {
                    rerun = true
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
}
