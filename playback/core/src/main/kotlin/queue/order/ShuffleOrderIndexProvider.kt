package org.jellyfin.playback.core.queue.order

/**
 * Provides a shuffled play order that covers the whole queue exactly once. It keeps a list of planned
 * upcoming indices so preloading (peek) is stable, refilling from the not-yet-played indices as they
 * are consumed.
 */
internal class ShuffleOrderIndexProvider : OrderIndexProvider {
	private val nextIndices = mutableListOf<Int>()

	override fun reset() = nextIndices.clear()

	override fun provideIndices(
		amount: Int,
		size: Int,
		playedIndices: Collection<Int>,
		currentIndex: Int,
	): Collection<Int> {
		// Drop planned indices that are no longer valid (out of range, already played, or now playing).
		nextIndices.retainAll { it in 0 until size && it != currentIndex && it !in playedIndices }

		if (nextIndices.size < amount) {
			val taken = HashSet(playedIndices).apply {
				add(currentIndex)
				addAll(nextIndices)
			}
			val available = (0 until size).filterTo(mutableListOf()) { it !in taken }
			available.shuffle()

			var i = 0
			while (nextIndices.size < amount && i < available.size) {
				nextIndices.add(available[i])
				i++
			}
		}

		return nextIndices.take(amount)
	}

	override fun notifyRemoved(index: Int) {
		nextIndices.removeAll { it == index }
		nextIndices.replaceAll { if (it > index) it - 1 else it }
	}

	override fun useNextIndex() {
		if (nextIndices.isNotEmpty()) nextIndices.removeAt(0)
	}
}
