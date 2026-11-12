package com.opirl.app.widgets.map

import java.util.concurrent.Executor

class TileStore<T : Any>(
    private val executor: Executor,
    private val onLoaded: () -> Unit,
    private val capacity: Int = 48,
    private val retryDelay: Long = 5_000,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
    private val load: (TileKey) -> T?,
) {
    private class Failure(val attempts: Int, val retryAt: Long)

    private val lock = Any()
    private val tiles = object : LinkedHashMap<TileKey, T>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<TileKey, T>) = size > capacity
    }
    private val loading = HashSet<TileKey>()
    private val failed = HashMap<TileKey, Failure>()
    private var wanted = emptyList<TileKey>()

    fun get(key: TileKey): T? = synchronized(lock) { tiles[key.wrapped()] }

    fun request(keys: List<TileKey>) {
        synchronized(lock) {
            wanted = keys.map { it.wrapped() }
            failed.keys.retainAll(wanted.toSet())
        }
        pump()
    }

    fun retryIn(): Long? = synchronized(lock) {
        wanted.filter { it !in loading }.mapNotNull { failed[it]?.retryAt }.minOrNull()?.let { (it - clock()).coerceAtLeast(0) }
    }

    private fun pump() {
        while (true) {
            val key = synchronized(lock) {
                if (loading.size >= WORKERS) return
                val now = clock()
                wanted.firstOrNull { it !in tiles && it !in loading && (failed[it]?.retryAt ?: 0) <= now }?.also { loading += it }
            } ?: return
            executor.execute { fetch(key) }
        }
    }

    private fun fetch(key: TileKey) {
        val tile = try {
            load(key)
        } catch (e: Exception) {
            null
        }
        synchronized(lock) {
            loading -= key
            if (tile != null) {
                tiles[key] = tile
                failed -= key
            } else {
                val attempts = (failed[key]?.attempts ?: 0) + 1
                failed[key] = Failure(attempts, clock() + (retryDelay shl minOf(attempts - 1, MAX_DOUBLINGS)))
            }
        }
        onLoaded()
        pump()
    }

    private companion object {
        const val WORKERS = 2
        const val MAX_DOUBLINGS = 6
    }
}
