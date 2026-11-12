package com.opirl.app.widgets.map

import java.util.concurrent.Executor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

private class ManualExecutor : Executor {
    val tasks = ArrayDeque<Runnable>()

    override fun execute(command: Runnable) {
        tasks.addLast(command)
    }

    fun runNext() = tasks.removeFirst().run()

    fun runAll() {
        while (tasks.isNotEmpty()) runNext()
    }
}

private class Fixture(capacity: Int = 48) {
    val executor = ManualExecutor()
    val loaded = mutableListOf<TileKey>()
    val failing = mutableSetOf<TileKey>()
    var time = 0L
    var finished = 0
    val store = TileStore<String>(executor, { finished++ }, capacity, retryDelay = 1000, clock = { time }) { key ->
        loaded += key
        if (key in failing) null else "tile $key"
    }
}

class TileStoreTest {
    private fun key(x: Int) = TileKey(5, x, 3)

    @Test
    fun atMostTwoTilesLoadAtOnceAndEachOnlyOnce() {
        val fixture = Fixture()
        val keys = listOf(key(1), key(2), key(3))
        fixture.store.request(keys)
        fixture.store.request(keys)
        assertEquals(2, fixture.executor.tasks.size)
        fixture.executor.runNext()
        assertEquals(2, fixture.executor.tasks.size)
        fixture.executor.runAll()
        fixture.store.request(keys)
        assertEquals(0, fixture.executor.tasks.size)
        assertEquals(keys, fixture.loaded)
    }

    @Test
    fun aTileNoLongerWantedIsNeverLoaded() {
        val fixture = Fixture()
        fixture.store.request(listOf(key(1), key(2), key(3)))
        fixture.store.request(listOf(key(4)))
        fixture.executor.runAll()
        assertEquals(listOf(key(1), key(2), key(4)), fixture.loaded)
    }

    @Test
    fun theLeastRecentlyUsedTileIsDroppedFirst() {
        val fixture = Fixture(capacity = 2)
        fixture.store.request(listOf(key(1), key(2)))
        fixture.executor.runAll()
        fixture.store.get(key(1))
        fixture.store.request(listOf(key(3)))
        fixture.executor.runAll()
        assertNull(fixture.store.get(key(2)))
        assertNotNull(fixture.store.get(key(1)))
        assertNotNull(fixture.store.get(key(3)))
    }

    @Test
    fun aTileBeyondTheDateLineIsTheTileOnTheOtherSide() {
        val fixture = Fixture()
        fixture.store.request(listOf(TileKey(3, 9, 1)))
        fixture.executor.runAll()
        assertEquals(listOf(TileKey(3, 1, 1)), fixture.loaded)
        assertNotNull(fixture.store.get(TileKey(3, 9, 1)))
    }

    @Test
    fun aFailedTileWaitsBeforeItIsTriedAgain() {
        val fixture = Fixture()
        fixture.failing += key(1)
        fixture.store.request(listOf(key(1)))
        fixture.executor.runAll()
        assertNull(fixture.store.get(key(1)))
        assertEquals(1000L, fixture.store.retryIn())
        fixture.time = 400
        fixture.store.request(listOf(key(1)))
        assertEquals(0, fixture.executor.tasks.size)
        assertEquals(600L, fixture.store.retryIn())
        fixture.time = 1000
        fixture.failing.clear()
        fixture.store.request(listOf(key(1)))
        fixture.executor.runAll()
        assertNotNull(fixture.store.get(key(1)))
        assertNull(fixture.store.retryIn())
    }

    @Test
    fun aTileThatKeepsFailingIsTriedLessAndLessOften() {
        val fixture = Fixture()
        fixture.failing += key(1)
        val waits = (1..4).map {
            fixture.store.request(listOf(key(1)))
            fixture.executor.runAll()
            val wait = fixture.store.retryIn()
            fixture.time += wait!!
            wait
        }
        assertEquals(listOf(1000L, 2000L, 4000L, 8000L), waits)
    }

    @Test
    fun theCallerHearsOfEveryFinishedLoadWhetherItWorkedOrNot() {
        val fixture = Fixture()
        fixture.failing += key(2)
        fixture.store.request(listOf(key(1), key(2)))
        fixture.executor.runAll()
        assertEquals(2, fixture.finished)
    }
}
