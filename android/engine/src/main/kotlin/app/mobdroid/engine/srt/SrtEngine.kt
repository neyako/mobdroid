package app.mobdroid.engine.srt

import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import java.util.concurrent.Future
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher

internal class SrtEngine(name: String, private val onUncaught: (Throwable) -> Unit) {
    @Volatile private var thread: Thread? = null

    private val executor = object : ScheduledThreadPoolExecutor(
        1,
        ThreadFactory { task -> Thread(task, name).also { it.isDaemon = true; thread = it } },
    ) {
        override fun afterExecute(task: Runnable, error: Throwable?) {
            super.afterExecute(task, error)
            val failure = error ?: failureOf(task)
            if (failure != null) {
                onUncaught(failure)
            }
        }
    }.also {
        it.removeOnCancelPolicy = true
        it.executeExistingDelayedTasksAfterShutdownPolicy = false
        it.continueExistingPeriodicTasksAfterShutdownPolicy = false
    }

    val dispatcher: CoroutineDispatcher by lazy(LazyThreadSafetyMode.NONE) { executor.asCoroutineDispatcher() }

    val isShutdown: Boolean get() = executor.isShutdown

    fun isEngineThread(): Boolean = Thread.currentThread() === thread

    fun execute(task: Runnable) {
        try {
            executor.execute(task)
        } catch (_: RejectedExecutionException) {
        }
    }

    fun schedule(delayNanos: Long, task: Runnable): ScheduledFuture<*>? = try {
        executor.schedule(task, maxOf(delayNanos, 0), TimeUnit.NANOSECONDS)
    } catch (_: RejectedExecutionException) {
        null
    }

    fun scheduleWithFixedDelay(periodMs: Long, task: Runnable): ScheduledFuture<*>? = try {
        executor.scheduleWithFixedDelay(task, periodMs, periodMs, TimeUnit.MILLISECONDS)
    } catch (_: RejectedExecutionException) {
        null
    }

    fun shutdown() {
        executor.shutdown()
    }

    fun awaitTermination(timeoutMs: Long): Boolean {
        if (isEngineThread()) {
            return false
        }
        val terminated = try {
            executor.awaitTermination(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        if (!terminated) {
            executor.shutdownNow()
        }
        return terminated
    }

    private fun failureOf(task: Runnable): Throwable? {
        if (task !is Future<*> || !task.isDone || task.isCancelled) {
            return null
        }
        return try {
            task.get()
            null
        } catch (error: ExecutionException) {
            error.cause ?: error
        } catch (_: CancellationException) {
            null
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            null
        }
    }
}
