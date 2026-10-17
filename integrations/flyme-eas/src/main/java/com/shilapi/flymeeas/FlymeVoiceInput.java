package com.shilapi.flymeeas;

import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.IntPredicate;

/** Optional XUI voice-key interception; media-key ownership remains with EAS. */
public final class FlymeVoiceInput implements AutoCloseable {
 private static final String INPUT="com.ecarx.xui.adaptapi.input.Input";
 private static final String CALLBACK="com.ecarx.xui.adaptapi.input.IKeyCallback";
 private final Context context;
 private final IntPredicate pressed;
 private final Consumer<String> log;
 private final HandlerThread thread=new HandlerThread("Flyme-voice-input");
 private final Handler worker;
 private final Set<Integer> held=new HashSet<>();
 private volatile boolean closed;
 private Object input,callback;
 private Class<?> callbackType;
 private volatile int[] requested=new int[0];
 public FlymeVoiceInput(Context context,IntPredicate pressed,Consumer<String> log){
  this.context=context.getApplicationContext();this.pressed=pressed;this.log=log;
  thread.start();worker=new Handler(thread.getLooper());worker.post(this::register);
 }
 private void register(){
  if(closed)return;
  try{
   int vendor=200231;
   try{vendor=Class.forName("com.ecarx.xui.adaptapi.input.KeyCode").getField("KEYCODE_R_VOICE_ASSIST").getInt(null);}catch(ReflectiveOperationException ignored){}
   if(vendor<=0)vendor=200231;
   requested=vendor==231?new int[]{231}:new int[]{231,vendor};
   callbackType=Class.forName(CALLBACK);
   input=Class.forName(INPUT).getMethod("create",Context.class).invoke(null,context);
   if(input==null){log.accept("voice input unavailable: Input.create returned null");return;}
   ClassLoader loader=callbackType.getClassLoader();if(loader==null)loader=context.getClassLoader();
   callback=Proxy.newProxyInstance(loader,new Class<?>[]{callbackType},this::invoke);
   log.accept("voice interception begin requested="+Arrays.toString(requested));
   Object result=input.getClass().getMethod("requestKeysInterception",int[].class,callbackType).invoke(input,requested,callback);
   log.accept("voice interception accepted="+(result instanceof int[]?Arrays.toString((int[])result):"unknown (not an int[])")+"; registration does not prove key delivery");
  }catch(ReflectiveOperationException|RuntimeException|LinkageError e){log.accept("voice interception unavailable: "+e.getClass().getSimpleName()+" "+e.getMessage()+" cause="+e.getCause());}
 }
 private Object invoke(Object proxy,Method method,Object[] args){
  String name=method.getName();
  if(name.equals("toString"))return "AppleCarPlayVoiceCallback";
  if(name.equals("hashCode"))return System.identityHashCode(proxy);
  if(name.equals("equals"))return args!=null&&args.length>0&&proxy==args[0];
  if(args==null||args.length==0||!(args[0] instanceof Integer)||method.getReturnType()!=boolean.class)return false;
  int code=(Integer)args[0];
  synchronized(held){
   if(closed)return false;
   boolean allowed=false;for(int key:requested)if(key==code)allowed=true;
   if(!allowed)return false;
   if(name.equals("onKeyReleased")||name.equals("onKeyShortReleased")||name.equals("onKeyLongReleased"))return held.remove(code);
   if(!name.equals("onKeyPressed")&&!name.equals("onKeyShortPressed")&&!name.equals("onKeyLongPressed"))return false;
   if(held.contains(code))return true;
   boolean consumed=pressed.test(code);
   if(consumed&&!closed)held.add(code);
   log.accept("voice callback method="+name+" code="+code+" consumed="+consumed);
   return consumed&&!closed;
  }
 }
 @Override public void close(){
  if(closed)return;closed=true;
  worker.post(()->{
   try{if(input!=null&&callback!=null)input.getClass().getMethod("abandonKeysInterception",callbackType).invoke(input,callback);}
   catch(ReflectiveOperationException|RuntimeException e){log.accept("voice interception release failed: "+e.getClass().getSimpleName());}
   finally{input=null;callback=null;thread.quitSafely();log.accept("voice interception stopped");}
  });
 }
}
