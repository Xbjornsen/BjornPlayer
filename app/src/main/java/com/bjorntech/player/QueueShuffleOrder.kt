package com.bjorntech.player

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.source.ShuffleOrder
import java.util.Random

/**
 * Shuffle order tuned for a music library with a user queue.
 *
 *  1. On a fresh playlist (setMediaItems → [cloneAndSet]) the song the user tapped
 *     plays first, so the rest of the library follows it in shuffled order instead
 *     of part of it being skipped.
 *  2. Items added later are placed right after their timeline predecessor (the
 *     DefaultShuffleOrder puts them at random positions), so "Add to queue" means
 *     "play next". MainActivity inserts queued songs directly after the current
 *     item (or after the last queued one), so the queue plays in the order added.
 *
 * Installed on the ExoPlayer in PlaybackService. Unit-tested in QueueShuffleOrderTest.
 */
@OptIn(UnstableApi::class)
class QueueShuffleOrder private constructor(
    private val shuffled: IntArray,
    private val random: Random
) : ShuffleOrder {

    companion object {
        private fun freshShuffle(length: Int, random: Random, start: Int = C.INDEX_UNSET): IntArray {
            val a = IntArray(length) { it }
            for (i in length - 1 downTo 1) {
                val j = random.nextInt(i + 1)
                val t = a[i]; a[i] = a[j]; a[j] = t
            }
            if (start in 0 until length) {
                val pos = a.indexOf(start)
                a[pos] = a[0]; a[0] = start
            }
            return a
        }
    }

    constructor(length: Int = 0, random: Random = Random()) : this(freshShuffle(length, random), random)

    /** indexInShuffled[timelineIndex] = position in play order. */
    private val indexInShuffled = IntArray(shuffled.size).also { inv ->
        for (pos in shuffled.indices) inv[shuffled[pos]] = pos
    }

    override fun getLength(): Int = shuffled.size

    override fun getNextIndex(index: Int): Int {
        val pos = indexInShuffled[index] + 1
        return if (pos < shuffled.size) shuffled[pos] else C.INDEX_UNSET
    }

    override fun getPreviousIndex(index: Int): Int {
        val pos = indexInShuffled[index] - 1
        return if (pos >= 0) shuffled[pos] else C.INDEX_UNSET
    }

    override fun getLastIndex(): Int = if (shuffled.isEmpty()) C.INDEX_UNSET else shuffled[shuffled.size - 1]

    override fun getFirstIndex(): Int = if (shuffled.isEmpty()) C.INDEX_UNSET else shuffled[0]

    override fun cloneAndInsert(insertionIndex: Int, insertionCount: Int): ShuffleOrder {
        if (shuffled.isEmpty()) return QueueShuffleOrder(insertionCount, random)
        // Play-order position of the item just before the insertion point (-1 = front).
        val anchorPos = if (insertionIndex > 0) indexInShuffled[insertionIndex - 1] else -1
        val out = IntArray(shuffled.size + insertionCount)
        var o = 0
        for (pos in shuffled.indices) {
            val v = shuffled[pos]
            out[o++] = if (v >= insertionIndex) v + insertionCount else v
            if (pos == anchorPos) for (k in 0 until insertionCount) out[o++] = insertionIndex + k
        }
        if (anchorPos == -1) {
            // Insert at the front: shift everything right and put new items first.
            System.arraycopy(out, 0, out, insertionCount, shuffled.size)
            for (k in 0 until insertionCount) out[k] = insertionIndex + k
        }
        return QueueShuffleOrder(out, random)
    }

    override fun cloneAndRemove(indexFrom: Int, indexToExclusive: Int): ShuffleOrder {
        val removed = indexToExclusive - indexFrom
        val out = IntArray(shuffled.size - removed)
        var o = 0
        for (v in shuffled) {
            if (v in indexFrom until indexToExclusive) continue
            out[o++] = if (v >= indexToExclusive) v - removed else v
        }
        return QueueShuffleOrder(out, random)
    }

    override fun cloneAndClear(): ShuffleOrder = QueueShuffleOrder(IntArray(0), random)

    /** New playlist (setMediaItems): fresh shuffle with the tapped item first. */
    override fun cloneAndSet(insertionCount: Int, startIndex: Int): ShuffleOrder =
        QueueShuffleOrder(freshShuffle(insertionCount, random, startIndex), random)

    /**
     * Timeline items [indexFrom, indexToExclusive) moved to start at [newIndexFrom].
     * Play order is unchanged; only the timeline indices are renumbered. (The
     * interface default returns `this`, which would leave stale indices.)
     */
    override fun cloneAndMove(indexFrom: Int, indexToExclusive: Int, newIndexFrom: Int): ShuffleOrder {
        if (indexFrom == newIndexFrom || indexFrom == indexToExclusive) return this
        val n = shuffled.size
        val count = indexToExclusive - indexFrom
        // Rebuild the timeline permutation, then map each old index to its new one.
        val timeline = (0 until n).toMutableList()
        val moved = timeline.subList(indexFrom, indexToExclusive).toList()
        repeat(count) { timeline.removeAt(indexFrom) }
        timeline.addAll(newIndexFrom, moved)
        val newIndexOf = IntArray(n).also { for (newI in 0 until n) it[timeline[newI]] = newI }
        return QueueShuffleOrder(IntArray(n) { newIndexOf[shuffled[it]] }, random)
    }
}
