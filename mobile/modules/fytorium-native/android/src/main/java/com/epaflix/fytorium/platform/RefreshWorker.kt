package com.epaflix.fytorium.platform

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.facebook.react.ReactApplication
import com.facebook.react.ReactInstanceEventListener
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.ReactContext
import com.facebook.react.bridge.UiThreadUtil
import com.facebook.react.jstasks.HeadlessJsTaskConfig
import com.facebook.react.jstasks.HeadlessJsTaskContext
import com.facebook.react.jstasks.HeadlessJsTaskEventListener
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

class RefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result =
        if (withTimeoutOrNull(TASK_TIMEOUT_MS + 5_000) { runTask(applicationContext) } == true) Result.success() else Result.failure()

    companion object {
        const val TASK = "FytoriumRefresh"
        const val PUSH_WORK = "fytorium-push-refresh"
        const val PERIODIC_WORK = "fytorium-care-refresh"
        private const val TASK_TIMEOUT_MS = 60_000L
        private val connected = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED)

        fun enqueueOnce(context: Context) = WorkManager.getInstance(context).enqueueUniqueWork(PUSH_WORK, ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<RefreshWorker>().setConstraints(connected.build()).build())

        fun schedulePeriodic(context: Context) = WorkManager.getInstance(context).enqueueUniquePeriodicWork(PERIODIC_WORK, ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<RefreshWorker>(6, TimeUnit.HOURS).setInitialDelay(6, TimeUnit.HOURS)
                .setConstraints(connected.setRequiresBatteryNotLow(true).build()).build())

        fun cancel(context: Context) = WorkManager.getInstance(context).run { cancelUniqueWork(PUSH_WORK); cancelUniqueWork(PERIODIC_WORK) }

        private suspend fun reactContext(context: Context): ReactContext = suspendCancellableCoroutine { done ->
            UiThreadUtil.runOnUiThread {
                val host = (context.applicationContext as ReactApplication).reactHost!!
                host.currentReactContext?.let { done.resume(it); return@runOnUiThread }
                host.addReactInstanceEventListener(object : ReactInstanceEventListener {
                    override fun onReactContextInitialized(context: ReactContext) {
                        host.removeReactInstanceEventListener(this)
                        if (done.isActive) done.resume(context)
                    }
                })
                host.start()
            }
        }

        private suspend fun runTask(context: Context): Boolean {
            val react = reactContext(context)
            return suspendCancellableCoroutine { done ->
                UiThreadUtil.runOnUiThread {
                    val tasks = HeadlessJsTaskContext.getInstance(react)
                    var id = -1
                    tasks.addTaskEventListener(object : HeadlessJsTaskEventListener {
                        override fun onHeadlessJsTaskStart(taskId: Int) = Unit
                        override fun onHeadlessJsTaskFinish(taskId: Int) {
                            if (taskId != id) return
                            tasks.removeTaskEventListener(this)
                            if (done.isActive) done.resume(true)
                        }
                    })
                    id = tasks.startTask(HeadlessJsTaskConfig(TASK, Arguments.createMap(), TASK_TIMEOUT_MS, true))
                }
            }
        }
    }
}
