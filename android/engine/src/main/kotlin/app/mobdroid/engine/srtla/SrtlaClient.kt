package app.mobdroid.engine.srtla

import app.mobdroid.engine.net.LinkType
import app.mobdroid.engine.net.NetworkLink
import app.mobdroid.engine.net.NetworkLinkProvider
import app.mobdroid.engine.srt.SrtEngine
import app.mobdroid.engine.srt.SrtTransport
import app.mobdroid.engine.srt.SrtTransportListener
import java.net.InetSocketAddress
import java.util.concurrent.ScheduledFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

private enum class Phase { Idle, Probing, CreatingGroup, Registering, Running, Closed }

private const val HOUSEKEEPING_INTERVAL_MS = 1000L

internal class SrtlaClient(
    private val engine: SrtEngine,
    private val server: InetSocketAddress,
    private val provider: NetworkLinkProvider,
    private var priorities: SrtlaPriorities = SrtlaPriorities(),
    private val nanoTime: () -> Long = System::nanoTime,
    private val connectTimeoutMs: Long = 6000,
    private val housekeepingEnabled: Boolean = true,
) : SrtTransport, SrtlaLinkHost {
    override var listener: SrtTransportListener? = null
    private var phase = Phase.Idle
    private val links = ArrayList<SrtlaLink>()
    private var group: ByteArray? = null
    private var scope: CoroutineScope? = null
    private var housekeepingTimer: ScheduledFuture<*>? = null
    private var connectTimer: ScheduledFuture<*>? = null

    @Volatile private var statsSnapshot: List<SrtlaLinkStats> = emptyList()

    override val groupId: ByteArray? get() = group
    override val isProbing: Boolean get() = phase == Phase.Probing

    fun linkStats(): List<SrtlaLinkStats> = statsSnapshot

    override fun start() {
        if (phase != Phase.Idle) {
            return
        }
        phase = Phase.Probing
        connectTimer = engine.schedule(connectTimeoutMs * 1_000_000, Runnable { onConnectTimeout() })
        if (housekeepingEnabled) {
            housekeepingTimer = engine.scheduleWithFixedDelay(HOUSEKEEPING_INTERVAL_MS, Runnable {
                housekeeping(nanoTime())
            })
        }
        scope = CoroutineScope(SupervisorJob() + engine.dispatcher).also {
            it.launch {
                provider.links.collect { current ->
                    try {
                        applyLinks(current)
                    } catch (error: Exception) {
                        listener?.onError("SRTLA link update failed: ${error.message}")
                    }
                }
            }
        }
    }

    override fun send(packet: ByteArray, length: Int) {
        val now = nanoTime()
        val link = select() ?: return
        if (!link.sendSrt(packet, length, now)) {
            select()?.sendSrt(packet, length, now)
        }
    }

    override fun close() {
        if (phase == Phase.Closed) {
            return
        }
        phase = Phase.Closed
        scope?.cancel()
        scope = null
        housekeepingTimer?.cancel(false)
        connectTimer?.cancel(false)
        for (link in links) {
            link.stop()
        }
        links.clear()
        statsSnapshot = emptyList()
    }

    fun setPriorities(newPriorities: SrtlaPriorities) {
        priorities = newPriorities
        for (link in links) {
            link.priority = newPriorities.priorityOf(link.networkLink.type)
        }
        refreshStats()
    }

    fun housekeeping(now: Long) {
        if (phase == Phase.Closed) {
            return
        }
        for (index in links.indices) {
            links[index].housekeeping(now)
        }
        refreshStats()
    }

    fun applyLinks(current: List<NetworkLink>) {
        if (phase == Phase.Closed) {
            return
        }
        val now = nanoTime()
        val iterator = links.iterator()
        while (iterator.hasNext()) {
            val link = iterator.next()
            val network = current.firstOrNull { it.id == link.id }
            if (network == null || network !== link.networkLink) {
                link.stop()
                iterator.remove()
            }
        }
        for (network in current) {
            if (links.none { it.id == network.id }) {
                val link = SrtlaLink(network, server, engine, this, nanoTime, now)
                link.priority = priorities.priorityOf(network.type)
                links.add(link)
                link.start(now)
            }
        }
        links.sortBy { rank(it.networkLink.type) }
        refreshStats()
    }

    override fun onLinkReady(link: SrtlaLink, now: Long) {
        val id = group
        if (id != null) {
            link.register(id, now)
        } else if (phase == Phase.Probing) {
            link.probe(now)
        }
        refreshStats()
    }

    override fun onLinkNoGroup(link: SrtlaLink, now: Long) {
        if (phase != Phase.Probing) {
            return
        }
        link.createGroup(now)
        phase = Phase.CreatingGroup
    }

    override fun onLinkGroupId(link: SrtlaLink, groupId: ByteArray, now: Long) {
        if (phase != Phase.CreatingGroup) {
            return
        }
        group = groupId
        phase = Phase.Registering
        for (index in links.indices) {
            links[index].register(groupId, now)
        }
    }

    override fun onLinkRegistered(link: SrtlaLink, now: Long) {
        if (phase == Phase.Registering) {
            phase = Phase.Running
            connectTimer?.cancel(false)
            listener?.onReady()
        }
        refreshStats()
    }

    override fun onLinkSrtPacket(buffer: ByteArray, length: Int) {
        if (phase == Phase.Running) {
            listener?.onPacket(buffer, length)
        }
    }

    override fun onLinkSrtAck(sequenceNumber: Int) {
        for (index in links.indices) {
            links[index].window.onSrtAck(sequenceNumber)
        }
    }

    override fun onLinkSrtNak(sequenceNumber: Int) {
        for (index in links.indices) {
            links[index].window.onNak(sequenceNumber)
        }
    }

    override fun onLinkSrtlaAck(sequenceNumber: Int) {
        for (index in links.indices) {
            links[index].window.onSrtlaAck(sequenceNumber)
        }
    }

    private fun select(): SrtlaLink? {
        var selected: SrtlaLink? = null
        var selectedScore = -1
        for (index in links.indices) {
            val link = links[index]
            val score = link.score()
            if (score > selectedScore) {
                selected = link
                selectedScore = score
            }
        }
        return selected
    }

    private fun onConnectTimeout() {
        if (phase != Phase.Running && phase != Phase.Closed) {
            listener?.onError("SRTLA registration timed out")
        }
    }

    fun refreshStats() {
        statsSnapshot = links.map { it.stats() }
    }

    private fun rank(type: LinkType): Int = when (type) {
        LinkType.CELLULAR -> 0
        LinkType.WIFI -> 1
        else -> 2
    }
}
