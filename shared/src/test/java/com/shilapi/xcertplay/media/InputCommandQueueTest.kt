package com.shilapi.xcertplay.media

import com.shilapi.xcertplay.airplay.AirPlayContact
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.TimeUnit

class InputCommandQueueTest {
    private class Scheduler:AbstractExecutorService(){
        val tasks=java.util.ArrayDeque<Runnable>();var closed=false
        override fun execute(command:Runnable){if(closed)throw java.util.concurrent.RejectedExecutionException();tasks.add(command)}
        fun runAll(){while(tasks.isNotEmpty())tasks.removeFirst().run()}
        override fun shutdown(){closed=true}
        override fun shutdownNow():MutableList<Runnable>{closed=true;return tasks.toMutableList().also{tasks.clear()}}
        override fun isShutdown()=closed
        override fun isTerminated()=closed && tasks.isEmpty()
        override fun awaitTermination(timeout:Long,unit:TimeUnit)=isTerminated
    }
    private class Session{var token:Any=Any();var closed=false}
    private fun contact(x:Double,down:Boolean=true,id:Int=1)=listOf(AirPlayContact(id,x,0.5,down))
    @Test fun thousandPendingMovesKeepOnlyLatestCoordinatesAndPreserveUp() {
        val scheduler=Scheduler();val session=Session();val sent=mutableListOf<List<AirPlayContact>>();val metrics=mutableListOf<InputCommandMetrics>()
        val queue=InputCommandQueue({session},{!it.closed},{it.token},{_,value->sent.add(value);true},{_,_->fail("unexpected fault")},metrics::add,scheduler)
        assertTrue(queue.touch(contact(0.0)))
        repeat(1000){assertTrue(queue.touch(contact((it+1)/1000.0)))}
        assertTrue(queue.touch(contact(1.0,false)))
        assertEquals(1,scheduler.tasks.size)
        scheduler.runAll()
        assertEquals(3,sent.size);assertEquals(1.0,sent[1][0].x,0.0);assertFalse(sent.last()[0].down)
        assertEquals(999L,metrics.last().coalescedMoves);queue.shutdownNow()
    }
    @Test fun controlsAreOrderedAndCannotBeDiscardedByFirstTouchOrMergedAcross() {
        val scheduler=Scheduler();val session=Session();val sent=mutableListOf<String>()
        val queue=InputCommandQueue({session},{true},{it.token},{_,value->sent.add("touch:${value[0].x}");true},{_,_->fail()},executor=scheduler)
        queue.execute{sent.add("siri")};queue.touch(contact(0.0));queue.touch(contact(0.1))
        queue.execute{sent.add("knob")};queue.touch(contact(0.2));scheduler.runAll()
        assertEquals(listOf("siri","touch:0.0","touch:0.1","knob","touch:0.2"),sent);queue.shutdownNow()
    }
    @Test fun reliableBoundaryOverflowIsExplicitSessionFaultRatherThanSilentUpDrop() {
        val scheduler=Scheduler();val session=Session();var faults=0
        val queue=InputCommandQueue({session},{!it.closed},{it.token},{_,_->true},{s,reason->assertEquals("input_backpressure",reason);s.closed=true;faults++},executor=scheduler,capacity=2)
        queue.touch(contact(0.0));queue.execute{}
        assertFalse(queue.touch(contact(1.0,false)));assertEquals(1,faults);assertTrue(session.closed)
        scheduler.runAll();queue.shutdownNow()
    }
    @Test fun queuedOldScreenTouchesAreDroppedWhileControlSessionAndNewScreenRemainUsable() {
        val scheduler=Scheduler();val session=Session();var touches=0;var controls=0
        val queue=InputCommandQueue({session},{true},{it.token},{_,_->touches++;true},{_,_->fail()},executor=scheduler)
        queue.touch(contact(0.0));queue.execute{controls++};session.token=Any();queue.touch(contact(0.5))
        scheduler.runAll();assertEquals(1,touches);assertEquals(1,controls);queue.shutdownNow()
    }
    @Test fun rejectedExecutorNeverPretendsToAcceptTheFirstMove() {
        val scheduler=Scheduler().apply{shutdown()};val session=Session()
        val queue=InputCommandQueue({session},{true},{it.token},{_,_->true},{_,_->fail()},executor=scheduler)
        assertFalse(queue.touch(contact(0.0)));assertFalse(queue.touch(contact(0.5)));queue.shutdownNow()
    }
    @Test fun queueWaitAndSendDurationUseOneClockAndWrittenIsNotAcknowledgement() {
        val scheduler=Scheduler();val session=Session();var now=100L;var observed:InputCommandMetrics?=null
        val queue=InputCommandQueue({session},{true},{it.token},{_,_->now+=50;false},{_,_->fail()},report={observed=it},executor=scheduler,nanoTime={now})
        queue.touch(contact(0.0));now=300;scheduler.runAll()
        assertEquals(200L,observed!!.queueWaitNs);assertEquals(50L,observed!!.sendNs);assertEquals(false,observed!!.written)
        queue.shutdownNow()
    }
    @Test fun overflowReportsRejectionEvenWhenNoSendCanRun() {
        val executor=Scheduler();val session=Session();val reports=mutableListOf<InputCommandMetrics>()
        val queue=InputCommandQueue({session},{!it.closed},{it.token},{_,_->true},{_,_->},
            report={reports.add(it)},executor=executor,capacity=1)
        assertTrue(queue.touch(contact(0.1)))
        assertFalse(queue.touch(contact(0.2,false)))
        assertEquals(false,reports.last().accepted)
        assertEquals(1L,reports.last().overflow)
        assertNull(reports.last().written)
        queue.shutdownNow()
    }

    @Test fun discardedOldSessionCommandCompletesOnceWithoutExecuting() {
        val scheduler=Scheduler();var session=Session();var sent=0;var cancelled=0
        val queue=InputCommandQueue({session},{!it.closed},{it.token},{_,_->true},{_,_->},executor=scheduler)
        assertTrue(queue.executeTracked({sent++},{cancelled++}))
        session=Session();scheduler.runAll();queue.shutdownNow()
        assertEquals(0,sent);assertEquals(1,cancelled)
    }
    @Test fun overflowAndShutdownNotifyEveryDiscardedReliableCommandOnce() {
        val scheduler=Scheduler();val session=Session();var cancelled=0
        val queue=InputCommandQueue({session},{!it.closed},{it.token},{_,_->true},{_,_->},executor=scheduler,capacity=1)
        assertTrue(queue.executeTracked({fail("discarded")},{cancelled++}))
        assertFalse(queue.executeTracked({fail("rejected")},{cancelled++}))
        queue.shutdownNow();scheduler.runAll();assertEquals(2,cancelled)
    }

}
