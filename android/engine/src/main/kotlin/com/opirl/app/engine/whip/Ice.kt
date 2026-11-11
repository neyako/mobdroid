package com.opirl.app.engine.whip

import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

internal enum class IceState { NEW, CHECKING, CONNECTED, FAILED }

private enum class PairState { WAITING, IN_PROGRESS, SUCCEEDED, FAILED }

private const val RETRANSMIT_NANOS = 500_000_000L
private const val PACING_NANOS = 20_000_000L
private const val MAX_ATTEMPTS = 16
private const val NOMINATION_GRACE_NANOS = 200_000_000L
private const val PENDING_LIFETIME_NANOS = 15_000_000_000L
private const val IDLE_NANOS = 1_000_000_000L
private const val HOST_TYPE_PREFERENCE = 126L
private const val PEER_REFLEXIVE_TYPE_PREFERENCE = 110L

internal fun candidatePriority(typePreference: Long, localPreference: Long, component: Int = 1): Long =
    (typePreference shl 24) + (localPreference shl 8) + (256 - component)

internal fun gatherLocalCandidates(addresses: List<InetAddress>, port: Int): List<LocalCandidate> =
    addresses.mapIndexed { index, address ->
        LocalCandidate(index + 1, candidatePriority(HOST_TYPE_PREFERENCE, 65535L - index), address, port)
    }

internal fun gatherLocalAddresses(serverHost: String?): List<InetAddress> {
    val result = LinkedHashSet<InetAddress>()
    if (serverHost != null) {
        try {
            DatagramSocket().use { probe ->
                probe.connect(InetAddress.getByName(serverHost), 9)
                result.add(probe.localAddress)
            }
        } catch (_: Exception) {
        }
    }
    try {
        for (network in NetworkInterface.getNetworkInterfaces().toList()) {
            if (!network.isUp || network.isLoopback) {
                continue
            }
            for (address in network.inetAddresses.toList()) {
                result.add(address)
            }
        }
    } catch (_: Exception) {
    }
    val usable = result.filter { !it.isAnyLocalAddress && !it.isLoopbackAddress && !it.isLinkLocalAddress && !it.isMulticastAddress }
    return usable.ifEmpty { listOf(InetAddress.getLoopbackAddress()) }.map { normalize(it) }
}

private fun normalize(address: InetAddress): InetAddress =
    if (address is Inet6Address && address.scopeId != 0 && !address.isLinkLocalAddress) {
        InetAddress.getByAddress(address.address)
    } else {
        address
    }

private class CandidatePair(val remote: InetSocketAddress, var remotePriority: Long, var localPriority: Long) {
    var priority = 0L
    var state = PairState.WAITING
    var lastSentNanos = 0L
    var attempts = 0
    var triggered = false
    var peerChecked = false
    var nominatedByPeer = false
    var validAt = 0L
    var transactionId: ByteArray? = null
    var nominationRequested = false

    fun recompute(controlling: Boolean) {
        val controllingPriority = if (controlling) localPriority else remotePriority
        val controlledPriority = if (controlling) remotePriority else localPriority
        priority = (minOf(controllingPriority, controlledPriority) shl 32) + 2 * maxOf(controllingPriority, controlledPriority) +
            (if (controllingPriority > controlledPriority) 1 else 0)
    }
}

private class PendingCheck(val pair: CandidatePair, val nominate: Boolean, val sentNanos: Long)

internal class IceAgent(
    private val socket: DatagramSocket,
    private val localUfrag: String,
    localPassword: String,
    private val localCandidates: List<LocalCandidate>,
    private val consentIntervalNanos: Long = 2_000_000_000L,
    private val consentTimeoutNanos: Long = 10_000_000_000L,
    controlling: Boolean = true,
    private val clock: () -> Long = System::nanoTime,
    private val random: SecureRandom = SecureRandom(),
) {
    private val lock = ReentrantLock()
    private val changed = lock.newCondition()
    private val localKey = localPassword.toByteArray(Charsets.UTF_8)
    private val tieBreaker = random.nextLong()
    private val pairs = ArrayList<CandidatePair>()
    private val pending = HashMap<ByteBuffer, PendingCheck>()
    private var controlling = controlling
    private var remoteUfrag: String? = null
    private var remoteKey: ByteArray? = null
    private var remoteLite = false
    private var selectedPair: CandidatePair? = null
    private var nominating: CandidatePair? = null
    private var lastPacedNanos = 0L
    private var consentSentNanos = 0L
    private var lastConsentNanos = 0L
    private var rtt = 0.0

    @Volatile var state = IceState.NEW
        private set

    @Volatile var failure: String? = null
        private set

    @Volatile var selectedRemote: InetSocketAddress? = null
        private set

    @Volatile private var knownRemotes: List<InetSocketAddress> = emptyList()

    val rttMs: Double
        get() = lock.withLock { rtt }

    fun isPeerAddress(address: InetSocketAddress): Boolean = knownRemotes.contains(address)

    fun setRemote(ufrag: String, password: String, candidates: List<IceCandidate>, lite: Boolean) {
        lock.withLock {
            remoteUfrag = ufrag
            remoteKey = password.toByteArray(Charsets.UTF_8)
            remoteLite = lite
            for (candidate in candidates) {
                if (pairFor(candidate.address) == null) {
                    addPair(candidate.address, candidate.priority)
                }
            }
            if (state == IceState.NEW) {
                state = IceState.CHECKING
            }
            changed.signalAll()
        }
    }

    fun onPacket(data: ByteArray, length: Int, from: InetSocketAddress) {
        val message = StunMessage.parse(data, 0, length) ?: return
        lock.withLock {
            when {
                message.isRequest -> handleRequest(message, from)
                message.isSuccess -> handleSuccess(message, from)
                message.isError -> handleError(message)
            }
            changed.signalAll()
        }
    }

    fun tick(): Long =
        lock.withLock {
            val now = clock()
            expirePending(now)
            when (state) {
                IceState.CHECKING -> if (remoteUfrag == null) IDLE_NANOS else checkingTick(now)
                IceState.CONNECTED -> connectedTick(now)
                else -> IDLE_NANOS
            }
        }

    fun awaitChange(maxNanos: Long) {
        if (maxNanos <= 0) {
            return
        }
        lock.withLock {
            changed.awaitNanos(maxNanos)
        }
    }

    fun wake() {
        lock.withLock { changed.signalAll() }
    }

    fun fail(reason: String) {
        lock.withLock {
            if (state != IceState.FAILED) {
                state = IceState.FAILED
                failure = reason
            }
            changed.signalAll()
        }
    }

    private fun checkingTick(now: Long): Long {
        var wake = IDLE_NANOS
        val paced = now - lastPacedNanos >= PACING_NANOS
        val triggered = pairs.firstOrNull { it.triggered && it.state != PairState.FAILED }
        val nominee = nominating
        if (nominee != null) {
            wake = minOf(wake, retransmitIn(nominee, now))
        } else if (controlling) {
            val ready = pairs.firstOrNull { it.state == PairState.SUCCEEDED && nominationReady(it, now) }
            if (ready != null) {
                nominating = ready
                ready.nominationRequested = true
                sendCheck(ready, now, newTransaction = true, nominate = true)
                lastPacedNanos = now
                return PACING_NANOS
            }
            for (pair in pairs) {
                if (pair.state == PairState.SUCCEEDED && !nominationReady(pair, now)) {
                    wake = minOf(wake, pair.validAt + NOMINATION_GRACE_NANOS - now)
                }
            }
        }
        if (nominee != null && retransmitIn(nominee, now) <= 0 && paced) {
            if (nominee.attempts >= MAX_ATTEMPTS) {
                nominee.state = PairState.FAILED
                nominating = null
            } else {
                sendCheck(nominee, now, newTransaction = false, nominate = true)
                lastPacedNanos = now
            }
            return PACING_NANOS
        }
        if (triggered != null && triggered.state != PairState.SUCCEEDED && paced) {
            triggered.triggered = false
            sendCheck(triggered, now, newTransaction = true, nominate = false)
            lastPacedNanos = now
            return PACING_NANOS
        }
        var work = false
        for (pair in pairs) {
            when (pair.state) {
                PairState.WAITING -> {
                    if (paced) {
                        sendCheck(pair, now, newTransaction = true, nominate = false)
                        lastPacedNanos = now
                        return PACING_NANOS
                    }
                    work = true
                }
                PairState.IN_PROGRESS -> {
                    val due = retransmitIn(pair, now)
                    if (due <= 0) {
                        if (pair.attempts >= MAX_ATTEMPTS) {
                            pair.state = PairState.FAILED
                        } else if (paced) {
                            sendCheck(pair, now, newTransaction = false, nominate = false)
                            lastPacedNanos = now
                            return PACING_NANOS
                        } else {
                            work = true
                        }
                    } else {
                        wake = minOf(wake, due)
                    }
                }
                else -> {}
            }
        }
        if (pairs.isNotEmpty() && pairs.all { it.state == PairState.FAILED }) {
            state = IceState.FAILED
            failure = "No ICE candidate pair worked"
            changed.signalAll()
        }
        return if (work) PACING_NANOS else maxOf(wake, 0L)
    }

    private fun connectedTick(now: Long): Long {
        val pair = selectedPair ?: return IDLE_NANOS
        if (now - lastConsentNanos > consentTimeoutNanos) {
            state = IceState.FAILED
            failure = "Consent lost"
            changed.signalAll()
            return IDLE_NANOS
        }
        if (now - consentSentNanos >= consentIntervalNanos) {
            sendCheck(pair, now, newTransaction = true, nominate = controlling)
            consentSentNanos = now
        }
        return minOf(consentSentNanos + consentIntervalNanos - now, lastConsentNanos + consentTimeoutNanos - now).coerceAtLeast(1_000_000L)
    }

    private fun retransmitIn(pair: CandidatePair, now: Long): Long = pair.lastSentNanos + RETRANSMIT_NANOS - now

    private fun nominationReady(pair: CandidatePair, now: Long): Boolean {
        val best = pairs.firstOrNull { it.state == PairState.SUCCEEDED }
        return best === pair && (remoteLite || pair.peerChecked || now - pair.validAt >= NOMINATION_GRACE_NANOS)
    }

    private fun sendCheck(pair: CandidatePair, now: Long, newTransaction: Boolean, nominate: Boolean) {
        val key = remoteKey ?: return
        val ufrag = remoteUfrag ?: return
        var id = pair.transactionId
        if (newTransaction || id == null) {
            id = ByteArray(12).also { random.nextBytes(it) }
            pair.transactionId = id
            pair.attempts = 0
            pending[ByteBuffer.wrap(id)] = PendingCheck(pair, nominate, now)
        }
        pair.attempts++
        pair.lastSentNanos = now
        if (pair.state == PairState.WAITING) {
            pair.state = PairState.IN_PROGRESS
        }
        val builder = StunBuilder(STUN_BINDING_REQUEST, id).username("$ufrag:$localUfrag")
        if (controlling) builder.iceControlling(tieBreaker) else builder.iceControlled(tieBreaker)
        builder.priority(candidatePriority(PEER_REFLEXIVE_TYPE_PREFERENCE, pair.localPriority shr 8 and 0xFFFF))
        if (nominate && controlling) {
            builder.useCandidate()
        }
        transmit(builder.build().encode(key, fingerprint = true), pair.remote, pair)
    }

    private fun transmit(data: ByteArray, to: InetSocketAddress, pair: CandidatePair?) {
        try {
            socket.send(DatagramPacket(data, data.size, to))
        } catch (_: IOException) {
            if (pair != null && pair !== selectedPair) {
                pair.state = PairState.FAILED
            }
        }
    }

    private fun handleRequest(message: StunMessage, from: InetSocketAddress) {
        val username = message.username ?: return
        val separator = username.indexOf(':')
        if (separator <= 0 || username.substring(0, separator) != localUfrag) {
            return
        }
        val expectedRemote = remoteUfrag
        if (expectedRemote != null && username.substring(separator + 1) != expectedRemote) {
            return
        }
        if (!message.verifyIntegrity(localKey)) {
            return
        }
        if (message.find(STUN_ATTR_FINGERPRINT) != null && !message.verifyFingerprint()) {
            return
        }
        val theirControlling = message.iceControlling
        val theirControlled = message.iceControlled
        if (controlling && theirControlling != null) {
            if (java.lang.Long.compareUnsigned(tieBreaker, theirControlling) >= 0) {
                respondWithError(message, from, STUN_ERROR_ROLE_CONFLICT)
                return
            }
            switchRole(false)
        } else if (!controlling && theirControlled != null) {
            if (java.lang.Long.compareUnsigned(tieBreaker, theirControlled) >= 0) {
                switchRole(true)
            } else {
                respondWithError(message, from, STUN_ERROR_ROLE_CONFLICT)
                return
            }
        }
        val response = StunBuilder(STUN_BINDING_SUCCESS, message.transactionId).xorMappedAddress(from).build().encode(localKey, fingerprint = true)
        transmit(response, from, null)
        var pair = pairFor(from)
        if (pair == null) {
            pair = addPair(from, message.priority ?: candidatePriority(PEER_REFLEXIVE_TYPE_PREFERENCE, 0))
        }
        pair.peerChecked = true
        if (pair.state == PairState.FAILED && state == IceState.CHECKING) {
            pair.state = PairState.WAITING
            pair.transactionId = null
        }
        if (pair.state != PairState.SUCCEEDED) {
            pair.triggered = true
        }
        if (!controlling && message.useCandidate) {
            pair.nominatedByPeer = true
            if (pair.state == PairState.SUCCEEDED) {
                select(pair, clock())
            }
        }
    }

    private fun respondWithError(request: StunMessage, to: InetSocketAddress, code: Int) {
        transmit(StunBuilder(STUN_BINDING_ERROR, request.transactionId).errorCode(code).build().encode(localKey, fingerprint = true), to, null)
    }

    private fun handleSuccess(message: StunMessage, from: InetSocketAddress) {
        val check = pending[ByteBuffer.wrap(message.transactionId)] ?: return
        val key = remoteKey ?: return
        if (!message.verifyIntegrity(key)) {
            return
        }
        if (message.find(STUN_ATTR_FINGERPRINT) != null && !message.verifyFingerprint()) {
            return
        }
        val pair = check.pair
        if (from != pair.remote) {
            return
        }
        pending.remove(ByteBuffer.wrap(message.transactionId))
        val now = clock()
        if (pair.attempts <= 1 || pair === selectedPair) {
            rtt = (now - check.sentNanos) / 1_000_000.0
        }
        if (pair.state != PairState.SUCCEEDED) {
            pair.state = PairState.SUCCEEDED
            pair.validAt = now
        }
        if (pair === selectedPair) {
            lastConsentNanos = now
            return
        }
        if (controlling) {
            if (check.nominate) {
                select(pair, now)
            }
        } else if (pair.nominatedByPeer) {
            select(pair, now)
        }
    }

    private fun handleError(message: StunMessage) {
        val check = pending.remove(ByteBuffer.wrap(message.transactionId)) ?: return
        val pair = check.pair
        if (message.errorCode == STUN_ERROR_ROLE_CONFLICT) {
            switchRole(!controlling)
            pair.state = PairState.WAITING
            pair.triggered = true
            pair.transactionId = null
            if (nominating === pair) {
                nominating = null
            }
            return
        }
        if (pair !== selectedPair) {
            pair.state = PairState.FAILED
            if (nominating === pair) {
                nominating = null
            }
        }
    }

    private fun select(pair: CandidatePair, now: Long) {
        selectedPair = pair
        selectedRemote = pair.remote
        nominating = null
        lastConsentNanos = now
        consentSentNanos = now
        state = IceState.CONNECTED
    }

    private fun switchRole(newControlling: Boolean) {
        controlling = newControlling
        for (pair in pairs) {
            pair.recompute(controlling)
        }
        pairs.sortByDescending { it.priority }
        if (!controlling) {
            nominating = null
        }
    }

    private fun addPair(remote: InetSocketAddress, remotePriority: Long): CandidatePair {
        val pair = CandidatePair(remote, remotePriority, localPriorityFor(remote))
        pair.recompute(controlling)
        pairs.add(pair)
        pairs.sortByDescending { it.priority }
        knownRemotes = pairs.map { it.remote }
        return pair
    }

    private fun localPriorityFor(remote: InetSocketAddress): Long {
        val sameFamily = localCandidates.firstOrNull { (it.address is Inet6Address) == (remote.address is Inet6Address) }
        return sameFamily?.priority ?: candidatePriority(HOST_TYPE_PREFERENCE, 0)
    }

    private fun pairFor(address: InetSocketAddress): CandidatePair? = pairs.firstOrNull { it.remote == address }

    private fun expirePending(now: Long) {
        val iterator = pending.values.iterator()
        while (iterator.hasNext()) {
            if (now - iterator.next().sentNanos > PENDING_LIFETIME_NANOS) {
                iterator.remove()
            }
        }
    }
}
