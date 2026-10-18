package io.github.kriakiku.holtdroid.tunnel

import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

object Tunnel {
    const val TAG = "HoltdroidTunnel"

    @Volatile
    var status: Status = Status()
        private set

    @Volatile
    var traffic: (() -> Traffic)? = null

    private val listeners = CopyOnWriteArraySet<(Status) -> Unit>()

    fun snapshot(): Status = status.let { s -> traffic?.takeIf { s.state == State.RUNNING }?.let { s.copy(traffic = it()) } ?: s }

    fun update(next: Status) {
        status = next
        listeners.forEach { it(next) }
    }

    fun listen(listener: (Status) -> Unit) {
        listeners += listener
    }

    fun unlisten(listener: (Status) -> Unit) {
        listeners -= listener
    }

    fun await(timeoutMs: Long, done: (Status) -> Boolean): Status {
        val latch = CountDownLatch(1)
        val listener: (Status) -> Unit = { if (done(it)) latch.countDown() }
        listen(listener)
        try {
            if (!done(status)) latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        } finally {
            unlisten(listener)
        }
        return status
    }
}
