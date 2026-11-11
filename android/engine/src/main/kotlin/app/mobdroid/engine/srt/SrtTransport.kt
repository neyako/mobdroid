package app.mobdroid.engine.srt

import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.PortUnreachableException

internal interface SrtTransportListener {
    fun onReady()

    fun onPacket(buffer: ByteArray, length: Int)

    fun onError(reason: String)
}

internal interface SrtTransport {
    var listener: SrtTransportListener?

    fun start()

    fun send(packet: ByteArray, length: Int)

    fun close()
}

internal class UdpSrtTransport(
    private val engine: SrtEngine,
    private val target: InetSocketAddress,
) : SrtTransport {
    override var listener: SrtTransportListener? = null
    @Volatile private var socket: DatagramSocket? = null
    private var reader: Thread? = null
    private val outgoing = DatagramPacket(ByteArray(0), 0)

    override fun start() {
        val opened = DatagramSocket()
        try {
            opened.connect(target)
        } catch (error: IOException) {
            opened.close()
            listener?.onError("Failed to open UDP socket: ${error.message}")
            return
        } catch (error: IllegalArgumentException) {
            opened.close()
            listener?.onError("Invalid SRT server address: ${error.message}")
            return
        }
        socket = opened
        reader = Thread({ readLoop(opened) }, "srt-udp-reader").also {
            it.isDaemon = true
            it.start()
        }
        listener?.onReady()
    }

    override fun send(packet: ByteArray, length: Int) {
        val current = socket ?: return
        outgoing.setData(packet, 0, length)
        try {
            current.send(outgoing)
        } catch (_: IOException) {
        }
    }

    override fun close() {
        val current = socket ?: return
        socket = null
        current.close()
        reader?.let {
            if (Thread.currentThread() !== it) {
                it.join(500)
            }
        }
        reader = null
    }

    private fun readLoop(opened: DatagramSocket) {
        val buffer = ByteArray(2048)
        val incoming = DatagramPacket(buffer, buffer.size)
        while (!opened.isClosed) {
            try {
                incoming.setData(buffer, 0, buffer.size)
                opened.receive(incoming)
            } catch (_: PortUnreachableException) {
                continue
            } catch (_: IOException) {
                if (opened.isClosed) {
                    return
                }
                Thread.sleep(50)
                continue
            }
            val copy = buffer.copyOf(incoming.length)
            engine.execute {
                if (socket === opened) {
                    listener?.onPacket(copy, copy.size)
                }
            }
        }
    }
}
