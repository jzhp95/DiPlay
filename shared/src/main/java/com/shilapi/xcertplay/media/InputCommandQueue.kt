package com.shilapi.xcertplay.media

import com.shilapi.xcertplay.airplay.AirPlayContact
import java.util.ArrayDeque
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

data class InputCommandMetrics(val pending:Int,val coalescedMoves:Long,val staleDropped:Long,val overflow:Long,
    val queueWaitNs:Long,val sendNs:Long,val oldestAgeNs:Long,val written:Boolean?,val accepted:Boolean?=null)

/** One worker and at most 64 waiting inputs. Executor acceptance precedes queue acceptance. */
internal class InputCommandQueue<S:Any>(
    private val current:()->S?,private val usable:(S)->Boolean,private val screen:(S)->Any?,
    private val sendTouch:(S,List<AirPlayContact>)->Boolean,private val fault:(S,String)->Unit,
    private val report:(InputCommandMetrics)->Unit={},
    private val executor:ExecutorService=Executors.newSingleThreadExecutor{Thread(it,"carplay-input").apply{isDaemon=true}},
    private val nanoTime:()->Long=System::nanoTime,private val capacity:Int=64,
) {
    private class Entry<S>(val session:S,val screen:Any?,val touch:Boolean,val move:Boolean,val gesture:Long,
        val ids:Set<Int>,var enqueuedNs:Long,val onDiscard:()->Unit,var action:()->Boolean?)
    private val lock=Any()
    private val pending=ArrayDeque<Entry<S>>()
    private var draining=false
    private var closed=false
    private var failed=false
    private var previousSession:S?=null
    private var previousScreen:Any?=null
    private var downIds=emptySet<Int>()
    private var gesture=0L
    private var coalesced=0L
    private var stale=0L
    private var overflows=0L
    init{require(capacity>0)}
    fun touch(contacts:List<AirPlayContact>):Boolean {
        val session=current()?.takeIf(usable)?:return false
        val token=screen(session)?:return false
        val snapshot=contacts.toList()
        return enqueue(session,token,snapshot){sendTouch(session,snapshot)}
    }
    fun execute(action:()->Unit) {
        val session=current()?.takeIf(usable)?:throw RejectedExecutionException("no_input_session")
        if(!enqueue(session,null,null){action();null})throw RejectedExecutionException("input_not_accepted")
    }
    fun executeTracked(action:()->Unit,onDiscard:()->Unit):Boolean {
        val discarded=java.util.concurrent.atomic.AtomicBoolean(false)
        val cancel:()->Unit={if(discarded.compareAndSet(false,true))onDiscard()}
        val session=current()?.takeIf(usable)
        if(session==null){runCatching{cancel()};return false}
        val accepted=enqueue(session,null,null,cancel){action();null}
        if(!accepted)runCatching{cancel()}
        return accepted
    }
    private fun discard(entries:List<Entry<S>>){entries.forEach{runCatching{it.onDiscard()}}}
    private fun enqueue(session:S,token:Any?,contacts:List<AirPlayContact>?,onDiscard:()->Unit={},action:()->Boolean?):Boolean {
        var overflow=false
        val discarded=mutableListOf<Entry<S>>()
        val accepted=synchronized(lock){
            if(closed || failed)return@synchronized false
            if(previousSession!==session){
                stale+=pending.size;discarded.addAll(pending);pending.clear();downIds=emptySet();gesture++
                previousSession=session;previousScreen=screen(session)
            }
            if(contacts!=null && previousScreen!==token){
                val old=pending.size
                pending.removeIf{it.touch && it.screen!==token}
                stale+=old-pending.size;downIds=emptySet();gesture++;previousScreen=token
            }
            val ids=contacts?.filter{it.down}?.map{it.id}?.toSet().orEmpty()
            if(contacts!=null && downIds.isEmpty() && ids.isNotEmpty())gesture++
            val move=contacts!=null && ids.isNotEmpty() && ids==downIds && contacts.all{it.down}
            val tail=pending.peekLast()
            if(move && tail!=null && tail.move && tail.session===session && tail.screen===token && tail.gesture==gesture && tail.ids==ids){
                tail.action=action;tail.enqueuedNs=nanoTime();coalesced++;downIds=ids
                return@synchronized true
            }
            if(pending.size>=capacity){failed=true;overflows++;discarded.addAll(pending);pending.clear();overflow=true;return@synchronized false}
            val entry=Entry(session,token,contacts!=null,move,gesture,ids,nanoTime(),onDiscard,action)
            pending.addLast(entry)
            if(!draining){
                draining=true
                try{executor.execute{drain()}}
                catch(_:RejectedExecutionException){discarded.addAll(pending);pending.clear();draining=false;return@synchronized false}
            }
            if(contacts!=null)downIds=ids
            true
        }
        discard(discarded)
        if(!accepted)publishMetrics(accepted=false)
        if(overflow)runCatching{fault(session,"input_backpressure")}
        return accepted
    }
    private fun drain(){
        while(true){
            val entry=synchronized(lock){
                if(closed || failed || pending.isEmpty()){draining=false;return}
                pending.removeFirst()
            }
            if(current()!==entry.session || !usable(entry.session) || (entry.touch && screen(entry.session)!==entry.screen)){
                synchronized(lock){stale++};runCatching{entry.onDiscard()};publishMetrics();continue
            }
            val dequeued=nanoTime()
            var written:Boolean?=null
            try{written=entry.action()}
            catch(_:Exception){
                val dropped=synchronized(lock){failed=true;pending.toList().also{pending.clear()}}
                runCatching{entry.onDiscard()};discard(dropped)
                runCatching{fault(entry.session,"input_send_failed")}
            }
            val end=nanoTime()
            val metrics=synchronized(lock){InputCommandMetrics(pending.size,coalesced,stale,overflows,
                (dequeued-entry.enqueuedNs).coerceAtLeast(0),(end-dequeued).coerceAtLeast(0),
                pending.peekFirst()?.let{(end-it.enqueuedNs).coerceAtLeast(0)}?:0,written,true)}
            runCatching{report(metrics)}
        }
    }
    private fun publishMetrics(accepted:Boolean?=null) {
        val now=nanoTime()
        val metrics=synchronized(lock){InputCommandMetrics(pending.size,coalesced,stale,overflows,0,0,
            pending.peekFirst()?.let{(now-it.enqueuedNs).coerceAtLeast(0)}?:0,null,accepted)}
        runCatching{report(metrics)}
    }
    fun shutdownNow(){
        val dropped=synchronized(lock){closed=true;pending.toList().also{pending.clear();downIds=emptySet()}}
        discard(dropped);executor.shutdownNow()
    }
}
