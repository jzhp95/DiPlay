package com.shilapi.xcertplay.airplay

import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class AirPlayPeerIsolationTest {
    @Test fun wrongPeerDoesNotOccupyTheSlotBeforeLegitimatePeerArrives() {
        val expected=InetAddress.getByName("127.0.0.1")
        val wrongAddress=InetAddress.getByName("192.0.2.10")
        var wrongClosed=false;var accepted=0
        val wrong=object:Socket(){
            override fun getInetAddress()=wrongAddress
            override fun close(){wrongClosed=true}
        }
        val good=object:Socket(){override fun getInetAddress()=expected}
        val queue=java.util.ArrayDeque<Socket>().apply{add(wrong);add(good)}
        val server=object:ServerSocket(){override fun accept():Socket{accepted++;return queue.removeFirst()}}
        try {
            val rejected=mutableListOf<InetAddress?>()
            assertSame(good,server.acceptFrom(expected){rejected.add(it)})
            assertTrue(wrongClosed);assertEquals(listOf(wrongAddress),rejected);assertEquals(2,accepted)
        } finally {server.close();good.close()}
    }
    @Test fun actualLoopbackPeerCanStillConnect() {
        val loopback=InetAddress.getLoopbackAddress();val pool=Executors.newSingleThreadExecutor()
        ServerSocket(0,4,loopback).use{server->
            val accepted=pool.submit<Socket>{server.acceptFrom(loopback)}
            try{Socket(loopback,server.localPort).use{accepted.get(5,TimeUnit.SECONDS).use{peer->assertEquals(loopback,peer.inetAddress)}}}
            finally{pool.shutdownNow()}
        }
    }
    @Test fun shortAuthenticatedVideoBodyCannotBeReturnedAsPlaintext() {
        try {
            ScreenCodec.decryptFrame(ByteArray(32),0,ByteArray(128) /* screen wire header */,byteArrayOf(1,2,3))
            fail("short authentication tag must be rejected")
        } catch(expected:Exception){assertTrue(expected is java.security.GeneralSecurityException || expected is IllegalArgumentException)}
    }
}
