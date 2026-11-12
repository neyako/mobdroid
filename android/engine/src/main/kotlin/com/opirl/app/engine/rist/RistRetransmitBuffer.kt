package com.opirl.app.engine.rist

internal const val RIST_MAXIMUM_RETAINED_PACKETS = 16384
internal const val RIST_RETRY_QUEUE_CAPACITY = 4096
private const val SEQUENCE_SPACE = 65536
private const val NEVER = Long.MIN_VALUE
private val NO_DATA = ByteArray(0)

internal enum class RistRequestResult { Accepted, Missing, Duplicate, Exhausted }

internal class RistSlot {
    var sequence = 0
    var data = NO_DATA
    var length = 0
    var sentAt = 0L
    var requestedAt = NEVER
    var retransmits = 0
    var queued = false
    var valid = false
}

internal class RistRetransmitBuffer(
    private val retentionNanos: Long,
    private val maximumPackets: Int = RIST_MAXIMUM_RETAINED_PACKETS,
) {
    private val slots = arrayOfNulls<RistSlot>(SEQUENCE_SPACE)
    private val pool = ArrayDeque<ByteArray>()
    private var head = 0

    var size = 0
        private set

    var nextSequence = 0
        private set

    fun next(now: Long): RistSlot {
        while (size > 0 && (size >= maximumPackets || now - slots[head]!!.sentAt > retentionNanos)) {
            release()
        }
        val slot = slots[nextSequence] ?: RistSlot().also { slots[nextSequence] = it }
        slot.sequence = nextSequence
        slot.data = pool.removeLastOrNull() ?: ByteArray(RIST_PACKET_SIZE)
        slot.length = 0
        slot.sentAt = now
        slot.requestedAt = NEVER
        slot.retransmits = 0
        slot.queued = false
        slot.valid = true
        nextSequence = (nextSequence + 1) and 0xFFFF
        size += 1
        return slot
    }

    fun find(sequence: Int, now: Long): RistSlot? {
        val slot = slots[sequence and 0xFFFF] ?: return null
        return if (slot.valid && now - slot.sentAt <= retentionNanos) slot else null
    }

    fun request(sequence: Int, now: Long, spacingNanos: Long, maximumRetransmits: Int): RistRequestResult {
        val slot = find(sequence, now) ?: return RistRequestResult.Missing
        return when {
            slot.queued || (slot.requestedAt != NEVER && now - slot.requestedAt < spacingNanos) -> RistRequestResult.Duplicate
            slot.retransmits >= maximumRetransmits -> RistRequestResult.Exhausted
            else -> {
                slot.queued = true
                slot.requestedAt = now
                RistRequestResult.Accepted
            }
        }
    }

    private fun release() {
        val slot = slots[head]!!
        slot.valid = false
        pool.addLast(slot.data)
        slot.data = NO_DATA
        head = (head + 1) and 0xFFFF
        size -= 1
    }
}

internal class RistRetryQueue<T : Any>(private val capacity: Int = RIST_RETRY_QUEUE_CAPACITY) {
    private val sequences = IntArray(capacity)
    private val requesters = arrayOfNulls<Any>(capacity)
    private var head = 0

    var size = 0
        private set

    fun add(sequence: Int, requester: T): Boolean {
        if (size == capacity) {
            return false
        }
        val index = (head + size) % capacity
        sequences[index] = sequence
        requesters[index] = requester
        size += 1
        return true
    }

    fun firstSequence(): Int = sequences[head]

    @Suppress("UNCHECKED_CAST")
    fun firstRequester(): T = requesters[head] as T

    fun removeFirst() {
        requesters[head] = null
        head = (head + 1) % capacity
        size -= 1
    }

    fun clear() {
        while (size > 0) {
            removeFirst()
        }
    }
}
