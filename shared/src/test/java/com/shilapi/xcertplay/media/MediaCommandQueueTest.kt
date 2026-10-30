package com.shilapi.xcertplay.media

import com.shilapi.xcertplay.airplay.CarPlayMediaButton.PLAY
import com.shilapi.xcertplay.airplay.CarPlayMediaButton.PAUSE
import com.shilapi.xcertplay.airplay.CarPlayMediaButton.NEXT
import com.shilapi.xcertplay.airplay.CarPlayMediaButton.PREVIOUS
import org.junit.Assert.*
import org.junit.Test

class MediaCommandQueueTest {
    private class Manual : MediaCommandScheduler {
        var now = 0L
        private var serial = 0
        private data class Work(val at: Long, val order: Int, val action: () -> Unit)
        private val work = java.util.PriorityQueue<Work>(compareBy<Work> { it.at }.thenBy { it.order })
        var stopped = false
        override fun post(delayMillis: Long, action: () -> Unit) {
            if (stopped) throw java.util.concurrent.RejectedExecutionException()
            work += Work(now+delayMillis, serial++, action)
        }
        fun advance(ms: Long = 0) {
            val end = now + ms
            while (work.peek()?.at?.let { it <= end } == true) {
                val task = work.remove(); now = task.at; task.action()
            }
            now = end
        }
        override fun close() { stopped = true; work.clear() }
    }
    private class Harness : AutoCloseable {
        val timer = Manual()
        var session: Any? = Any()
        var usable = true
        var state = CarPlayNowPlaying(title="A", artist="artist", playing=true)
        var accepted = true
        val sent = mutableListOf<Pair<Any,Int>>()
        val queue = MediaCommandQueue(current={session},usable={usable},snapshot={state},
            send={ s, i -> sent += s to i; accepted },scheduler=timer,nanoTime={timer.now*1_000_000})
        fun commands() = sent.map { it.second }
        override fun close() { queue.close() }
    }
    @Test fun preservesOrderButDropsQueuedWorkForReplacedSessions() = Harness().use { h ->
        h.queue.enqueue(PLAY); h.queue.enqueue(PAUSE); h.timer.advance()
        assertEquals(listOf(PLAY,PAUSE), h.commands())
        h.queue.enqueue(NEXT); h.session=Any(); h.timer.advance(5000)
        assertEquals(listOf(PLAY,PAUSE), h.commands())
    }
    @Test fun changedTrackPausedGetsOnePlayAfter700ms() = Harness().use { h ->
        h.queue.enqueue(NEXT); h.timer.advance(); h.state=h.state.copy(title="B",playing=false)
        h.timer.advance(699); assertEquals(listOf(NEXT),h.commands())
        h.timer.advance(1); assertEquals(listOf(NEXT,PLAY),h.commands())
        h.timer.advance(5000); assertEquals(listOf(NEXT,PLAY),h.commands())
    }
    @Test fun changedTrackAlreadyPlayingDoesNotGetRedundantPlay() = Harness().use { h ->
        h.queue.enqueue(PREVIOUS);h.timer.advance();h.state=h.state.copy(title="B")
        h.timer.advance(5000);assertEquals(listOf(PREVIOUS),h.commands())
    }
    @Test fun unchangedTrackKeepsPollingEvenWhenOldStateStillSaysPlaying() = Harness().use { h ->
        h.queue.enqueue(NEXT);h.timer.advance(700)
        h.state=h.state.copy(title="B",playing=false)
        h.timer.advance(300);assertEquals(listOf(NEXT,PLAY),h.commands())
    }
    @Test fun unchangedPausedTrackOnlyResumesAtBoundedDeadline() = Harness().use { h ->
        h.queue.enqueue(NEXT);h.timer.advance();h.state=h.state.copy(playing=false)
        h.timer.advance(3699);assertEquals(listOf(NEXT),h.commands())
        h.timer.advance(1);assertEquals(listOf(NEXT,PLAY),h.commands())
    }
    @Test fun manualPauseCancelsResumeImmediatelyEvenBeforePauseDispatch() = Harness().use { h ->
        h.queue.enqueue(NEXT);h.timer.advance();h.state=h.state.copy(title="B",playing=false)
        h.queue.enqueue(PAUSE);h.timer.advance(5000)
        assertEquals(listOf(NEXT,PAUSE),h.commands())
    }
    @Test fun rapidNextOnlyNewestResumeIsAllowed() = Harness().use { h ->
        h.queue.enqueue(NEXT);h.timer.advance();h.timer.advance(200)
        h.queue.enqueue(NEXT);h.timer.advance();h.state=h.state.copy(title="C",playing=false)
        h.timer.advance(500);assertEquals(listOf(NEXT,NEXT),h.commands())
        h.timer.advance(200);assertEquals(listOf(NEXT,NEXT,PLAY),h.commands())
    }
    @Test fun rapidNextRetainsPlayIntentAcrossTransientPausedMetadata() = Harness().use { h ->
        h.queue.enqueue(NEXT);h.timer.advance();h.state=h.state.copy(title="B",playing=false)
        h.timer.advance(200);h.queue.enqueue(NEXT);h.timer.advance();h.state=h.state.copy(title="C")
        h.timer.advance(700);assertEquals(listOf(NEXT,NEXT,PLAY),h.commands())
    }
    @Test fun explicitPauseClearsInheritedResumeIntentBeforeAnotherSkip() = Harness().use { h ->
        h.queue.enqueue(NEXT);h.timer.advance();h.state=h.state.copy(playing=false)
        h.queue.enqueue(PAUSE);h.queue.enqueue(NEXT);h.timer.advance(5000)
        assertEquals(listOf(NEXT,PAUSE,NEXT),h.commands())
    }
    @Test fun pauseThenSkipDoesNotResumeFromStalePlayingMetadata() = Harness().use { h ->
        h.queue.enqueue(PAUSE);h.queue.enqueue(NEXT);h.timer.advance()
        h.state=h.state.copy(title="B",playing=false);h.timer.advance(5000)
        assertEquals(listOf(PAUSE,NEXT),h.commands())
    }
    @Test fun explicitPlayOrConfirmedExternalResumeReenablesSkipRecovery() = Harness().use { h ->
        h.queue.enqueue(PAUSE);h.timer.advance();h.queue.observePlayback(false);h.timer.advance()
        h.queue.observePlayback(true);h.timer.advance();h.queue.enqueue(NEXT);h.timer.advance()
        h.state=h.state.copy(title="B",playing=false);h.timer.advance(700)
        assertEquals(listOf(PAUSE,NEXT,PLAY),h.commands())
        h.queue.enqueue(PAUSE);h.timer.advance();h.queue.enqueue(PLAY);h.timer.advance()
        h.state=h.state.copy(title="B",playing=true);h.queue.enqueue(NEXT);h.timer.advance()
        h.state=h.state.copy(title="C",playing=false);h.timer.advance(700)
        assertEquals(listOf(PAUSE,NEXT,PLAY,PAUSE,PLAY,NEXT,PLAY),h.commands())
    }
    @Test fun skipWhileAlreadyPausedDoesNotStartPlayback() = Harness().use { h ->
        h.state=h.state.copy(playing=false);h.queue.enqueue(NEXT);h.timer.advance(5000)
        assertEquals(listOf(NEXT),h.commands())
    }
    @Test fun failedWriteNeverSchedulesResume() = Harness().use { h ->
        h.accepted=false;h.queue.enqueue(NEXT);h.timer.advance();h.state=h.state.copy(title="B",playing=false)
        h.timer.advance(5000);assertEquals(listOf(NEXT),h.commands())
    }
    @Test fun noSessionClosedSessionAndQueueCloseCannotSend() = Harness().use { h ->
        h.session=null;assertFalse(h.queue.enqueue(PLAY));h.session=Any();h.usable=false
        assertFalse(h.queue.enqueue(PLAY));h.usable=true;h.queue.enqueue(NEXT);h.queue.close()
        h.timer.advance(5000);assertTrue(h.sent.isEmpty());assertFalse(h.queue.enqueue(PLAY))
    }
    @Test fun replacementAndCloseCancelDelayedAutoPlay() = Harness().use { h ->
        h.queue.enqueue(NEXT);h.timer.advance();h.state=h.state.copy(title="B",playing=false)
        h.session=Any();h.timer.advance(5000);assertEquals(listOf(NEXT),h.commands())
    }
    @Test fun unknownTrackDoesNotWaitForATitleThatMayNeverArrive() = Harness().use { h ->
        h.state=h.state.copy(title=null,artist=null);h.queue.enqueue(NEXT);h.timer.advance()
        h.state=h.state.copy(playing=false);h.timer.advance(700)
        assertEquals(listOf(NEXT,PLAY),h.commands())
    }
    @Test fun realSerialSchedulerRunsCommandsInOrderAndStops() {
        val scheduler=SerialMediaCommandScheduler();val calls=java.util.concurrent.CopyOnWriteArrayList<Int>()
        val done=java.util.concurrent.CountDownLatch(2)
        try {
            scheduler.post { calls+=1;done.countDown() };scheduler.post { calls+=2;done.countDown() }
            assertTrue(done.await(5,java.util.concurrent.TimeUnit.SECONDS));assertEquals(listOf(1,2),calls)
        } finally {scheduler.close()}
    }
}
