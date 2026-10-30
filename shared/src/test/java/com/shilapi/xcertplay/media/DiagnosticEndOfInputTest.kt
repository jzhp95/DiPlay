package com.shilapi.xcertplay.media

import org.junit.Assert.*
import org.junit.Test

class DiagnosticEndOfInputTest {
    @Test fun explicitDiagnosticEndStaysOrderedAndIsNotDiscardedWithAReferenceChain(){
        val queue=VideoDecodeQueue()
        queue.offer(VideoJob.Frame(byteArrayOf(1)))
        queue.offer(VideoJob.EndOfDiagnosticInput)
        queue.offer(VideoJob.Frame(byteArrayOf(2)))
        queue.discardCurrentChain()
        assertSame(VideoJob.EndOfDiagnosticInput,queue.poll(0))
        assertArrayEquals(byteArrayOf(2),(queue.poll(0) as VideoJob.Frame).nalus)
    }
}
