package com.shilapi.flymeeas;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.RemoteException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONObject;

/** Optional state notification only. Never registers a source, requests focus or handles keys. */
public final class EasPlayStateChannel implements AutoCloseable {
 public interface Diagnostics { void report(String message); }
 private static volatile EasPlayStateChannel latest;
 private static final String FRAME="com.ecarx.eas.framework.sdk.IEASFrameworkService";
 private final Context context;
 private final Diagnostics diagnostics;
 private final HandlerThread thread=new HandlerThread("EAS-play-state");
 private final Handler worker;
 private final Handler watchdog=new Handler(Looper.getMainLooper());
 private final AtomicInteger generation=new AtomicInteger();
 private volatile boolean closed;
 private volatile boolean playing;
 private volatile ServiceConnection connection;
 // Below fields belong to worker. A blocked Binder never blocks the existing EAS key worker.
 private IBinder framework;
 private boolean initialized;
 private Boolean sent;
 private int failures;
 private final Runnable retry=this::bind;
 public EasPlayStateChannel(Context context,Diagnostics diagnostics){
  this.context=context.getApplicationContext();this.diagnostics=diagnostics;
  thread.start();worker=new Handler(thread.getLooper());synchronized(EasPlayStateChannel.class){latest=this;}
 }
 public void start(){worker.post(()->{if(!closed&&latest==this&&connection==null)bind();});}
 public void update(boolean playing){
  if(closed)return;
  this.playing=playing;
  worker.post(()->publish(false));
 }
 /** Reassert after OEM source selection/metadata has settled, even when playing is unchanged. */
 public void refresh(){if(!closed)worker.post(()->publish(true));}
 private void log(String message){diagnostics.report(message);}
 private boolean current(int g){return !closed&&latest==this&&generation.get()==g;}
 private void bind(){
  if(closed||latest!=this||connection!=null)return;
  final int g=generation.incrementAndGet();
  framework=null;initialized=false;sent=null;
  ServiceConnection next=new ServiceConnection(){
   @Override public void onServiceConnected(ComponentName name,IBinder binder){worker.post(()->{
    if(!current(g))return;
    try{
     // init enforces FRAME through the interface token under the same timeout as every call.
     framework=binder;
     transact(g,binder,1,p->p.writeStringArray(new String[]{"mediacenter"}),p->null,false);
     if(!current(g))return;
     initialized=true;log("通道已连接；补发最新状态");publish(true);
    }catch(Exception e){if(current(g))failed("初始化失败 "+e.getClass().getSimpleName());}
   });}
   @Override public void onServiceDisconnected(ComponentName name){lost("服务断开");}
   @Override public void onBindingDied(ComponentName name){lost("绑定失效");}
   @Override public void onNullBinding(ComponentName name){lost("null binding");}
   private void lost(String reason){worker.post(()->{if(current(g))failed(reason);});}
  };
  connection=next;
  try{
   Intent intent=new Intent("com.ecarx.easframework.intent.action.EASFRAMEWORK").setPackage("com.ecarx.sdk.openapi");
   if(!context.bindService(intent,next,Context.BIND_AUTO_CREATE)){failed("bindService=false");return;}
   log("正在连接 Framework");
   worker.postDelayed(()->{if(current(g)&&!initialized)failed("连接超时5000ms");},5000);
  }catch(Exception e){failed("绑定失败 "+e.getClass().getSimpleName());}
 }
 private void publish(boolean force){
  if(closed||latest!=this||!initialized||framework==null)return;
  boolean value=playing;
  if(!force&&sent!=null&&sent==value)return;
  int g=generation.get();
  try{
   int code=send(g,framework,value,false);
   if(!current(g))return;
   if(code!=200){failed("updatePlayState 被拒 code="+code+" playing="+value);return;}
   sent=value;failures=0;
   log("updatePlayState accepted code=200 playing="+value+" displayId=0（接口受理，非屏幕显示确认）");
  }catch(Exception e){if(current(g))failed("updatePlayState 异常 "+e.getClass().getSimpleName()+" playing="+value);}
 }
 private int send(int g,IBinder binder,boolean value,boolean stopping)throws Exception{
  byte[] json=new JSONObject().put("packageName",context.getPackageName())
   .put("playState",value?1:0).put("displayId",0).toString().getBytes(StandardCharsets.UTF_8);
  return transact(g,binder,4,p->{
   p.writeInt(1);p.writeString("mediacenter");p.writeString("MediaCenterAPI");
   p.writeString("updatePlayState");p.writeByteArray(json);p.writeByteArray(new byte[0]);
  },p->{
   if(p.readInt()==0)return -1;
   int code=p.readInt();p.readString();p.createByteArray();
   // Remaining optional vendor Parcelable is intentionally not deserialized.
   return code;
  },stopping);
 }
 private interface Write { void write(Parcel p)throws Exception; }
 private interface Read<T> { T read(Parcel p)throws Exception; }
 private <T>T transact(int g,IBinder binder,int code,Write write,Read<T> read,boolean stopping)throws Exception{
  if(!stopping&&!current(g))throw new RemoteException("状态通道已失效");
  Parcel data=Parcel.obtain(),reply=Parcel.obtain();
  java.util.concurrent.atomic.AtomicBoolean done=new java.util.concurrent.atomic.AtomicBoolean();
  Runnable timeout=()->{
   if(done.get()||generation.get()!=g)return;
   if(generation.compareAndSet(g,g+1)){
    log("Binder超时4000ms code="+code+"；状态通道降级，方控/媒体注册不受影响；晚到结果无效");
    unbind();
    // Android cannot cancel Binder. Retry on this same worker only after it returns.
    worker.post(()->{if(!closed)failed("超时事务已返回");});
   }
  };
  watchdog.postDelayed(timeout,4000);
  try{
   data.writeInterfaceToken(FRAME);write.write(data);
   if(!binder.transact(code,data,reply,0))throw new RemoteException("不支持事务 #"+code);
   reply.readException();T result=read.read(reply);
   if(!stopping&&!current(g))throw new RemoteException("迟到结果已忽略");
   return result;
  }finally{
   done.set(true);watchdog.removeCallbacks(timeout);data.recycle();reply.recycle();
   // An old in-flight call may have been applied after a replacement session's update.
   EasPlayStateChannel active=latest;
   if(active!=null&&active!=this)active.worker.post(()->active.publish(true));
  }
 }
 private void failed(String reason){
  if(closed)return;
  generation.incrementAndGet();initialized=false;framework=null;sent=null;unbind();
  long delay=failures++==0?1000:failures==2?3000:10000;
  log(reason+"；"+delay+"ms后重试，仅重连播放状态通道");
  worker.removeCallbacks(retry);worker.postDelayed(retry,delay);
 }
 private synchronized void unbind(){
  ServiceConnection old=connection;connection=null;
  if(old!=null)try{context.unbindService(old);}catch(IllegalArgumentException ignored){}
 }
 @Override public void close(){
  if(closed)return;
  closed=true;generation.incrementAndGet();
  worker.removeCallbacks(retry);
  unbind(); // Release the service even if a preceding synchronous Binder call is stuck.
  worker.post(()->{
   try{
    // Ordered after any in-flight playing notification; do not bind just to send stop.
    if((latest==this||latest==null)&&initialized&&framework!=null){
     int code=send(generation.get(),framework,false,true);
     log("session-stop updatePlayState playing=false code="+code+"（尽力通知）");
    }else log(latest!=this?"session-stop 已被新会话接管；跳过旧停止通知":"session-stop 未连接；停止通知未送达");
   }catch(Exception e){log("session-stop 通知失败 "+e.getClass().getSimpleName());}
   finally{initialized=false;framework=null;sent=null;unbind();
    synchronized(EasPlayStateChannel.class){if(latest==this)latest=null;}
    thread.quitSafely();}
  });
 }
}
