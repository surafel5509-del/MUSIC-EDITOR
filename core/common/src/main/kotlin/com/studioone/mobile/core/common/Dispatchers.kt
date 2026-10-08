package com.studioone.mobile.core.common

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * Injectable dispatcher provider. Every repository/use-case takes one so tests
 * can swap in a StandardTestDispatcher (see core:data test fixtures).
 */
interface DispatcherProvider {
    val main: CoroutineDispatcher
    val io: CoroutineDispatcher
    val default: CoroutineDispatcher
    /**
     * Audio-file processing (peak extraction, decode) runs on a dedicated pool:
     * it is CPU-heavy but must never starve the UI or the sync worker.
     */
    val audio: CoroutineDispatcher
}

class DefaultDispatcherProvider : DispatcherProvider {
    override val main: CoroutineDispatcher get() = Dispatchers.Main.immediate
    override val io: CoroutineDispatcher get() = Dispatchers.IO
    override val default: CoroutineDispatcher get() = Dispatchers.Default

    // Audio render/analysis pool: half the cores, min 2 — leaves headroom for
    // the real-time audio thread which is scheduled by AAudio at SCHED_FIFO.
    override val audio: CoroutineDispatcher by lazy {
        val parallelism = (Runtime.getRuntime().availableProcessors() / 2).coerceAtLeast(2)
        kotlinx.coroutines.asCoroutineDispatcher(
            java.util.concurrent.Executors.newFixedThreadPool(parallelism) { r ->
                Thread(r, "s1-audio-pool").apply { priority = Thread.NORM_PRIORITY + 1 }
            },
        )
    }
}
