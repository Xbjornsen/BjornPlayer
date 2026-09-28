package com.bjorntech.player

import androidx.media3.common.C
import androidx.media3.exoplayer.source.ShuffleOrder
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Random

class QueueShuffleOrderTest {

    @After
    fun reset() { QueueShuffleOrder.pendingStartIndex = C.INDEX_UNSET }

    /** Full play order as timeline indices, walking getFirstIndex/getNextIndex. */
    private fun ShuffleOrder.playOrder(): List<Int> {
        val out = mutableListOf<Int>()
        var i = firstIndex
        while (i != C.INDEX_UNSET) { out += i; i = getNextIndex(i) }
        return out
    }

    /** Must be a permutation, and walking backwards must mirror the forward walk. */
    private fun ShuffleOrder.assertConsistent() {
        val fwd = playOrder()
        assertEquals("is a permutation", (0 until length).toList(), fwd.sorted())
        val back = mutableListOf<Int>()
        var i = lastIndex
        while (i != C.INDEX_UNSET) { back += i; i = getPreviousIndex(i) }
        assertEquals(fwd.reversed(), back)
    }

    private fun fresh(length: Int, start: Int = C.INDEX_UNSET, seed: Long = 1): ShuffleOrder {
        QueueShuffleOrder.pendingStartIndex = start
        // How ExoPlayer builds a new playlist: clear, then insert everything.
        return QueueShuffleOrder(0, Random(seed)).cloneAndClear().cloneAndInsert(0, length)
    }

    @Test
    fun emptyOrderHasNoIndices() {
        val o = QueueShuffleOrder(0, Random(1))
        assertEquals(0, o.length)
        assertEquals(C.INDEX_UNSET, o.firstIndex)
        assertEquals(C.INDEX_UNSET, o.lastIndex)
    }

    @Test
    fun freshPlaylistIsAPermutation() {
        for (seed in 0L until 50) fresh(37, seed = seed).assertConsistent()
    }

    @Test
    fun tappedSongPlaysFirst() {
        for (seed in 0L until 50) {
            val start = (seed % 20).toInt()
            val o = fresh(20, start, seed)
            assertEquals(start, o.firstIndex)
            o.assertConsistent()
        }
    }

    @Test
    fun startIndexIsConsumedOnce() {
        fresh(10, start = 7)
        assertEquals(C.INDEX_UNSET, QueueShuffleOrder.pendingStartIndex)
    }

    @Test
    fun queuedSongsPlayNextInFifoOrder() {
        for (seed in 0L until 100) {
            val n = 5 + (seed % 20).toInt()
            var o = fresh(n, seed = seed)
            // Labels per timeline index; queued items get negative labels.
            val labels = MutableList(n) { it }
            val before = o.playOrder().map { labels[it] }
            val current = o.playOrder()[(seed % n).toInt()]

            // Queue A right after current, then B right after A (what MainActivity does).
            val a = current + 1
            o = o.cloneAndInsert(a, 1); labels.add(a, -1)
            val b = a + 1
            o = o.cloneAndInsert(b, 1); labels.add(b, -2)
            o.assertConsistent()

            val play = o.playOrder().map { labels[it] }
            val p = play.indexOf(current)   // current's label == its original index
            assertEquals(listOf(-1, -2), play.subList(p + 1, p + 3))
            // Everything else keeps its relative order.
            assertEquals(before, play.filter { it >= 0 })
        }
    }

    @Test
    fun removingAnItemKeepsTheRestInOrder() {
        var o = fresh(15, seed = 3)
        val labels = MutableList(15) { it }
        val before = o.playOrder().map { labels[it] }
        o = o.cloneAndRemove(4, 7); repeat(3) { labels.removeAt(4) }
        o.assertConsistent()
        assertEquals(before.filter { it !in 4..6 }, o.playOrder().map { labels[it] })
    }

    @Test
    fun insertAtFrontGoesFirst() {
        val o = fresh(8, seed = 9).cloneAndInsert(0, 2)
        o.assertConsistent()
        assertEquals(listOf(0, 1), o.playOrder().take(2))
    }

    @Test
    fun clearEmptiesTheOrder() {
        assertEquals(0, fresh(10).cloneAndClear().length)
    }

    @Test
    fun removingEverythingThenInsertingShufflesAfresh() {
        val emptied = fresh(10, seed = 4).cloneAndRemove(0, 10)
        assertEquals(0, emptied.length)
        QueueShuffleOrder.pendingStartIndex = 3
        val refilled = emptied.cloneAndInsert(0, 6)
        refilled.assertConsistent()
        assertEquals(6, refilled.length)
        assertEquals(3, refilled.firstIndex)
    }
}
