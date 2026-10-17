package com.shilapi.flymeeas;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.util.Log;
import java.util.ArrayDeque;

/** One instance per CarPlay controller. No service, wake lock, or process-global focus owner. */
public final class FlymeWheelBridge implements AutoCloseable {
 public interface Host {
  boolean ready();
  void mediaButton(int index);
  PlaybackSnapshot playback();
  default void diagnostic(String message) {}
  default boolean canHandleVoiceKey(){return false;}
  default void voiceKey(int keyCode){}
 }
 private static final String TAG="FlymeWheel";
 private static final String PERMISSION="ecarx.oem.permission.OPENAPI_MEDIACENTER_PERMISSION";
 private static final ArrayDeque<String> DIAGNOSTICS=new ArrayDeque<>();
 private static final java.util.Map<String,String> STATES=new java.util.LinkedHashMap<>();
 private final Handler main=new Handler(Looper.getMainLooper());
 private final Context context;
 private final Host host;
 private final SessionGate gate=new SessionGate();
 private final KeyPairs keys=new KeyPairs();
 private volatile boolean closed;
 private volatile boolean owned;
 private EasAdapter adapter;
 private FlymeVoiceInput voiceInput;
 private boolean attempted;
 private boolean eligible;
 private long lastMetadataPoll;
 private final Runnable tick=new Runnable(){public void run(){
  if(closed)return;
  if(!host.ready()) {
   // A live controller can lose its session before its Activity receives detach.
   if(adapter!=null){close();return;}
  }else if(!attempted){
   attempted=true;gate.start();connect();
  }
  if(!closed&&adapter!=null&&host.ready()&&owned){
   long now=android.os.SystemClock.elapsedRealtime();
   if(now-lastMetadataPoll>=5000){lastMetadataPoll=now;adapter.updatePlayback(host.playback());}
  }
  if(!closed)main.postDelayed(this,1000);
 }};
 public FlymeWheelBridge(Context context,Host host){
  this.context=context.getApplicationContext();this.host=host;
  main.post(()->{if(closed)return;eligible=eligible();if(eligible)tick.run();});
 }
 private boolean eligible(){
  try{
   PackageManager pm=context.getPackageManager();
   android.content.pm.ApplicationInfo app=pm.getApplicationInfo(context.getPackageName(),PackageManager.GET_META_DATA);
   boolean enabled=app.metaData!=null&&app.metaData.getBoolean("com.shilapi.flymeeas.ENABLED",false);
   boolean system=Process.myUid()%100000==1000;
   boolean platform=pm.checkSignatures("android",context.getPackageName())==PackageManager.SIGNATURE_MATCH;
   boolean permission=context.checkSelfPermission(PERMISSION)==PackageManager.PERMISSION_GRANTED;
   state("identity","enabled="+enabled+" uid="+Process.myUid()+" platform="+platform+" oemPermission="+permission);
   if(!enabled||!system||!platform||!permission)return false;
   pm.getApplicationInfo("ecarx.xsf.mediacenter",0);
   return true;
  }catch(Exception e){note("capability blocked: "+e);return false;}
 }
 private void connect(){
  if(closed)return;
  note("register source=36 package="+context.getPackageName());
  voiceInput=new FlymeVoiceInput(context,code->{
   if(closed||!host.ready()||!host.canHandleVoiceKey())return false;
   main.post(()->{if(!closed&&host.ready()&&host.canHandleVoiceKey())host.voiceKey(code);});
   return true;
  },this::note);
  adapter=new EasAdapter(context,new EasAdapter.Sink(){
   private void deliver(Runnable action){main.post(()->{if(!closed)action.run();});}
   public void event(int action,int raw,String source){deliver(()->{
    boolean accepted=gate.accepts(action,host.ready())&&keys.allow(action,true,android.os.SystemClock.elapsedRealtime());
    note("raw="+raw+" -> media="+action+" accepted="+accepted);
    if(accepted)host.mediaButton(action);
   });}
   public boolean canDispatch(int action){return !closed&&host.ready()&&(action==1||action==2||action==4||action==5);}
   public void registered(){deliver(()->{note("registered=true source=36");gate.registered=true;adapter.updatePlayback(host.playback());acquire();});}
   public boolean shouldRequestFocus(){return !closed&&host.ready()&&gate.mayExecuteRequest();}
   public void requestFinished(){deliver(()->main.postDelayed(FlymeWheelBridge.this::acquire,800));}
   public void focus(String pkg){
    if(closed)return;
    // Empty is unknown, not an authoritative source switch.
    if(pkg!=null&&!pkg.trim().isEmpty()){gate.focus(context.getPackageName().equals(pkg));owned=gate.owned;}
   }
   public void disconnected(){deliver(()->{note("connection invalidated; no automatic reconnect");close();});}
   public void status(String stage,String result){state(stage,result);}
   public void diagnostic(String message){note(message);}
  });
  adapter.updatePlayback(host.playback());
  adapter.connect(36,false);
 }
 private void acquire(){
  if(closed||!host.ready()||!gate.request())return;
  note("request focus source=36");
  adapter.requestFocus();
  // Next attempt is scheduled only after this worker request completes.
 }
 /** Called from the host's existing metadata observer; it never reacquires media focus. */
 public void update(){main.post(()->{
  if(!closed&&adapter!=null&&host.ready())adapter.updatePlayback(host.playback());
 });}
 public boolean allowHardware(int action){
  if(closed||!gate.active||!gate.registered||!host.ready())return true;
  boolean allow=!owned&&keys.allow(action,false,android.os.SystemClock.elapsedRealtime());
  if(!allow)note("suppress duplicate hardware media="+action);
  return allow;
 }
 public boolean ownsFocus(){return owned&&!closed;}
 @Override public void close(){
  // Invalidate callback eligibility synchronously, even when detach runs off the main thread.
  if(closed)return;closed=true;owned=false;
  main.post(()->{gate.stop();main.removeCallbacksAndMessages(null);
   if(voiceInput!=null){voiceInput.close();voiceInput=null;}
   if(adapter!=null){adapter.close();adapter=null;}state("lifecycle","session stopped; unregister queued; no focus retry");
  });
 }
 private void note(String line){
  synchronized(FlymeWheelBridge.class){
   Log.i(TAG,line);DIAGNOSTICS.addLast(android.os.SystemClock.elapsedRealtime()+" "+line);
   while(DIAGNOSTICS.size()>160)DIAGNOSTICS.removeFirst();
  }
  try{host.diagnostic(line);}catch(RuntimeException ignored){}
 }
 private void state(String key,String value){synchronized(FlymeWheelBridge.class){STATES.put(key,value);}note(key+"="+value);}
 public static synchronized String report(){
  StringBuilder text=new StringBuilder("Flyme EAS (source 36)\n");
  for(java.util.Map.Entry<String,String> entry:STATES.entrySet())text.append(entry.getKey()).append('=').append(entry.getValue()).append('\n');
  for(String line:DIAGNOSTICS)text.append(line).append('\n');
  return text.toString();
 }
}
