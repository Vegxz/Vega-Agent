package com.vepro.code

import java.net.HttpURLConnection
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Cooperative + active cancellation shared by the service, model client and tools.
 * Listeners are run exactly once and may disconnect sockets or interrupt waits.
 */
class CancellationToken {

    /** Handle returned by [onCancel]; closing it deregisters the listener. */
    fun interface Registration {
        fun close()
    }

    class CancelledException : Exception("cancelled by user") {
        companion object {
            private const val serialVersionUID = 1L
        }
    }

    private val cancelled = AtomicBoolean(false)

    /** java.lang.Object (not Any) so wait/notifyAll are available. */
    private val lock = java.lang.Object()
    private val listeners = mutableListOf<Runnable>()

    val isCancelled: Boolean
        get() = cancelled.get()

    fun cancel(): Boolean {
        if (!cancelled.compareAndSet(false, true)) {
            return false
        }
        val pending: List<Runnable>
        synchronized(lock) {
            pending = listeners.toList()
            listeners.clear()
            lock.notifyAll()
        }
        for (listener in pending) {
            runAsync(listener)
        }
        return true
    }

    @Throws(CancelledException::class)
    fun throwIfCancelled() {
        if (isCancelled) {
            throw CancelledException()
        }
    }

    /** Registers an action and runs it immediately if cancellation already won. */
    fun onCancel(listener: Runnable?): Registration {
        if (listener == null) {
            return Registration { }
        }
        var runNow = false
        synchronized(lock) {
            if (cancelled.get()) {
                runNow = true
            } else {
                listeners.add(listener)
            }
        }
        if (runNow) {
            runAsync(listener)
        }
        val closed = AtomicBoolean(false)
        return Registration {
            if (closed.compareAndSet(false, true)) {
                synchronized(lock) {
                    listeners.remove(listener)
                }
            }
        }
    }

    /**
     * Makes a HttpURLConnection actively cancellable. Disconnecting and
     * interrupting its owner unblocks DNS/connect/getResponseCode/readLine on
     * Android implementations much sooner than waiting for the read timeout.
     */
    fun watchConnection(connection: HttpURLConnection): Registration {
        val owner = Thread.currentThread()
        return onCancel {
            try {
                connection.disconnect()
            } catch (ignored: Exception) {
            }
            try {
                owner.interrupt()
            } catch (ignored: Exception) {
            }
        }
    }

    /** Cancellable replacement for Thread.sleep. Returns false when cancelled. */
    fun sleep(milliseconds: Long): Boolean {
        if (milliseconds <= 0) {
            return !isCancelled
        }
        val deadline = System.currentTimeMillis() + milliseconds
        synchronized(lock) {
            while (!cancelled.get()) {
                val remaining = deadline - System.currentTimeMillis()
                if (remaining <= 0) {
                    return true
                }
                try {
                    lock.wait(minOf(remaining, 1000L))
                } catch (ignored: InterruptedException) {
                    if (cancelled.get()) {
                        return false
                    }
                }
            }
        }
        return false
    }

    private companion object {
        fun runAsync(listener: Runnable) {
            val cleanup = Thread({ runQuietly(listener) }, "vepro-cancel-cleanup")
            cleanup.isDaemon = true
            cleanup.start()
        }

        fun runQuietly(listener: Runnable) {
            try {
                listener.run()
            } catch (ignored: Throwable) {
            }
        }
    }
}
