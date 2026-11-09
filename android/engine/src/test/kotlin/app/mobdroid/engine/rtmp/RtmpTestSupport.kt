package app.mobdroid.engine.rtmp

import app.mobdroid.engine.net.PublisherListener
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class RecordingListener : PublisherListener {
    val connected = CountDownLatch(1)
    val disconnected = CountDownLatch(1)
    val reasons = CopyOnWriteArrayList<String>()

    override fun onConnected() {
        connected.countDown()
    }

    override fun onDisconnected(reason: String) {
        reasons.add(reason)
        disconnected.countDown()
    }

    fun awaitConnected(seconds: Long = 15): Boolean = connected.await(seconds, TimeUnit.SECONDS)

    fun awaitDisconnected(seconds: Long = 15): Boolean = disconnected.await(seconds, TimeUnit.SECONDS)
}

fun waitUntil(timeoutSeconds: Long = 15, condition: () -> Boolean): Boolean {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds)
    while (System.nanoTime() < deadline) {
        if (condition()) {
            return true
        }
        Thread.sleep(10)
    }
    return condition()
}
