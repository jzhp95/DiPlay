package com.shilapi.xcertplay.airplay

import java.net.Socket
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.io.IOException
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],manifest=Config.NONE)
class AirPlayEventSequenceTest {
    private class Event(val failWrite:Boolean=false,val failFlush:Boolean=false):Socket(){
        val bytes=ByteArrayOutputStream()
        override fun getOutputStream():OutputStream=object:OutputStream(){
            override fun write(value:Int){if(failWrite)throw IOException("fixture write");bytes.write(value)}
            override fun write(data:ByteArray,offset:Int,length:Int){if(failWrite)throw IOException("fixture write");bytes.write(data,offset,length)}
            override fun flush(){if(failFlush)throw IOException("fixture flush")}
        }
    }
    private fun session():AirPlaySession=AirPlaySession(Socket(),
        AirPlayConfig("test","02:00:00:00:00:02","02:00:00:00:00:01","1",main=AirPlayDisplayConfig(800,480)),
        AirPlayIdentity.generate(),PairingStore(),null,object:AirPlaySessionListener{},object:AirPlayMediaHandler{})
    private fun wire(s:AirPlaySession,e:Event){
        for((k,v)in listOf("eventSocket" to e,"eventCipher" to ControlCipher(ByteArray(32),ByteArray(32))))
            s.javaClass.getDeclaredField(k).apply{isAccessible=true}.set(s,v)
    }
    private fun seq(s:AirPlaySession)=s.javaClass.getDeclaredField("eventCseq").apply{isAccessible=true}.getInt(s)
    @Test fun commitsOnlyAfterWriteAndFlushAndEncryptedHeadersStaySequential(){
        val s=session();val e=Event();wire(s,e)
        try{
            assertTrue(s.sendCommand(mapOf("type" to "one")));assertEquals(1,seq(s))
            assertTrue(s.sendCommand(mapOf("type" to "two")));assertEquals(2,seq(s))
            val text=String(ControlCipher(ByteArray(32),ByteArray(32)).decrypt(e.bytes.toByteArray()).data,Charsets.ISO_8859_1)
            assertTrue(text.contains("CSeq: 1\r\n"));assertTrue(text.contains("CSeq: 2\r\n"))
        }finally{s.close()}
    }
    @Test fun writeOrFlushFailureDoesNotCommitAndClosesCipherSession(){
        for(e in listOf(Event(failWrite=true),Event(failFlush=true))){val s=session();wire(s,e)
            assertFalse(s.sendCommand(mapOf("type" to "fixture")));assertEquals(0,seq(s));assertTrue(s.isClosed)
            assertFalse(s.sendCommand(mapOf("type" to "later")));assertEquals(0,seq(s))
        }
    }
    @Test fun absentChannelDoesNotConsumeSequence(){val s=session();try{assertFalse(s.sendCommand(emptyMap()));assertEquals(0,seq(s))}finally{s.close()}}
}
