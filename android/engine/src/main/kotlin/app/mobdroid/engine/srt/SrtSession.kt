package app.mobdroid.engine.srt

import java.util.concurrent.ScheduledFuture

internal interface SrtSessionListener {
    fun onSessionConnected()

    fun onSessionDisconnected(reason: String)
}

internal class SrtSession(
    private val engine: SrtEngine,
    val transport: SrtTransport,
    config: SrtConfig,
    private val listener: SrtSessionListener,
    private val nanoTime: () -> Long = System::nanoTime,
) : SrtTransportListener, SrtSenderListener {
    private val sender = SrtSender(config, SrtOutput { packet, length -> transport.send(packet, length) }, this)
    private val timerTask = Runnable { onTimer() }
    private var timer: ScheduledFuture<*>? = null
    private var timerDeadline = NO_DEADLINE
    private var closed = false

    fun counters(): SrtCounters = sender.counters()

    fun start() {
        transport.listener = this
        transport.start()
    }

    fun write(buffer: ByteArray, offset: Int, length: Int, containsAudio: Boolean) {
        sender.write(buffer, offset, length, containsAudio, nanoTime())
    }

    fun flush() {
        if (closed) {
            return
        }
        sender.flush(nanoTime())
        rearm()
    }

    fun stop() {
        if (closed) {
            return
        }
        closed = true
        timer?.cancel(false)
        timer = null
        sender.stop(nanoTime())
        transport.close()
    }

    override fun onReady() {
        if (closed) {
            return
        }
        sender.start(nanoTime())
        rearm()
    }

    override fun onPacket(buffer: ByteArray, length: Int) {
        if (closed) {
            return
        }
        sender.input(buffer, length, nanoTime())
        rearm()
    }

    override fun onError(reason: String) {
        close(reason)
    }

    override fun onSrtConnected() {
        listener.onSessionConnected()
    }

    override fun onSrtDisconnected(reason: String) {
        close(reason)
    }

    private fun close(reason: String) {
        if (closed) {
            return
        }
        closed = true
        timer?.cancel(false)
        timer = null
        transport.close()
        listener.onSessionDisconnected(reason)
    }

    private fun onTimer() {
        timer = null
        timerDeadline = NO_DEADLINE
        if (closed) {
            return
        }
        sender.onTimer(nanoTime())
        rearm()
    }

    private fun rearm() {
        if (closed) {
            return
        }
        val deadline = sender.nextDeadline()
        if (deadline == NO_DEADLINE || (timer != null && deadline >= timerDeadline)) {
            return
        }
        timer?.cancel(false)
        timerDeadline = deadline
        timer = engine.schedule(deadline - nanoTime(), timerTask)
    }
}
