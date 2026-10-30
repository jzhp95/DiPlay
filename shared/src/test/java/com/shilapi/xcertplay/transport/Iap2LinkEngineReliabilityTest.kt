package com.shilapi.xcertplay.transport

import org.junit.Assert.*
import org.junit.Test

class Iap2LinkEngineReliabilityTest {
    @Test fun oneNegotiatedRetransmissionIsSentBeforeTheLinkDies() {
        val engine=link(retries=1)
        engine.sendControl(byteArrayOf(7),0);dataFrames(engine)
        engine.advanceTime(99);assertTrue(dataFrames(engine).isEmpty())
        engine.advanceTime(100)
        assertEquals(100,sequence(dataFrames(engine).single()))
        assertEquals(Iap2LinkEngine.State.NORMAL,engine.state())
        engine.advanceTime(200)
        assertEquals(Iap2LinkEngine.State.DEAD,engine.state())
        assertNull(engine.nextDeadlineMillis())
    }

    @Test fun aNegotiatedBudgetAllowsExactlyThatManyTimeoutRetransmissions() {
        val engine=link(retries=3)
        engine.sendControl(byteArrayOf(7),0);dataFrames(engine)
        for(time in listOf(100L,200L,300L)) {
            engine.advanceTime(time)
            assertEquals(100,sequence(dataFrames(engine).single()))
            assertEquals(Iap2LinkEngine.State.NORMAL,engine.state())
        }
        engine.advanceTime(400);assertEquals(Iap2LinkEngine.State.DEAD,engine.state())
    }

    @Test fun extendedAckAndTimeoutUseOneRetransmissionBudget() {
        val engine=link(retries=2)
        engine.sendControl(byteArrayOf(7),0);dataFrames(engine)
        engine.feed(packet(0x20,payload=byteArrayOf(100)),50)
        assertEquals(100,sequence(dataFrames(engine).single()))
        assertEquals(150L,engine.nextDeadlineMillis())
        engine.advanceTime(100);assertTrue(dataFrames(engine).isEmpty())
        engine.advanceTime(150);assertEquals(100,sequence(dataFrames(engine).single()))
        engine.feed(packet(0x20,payload=byteArrayOf(100)),175)
        assertEquals(Iap2LinkEngine.State.DEAD,engine.state())
    }

    @Test fun aSingleExtendedAckRetryIsNotRejectedBeforeItIsSent() {
        val engine=link(retries=1)
        engine.sendControl(byteArrayOf(7),0);dataFrames(engine)
        engine.feed(packet(0x20,payload=byteArrayOf(100)),50)
        assertEquals(100,sequence(dataFrames(engine).single()))
        assertEquals(Iap2LinkEngine.State.NORMAL,engine.state())
        engine.feed(packet(0x20,payload=byteArrayOf(100)),60)
        assertEquals(Iap2LinkEngine.State.DEAD,engine.state())
    }

    @Test fun aNewPacketCannotPostponeTheOldestUnacknowledgedPacket() {
        val engine=link()
        engine.sendControl(byteArrayOf(1),0);engine.sendControl(byteArrayOf(2),10)
        dataFrames(engine)
        assertEquals(100L,engine.nextDeadlineMillis())
        engine.advanceTime(100)
        assertEquals(100,sequence(dataFrames(engine).single()))
        assertEquals(110L,engine.nextDeadlineMillis())
    }

    @Test fun duplicateAckKeepsTheEarliestDeadlineAfterOnePacketWasRetransmitted() {
        val engine=link()
        engine.sendControl(byteArrayOf(1),0);engine.sendControl(byteArrayOf(2),10)
        dataFrames(engine);engine.advanceTime(100);dataFrames(engine)
        engine.feed(packet(0x40,ack=99),105)
        assertEquals(110L,engine.nextDeadlineMillis())
        engine.advanceTime(110)
        assertEquals(101,sequence(dataFrames(engine).single()))
    }

    @Test fun anExtendedAckRetryCannotHideAnotherPacketsEarlierDeadline() {
        val engine=link()
        engine.sendControl(byteArrayOf(1),0);engine.sendControl(byteArrayOf(2),10)
        dataFrames(engine)
        engine.feed(packet(0x20,payload=byteArrayOf(100)),50)
        assertEquals(100,sequence(dataFrames(engine).single()))
        assertEquals(110L,engine.nextDeadlineMillis())
        engine.advanceTime(110);assertEquals(101,sequence(dataFrames(engine).single()))
    }

    @Test fun cumulativeAckRemovesAllConfirmedPacketsAfterSelectiveRetransmission() {
        val engine=link()
        engine.sendControl(byteArrayOf(1),0);engine.sendControl(byteArrayOf(2),10)
        dataFrames(engine)
        engine.feed(packet(0x20,payload=byteArrayOf(101)),50);dataFrames(engine)
        engine.feed(packet(0x40,ack=101),60);dataFrames(engine)
        assertNull(engine.nextDeadlineMillis())
        engine.advanceTime(10_000)
        assertTrue(dataFrames(engine).isEmpty())
        assertEquals(Iap2LinkEngine.State.NORMAL,engine.state())
    }

    @Test fun cumulativeAckAlsoClearsPacketsAcrossSequenceWrap() {
        val engine=link()
        repeat(155){index->
            engine.sendControl(byteArrayOf(7),index.toLong())
            val seq=sequence(dataFrames(engine).single())
            engine.feed(packet(0x40,ack=seq),index.toLong());dataFrames(engine)
        }
        repeat(3){engine.sendControl(byteArrayOf(it.toByte()),200L+it)}
        val outgoing=dataFrames(engine)
        assertEquals(listOf(255,0,1),outgoing.map(::sequence))
        engine.feed(packet(0x40,ack=1),210);dataFrames(engine)
        assertNull(engine.nextDeadlineMillis())
        engine.advanceTime(1000);assertTrue(dataFrames(engine).isEmpty())
    }

    @Test fun aOnePacketWindowQueuesTheSecondUntilTheFirstIsAcknowledged() {
        val engine=link(window=1)
        engine.sendControl(byteArrayOf(1),0);engine.sendControl(byteArrayOf(2),1)
        assertEquals(listOf(100),dataFrames(engine).map(::sequence))
        engine.feed(packet(0x40,ack=100),2)
        assertEquals(listOf(101),dataFrames(engine).map(::sequence))
        engine.feed(packet(0x40,ack=101),3)
        assertNull(engine.nextDeadlineMillis())
    }

    @Test fun aZeroAdvertisedWindowStillDrainsPreHandshakeAndSubsequentQueuedPackets() {
        val engine=Iap2LinkEngine();engine.start(true,0);engine.takeOutput()
        engine.sendControl(byteArrayOf(1),0);engine.sendControl(byteArrayOf(2),0)
        assertTrue(dataFrames(engine).isEmpty())
        val sync=Iap2LinkEngine.SynchronizationPayload(0,4096,100,50,4,3,
            listOf(Iap2LinkEngine.SessionDescriptor(10,0,2)))
        engine.feed(packet(0xc0,payload=sync.encode()),1)
        assertEquals(listOf(100),dataFrames(engine).map(::sequence))
        engine.feed(packet(0x40,ack=100),2)
        assertEquals(listOf(101),dataFrames(engine).map(::sequence))
        engine.feed(packet(0x40,ack=101),3)
        assertNull(engine.nextDeadlineMillis())
    }

    @Test fun theLargestWindowCannotWrapOutstandingCountToZero() {
        val engine=link(window=255)
        repeat(256){engine.sendControl(byteArrayOf(it.toByte()),0)}
        val first=dataFrames(engine)
        assertEquals(255,first.size)
        engine.feed(packet(0x40,ack=100),1)
        assertEquals(listOf(99),dataFrames(engine).map(::sequence))
        engine.feed(packet(0x40,ack=99),2)
        assertNull(engine.nextDeadlineMillis())
    }

    @Test fun staleAndFutureAcknowledgementsCannotRetireAnUnconfirmedPacket() {
        for(ack in listOf(80,98,101,255)) {
            val engine=link();engine.sendControl(byteArrayOf(7),0);dataFrames(engine)
            engine.feed(packet(0x40,ack=ack),50);dataFrames(engine)
            assertEquals("ACK=$ack",100L,engine.nextDeadlineMillis())
            engine.advanceTime(100)
            assertEquals(100,sequence(dataFrames(engine).single()))
            engine.feed(packet(0x40,ack=100),110);dataFrames(engine)
            assertNull(engine.nextDeadlineMillis())
        }
    }

    @Test fun outOfWindowAcknowledgementsStayInvalidAcrossSequenceWrap() {
        val engine=link()
        repeat(155){index->
            engine.sendControl(byteArrayOf(7),index.toLong())
            val seq=sequence(dataFrames(engine).single())
            engine.feed(packet(0x40,ack=seq),index.toLong());dataFrames(engine)
        }
        repeat(3){engine.sendControl(byteArrayOf(it.toByte()),200L+it)}
        assertEquals(listOf(255,0,1),dataFrames(engine).map(::sequence))
        for(ack in listOf(253,2)) {
            engine.feed(packet(0x40,ack=ack),210);dataFrames(engine)
            assertEquals(300L,engine.nextDeadlineMillis())
        }
        engine.feed(packet(0x40,ack=0),220);dataFrames(engine)
        assertEquals(302L,engine.nextDeadlineMillis())
        engine.feed(packet(0x40,ack=255),230);dataFrames(engine)
        assertEquals(302L,engine.nextDeadlineMillis())
        engine.feed(packet(0x40,ack=1),240);dataFrames(engine)
        assertNull(engine.nextDeadlineMillis())
    }

    @Test fun synchronizationRequiresAnAcknowledgementOfTheSentSynchronization() {
        val engine=Iap2LinkEngine();engine.start(true,0);engine.takeOutput()
        val sync=Iap2LinkEngine.SynchronizationPayload(8,4096,100,50,4,3,
            listOf(Iap2LinkEngine.SessionDescriptor(10,0,2)))
        engine.feed(packet(0xc0,ack=0,payload=sync.encode()),0)
        assertEquals(Iap2LinkEngine.State.NEGOTIATING,engine.state())
        assertEquals(500L,engine.nextDeadlineMillis())
        engine.feed(packet(0x40,ack=99),1)
        assertEquals(Iap2LinkEngine.State.NORMAL,engine.state())
    }

    @Test fun extendedAckPayloadIsHandledAsLinkControlNotSessionData() {
        for(flags in listOf(0x20,0x60)) {
            val engine=link()
            engine.sendControl(byteArrayOf(7),0);dataFrames(engine)
            engine.feed(packet(flags,seq=2,payload=byteArrayOf(100)),50)
            assertEquals(100,sequence(dataFrames(engine).single()))
            assertNull(engine.pollEvent())
            engine.feed(packet(0x40,seq=2,ack=100,session=10,payload=byteArrayOf(42)),60)
            assertArrayEquals(byteArrayOf(42),(engine.pollEvent() as Iap2LinkEngine.Event.Control).bytes)
        }
    }

    @Test fun dataWithoutAckFlagStillReachesItsNegotiatedSession() {
        val engine=link()
        engine.feed(packet(0,seq=2,session=12,payload=byteArrayOf(9)),1)
        val event=engine.pollEvent() as Iap2LinkEngine.Event.Session
        assertEquals(12,event.sessionId);assertArrayEquals(byteArrayOf(9),event.bytes)
    }

    @Test fun zeroRetransmissionsKeepsTheExistingNoAckMode() {
        val engine=link(retries=0)
        engine.sendControl(byteArrayOf(7),0);assertEquals(1,dataFrames(engine).size)
        assertNull(engine.nextDeadlineMillis())
        engine.advanceTime(10_000);assertEquals(Iap2LinkEngine.State.NORMAL,engine.state())
    }

    private fun link(retries:Int=4,window:Int=8):Iap2LinkEngine {
        val engine=Iap2LinkEngine();engine.start(true,0);engine.takeOutput()
        val sync=Iap2LinkEngine.SynchronizationPayload(window,4096,100,50,retries,3,
            listOf(Iap2LinkEngine.SessionDescriptor(10,0,2),Iap2LinkEngine.SessionDescriptor(12,1,2)))
        engine.feed(packet(0xc0,payload=sync.encode()),0)
        assertEquals(Iap2LinkEngine.State.NORMAL,engine.state())
        while(engine.pollEvent()!=null)Unit
        engine.takeOutput();return engine
    }
    private fun sequence(frame:ByteArray)=frame[5].toInt() and 0xff
    private fun dataFrames(engine:Iap2LinkEngine):List<ByteArray> {
        val wire=engine.takeOutput();val frames=mutableListOf<ByteArray>();var at=0
        while(at<wire.size){
            val size=((wire[at+2].toInt() and 0xff) shl 8) or (wire[at+3].toInt() and 0xff)
            assertTrue(size>=9 && at+size<=wire.size)
            if(size>9 && wire[at+7].toInt()==10)frames+=wire.copyOfRange(at,at+size)
            at+=size
        }
        return frames
    }
    private fun packet(control:Int,seq:Int=1,ack:Int=99,session:Int=0,payload:ByteArray?=null):ByteArray {
        val size=9+(payload?.let{it.size+1}?:0)
        val header=byteArrayOf(0xff.toByte(),0x5a,(size ushr 8).toByte(),size.toByte(),
            control.toByte(),seq.toByte(),ack.toByte(),session.toByte(),0)
        header[8]=checksum(header.copyOf(8))
        return if(payload==null)header else header+payload+checksum(payload)
    }
    private fun checksum(bytes:ByteArray)=(-bytes.sumOf{it.toInt() and 0xff}).toByte()
}
