package com.shilapi.flymeeas;

import android.app.PendingIntent;
import android.net.Uri;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.RemoteException;
import android.os.SystemClock;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/** Minimal, independently reconstructed Binder client for IHU623G OTA 537.
 * No vendor DEX, credential, system identity or signature is used.
 * All transactions are synchronous on a serial worker; a separate watchdog records
 * timeouts and invalidates late results. Android cannot cancel a blocked Binder call.
 */
public final class EasAdapter implements AutoCloseable {
 public interface Sink {
  void event(int action,int raw,String source);
  void status(String stage,String result);
  void diagnostic(String message);
  void registered();
  void focus(String packageName);
  void disconnected();
  boolean canDispatch(int action);
  boolean shouldRequestFocus();
  void requestFinished();
 }
 private static final String FRAME="com.ecarx.eas.framework.sdk.IEASFrameworkService";
 private static final String SUPPORT="com.ecarx.eas.framework.sdk.IEASFrameworkSuppportService";
 private static final String MEDIA="ecarx.xsf.mediacenter.IMediaCenterSvc";
 private static final String MUSIC="ecarx.xsf.mediacenter.IMusicClient";
 private static final String INFO="ecarx.xsf.mediacenter.IMusicPlaybackInfo";
 private final Context context;
 private final Sink sink;
 private final HandlerThread thread=new HandlerThread("EAS-serial");
 private final Handler worker;
 private final Handler watchdog=new Handler(Looper.getMainLooper());
 private final AtomicInteger generation=new AtomicInteger();
 private final List<ServiceConnection> bound=new ArrayList<>();
 private volatile boolean closed;
 private volatile PlaybackSnapshot playback=PlaybackSnapshot.EMPTY;
 private volatile PlaybackInfo playbackInfo;
 private volatile String lastFocusPackage;
 private IBinder media,token;
 private IBinder.DeathRecipient death;
 private int sourceType=6;
 private boolean selectAfterRegister;
 private volatile String stage="未开始";
 public EasAdapter(Context context,Sink sink){
  this.context=context.getApplicationContext();this.sink=sink;thread.start();worker=new Handler(thread.getLooper());
 }
 public void connect(int sourceType,boolean select){
  if(closed)return;
  if(sourceType!=6&&sourceType!=36){sink.status("eas_error","仅支持源类型6/36");return;}
  int g=generation.incrementAndGet();
  worker.post(()->{
   release();if(!current(g))return;
   this.sourceType=sourceType;selectAfterRegister=select;
   sink.status("eas_registration","未注册");sink.status("eas_focus","未知：尚未查询");
   bind(g,false);
  });
 }
 public void updatePlayback(PlaybackSnapshot snapshot){
  if(closed)return;
  playback=snapshot;int g=generation.get();worker.post(()->publishPlayback(g,"iphone-update"));
 }
 private void publishPlayback(int g,String reason){
  if(!current(g)||media==null||token==null)return;
  PlaybackInfo info=new PlaybackInfo(sourceType);playbackInfo=info;
  try{
   Boolean accepted=call(g,media,MEDIA,6,p->{p.writeStrongBinder(token);p.writeStrongBinder(info);},p->p.readInt()!=0);
   call(g,media,MEDIA,10,p->{p.writeStrongBinder(token);p.writeLong(info.snapshot.position);},p->null);
   sink.status("eas_metadata","accepted="+accepted+" reason="+reason+" title="+safe(info.snapshot.title)+" artist="+safe(info.snapshot.artist)+" playing="+info.snapshot.playing+" artwork="+(info.artwork!=null)+" launch="+(info.launch!=null));
   log("metadata update accepted="+accepted+" reason="+reason+" track="+info.snapshot.trackId()+" title="+safe(info.snapshot.title)+" duration="+info.snapshot.duration+" position="+info.snapshot.position+" playing="+info.snapshot.playing);
  }catch(Exception e){error("playback update "+reason,e);}
 }
 public void requestFocus(){int g=generation.get();worker.post(()->{try{request(g);}finally{sink.requestFinished();}});}
 public void queryFocus(){int g=generation.get();worker.post(()->query(g));}
 private boolean current(int g){return !closed&&generation.get()==g;}
 private void log(String s){sink.diagnostic("EAS generation="+generation.get()+" t="+SystemClock.elapsedRealtime()+" "+s);}
 private void error(String where,Throwable e){
  StringBuilder b=new StringBuilder(where);Throwable t=e;
  for(int i=0;t!=null&&i<4;i++,t=t.getCause())b.append(" | ").append(t.getClass().getName()).append(": ").append(t.getMessage());
  sink.status("eas_error",b.toString());log(b.toString());
 }
 private void bind(int g,boolean direct){
  if(!current(g))return;
  stage=direct?"支持服务绑定":"Framework绑定";
  sink.status("eas_connection",stage+"中");
  Intent intent=new Intent(direct?"com.ecarx.eas.core.intent.action.SUPPORT_SERVICE":"com.ecarx.easframework.intent.action.EASFRAMEWORK");
  intent.setPackage(direct?"ecarx.xsf.mediacenter":"com.ecarx.sdk.openapi");
  if(direct)intent.setComponent(new ComponentName("ecarx.xsf.mediacenter","ecarx.xsf.mediacenter.MediaCenterService"));
  final boolean[] delivered={false};
  ServiceConnection connection=new ServiceConnection(){
   @Override public void onServiceConnected(ComponentName name,IBinder binder){worker.post(()->{
    if(!current(g)||delivered[0])return;delivered[0]=true;
    try{
     String descriptor=binder.getInterfaceDescriptor();log("connected component="+name.flattenToShortString()+" descriptor="+descriptor);
     if(direct){
      if(MEDIA.equals(descriptor)){ready(g,binder);return;}
      if(!SUPPORT.equals(descriptor))throw new RemoteException("支持服务descriptor不匹配: "+descriptor);
      IBinder main=call(g,binder,SUPPORT,1,p->{writeMessage(p,true);p.writeInt(android.os.Process.myPid());p.writeInt(android.os.Process.myUid());},p->readSupport(p));
      if(main==null)throw new RemoteException("支持服务getMainBinder为空");ready(g,main);
     }else{
      if(!FRAME.equals(descriptor))throw new RemoteException("Framework descriptor不匹配: "+descriptor);
      call(g,binder,FRAME,1,p->p.writeStringArray(new String[]{"mediacenter"}),p->null);
      fetch(g,binder,0);
     }
    }catch(Exception e){if(current(g)){error(stage,e);if(!direct&&media==null)bind(g,true);else if(media!=null)sink.status("eas_registration","失败："+e.getClass().getSimpleName());else sink.status("eas_connection","失败：支持服务不可用");}}
   });}
   @Override public void onServiceDisconnected(ComponentName name){lost(g,"服务断开 "+name.flattenToShortString());}
   @Override public void onBindingDied(ComponentName name){lost(g,"绑定死亡 "+name.flattenToShortString());}
   @Override public void onNullBinding(ComponentName name){worker.post(()->{if(current(g)){delivered[0]=true;log("null binding "+name);if(!direct)bind(g,true);else sink.status("eas_connection","失败：null binding");}});}
  };
  try{
   log("normal bindService intent="+intent);
   boolean ok=context.bindService(intent,connection,Context.BIND_AUTO_CREATE);
   log("bindService returned="+ok);
   if(!ok){if(!direct)bind(g,true);else sink.status("eas_connection","失败：bindService=false");return;}
   synchronized(bound){bound.add(connection);}
   watchdog.postDelayed(()->worker.post(()->{
    if(current(g)&&!delivered[0]){
     delivered[0]=true;log("绑定超时 5000ms direct="+direct);
     try{context.unbindService(connection);}catch(Exception e){error("解除超时绑定",e);}synchronized(bound){bound.remove(connection);}
     if(!direct)bind(g,true);else sink.status("eas_connection","超时：支持服务未回调");
    }
   }),5000);
  }catch(Exception e){error(stage,e);if(!direct)bind(g,true);else sink.status("eas_connection","失败："+e.getClass().getSimpleName());}
 }
 private void fetch(int g,IBinder framework,int attempt){
  if(!current(g))return;
  try{
   stage="getMainBinder 第"+(attempt+1)+"次";
   IBinder main=call(g,framework,FRAME,4,p->writeMessage(p,false),p->{
    if(p.readInt()==0)return null;
    int code=p.readInt();String message=p.readString();p.createByteArray();
    log("Framework response code="+code+" message="+safe(message));
    String clazz=p.readString();
    if(clazz==null)return null;
    if(!"com.ecarx.sdk.openapi.msg.SupportServiceRetMessage".equals(clazz)&&!"com.ecarx.eas.core.base.SupportServiceRetMessage".equals(clazz))throw new RemoteException("未知返回Parcelable: "+clazz);
    IBinder result=readSupportFields(p);return code==200?result:null;
   });
   if(!current(g))return;
   if(main!=null){ready(g,main);return;}
   if(attempt<2){worker.postDelayed(()->fetch(g,framework,attempt+1),400);return;}
   log("Framework getMainBinder三次为空；尝试正常支持服务绑定");bind(g,true);
  }catch(Exception e){if(current(g)){error(stage,e);if(media==null)bind(g,true);else sink.status("eas_registration","失败："+e.getClass().getSimpleName());}}
 }
 private void writeMessage(Parcel p,boolean direct){
  p.writeInt(1);p.writeString("mediacenter");p.writeString("MediaCenterAPI");p.writeString("getMainBinder");
  p.writeByteArray((direct?"NoParam":"").getBytes(StandardCharsets.UTF_8));p.writeByteArray(new byte[0]);
 }
 private IBinder readSupport(Parcel p)throws RemoteException{return p.readInt()==0?null:readSupportFields(p);}
 private IBinder readSupportFields(Parcel p)throws RemoteException{
  int code=p.readInt();String message=p.readString();p.createByteArray();IBinder binder=p.readStrongBinder();
  log("Support response code="+code+" message="+safe(message)+" binder="+(binder!=null));
  return code==200?binder:null;
 }
 private void ready(int g,IBinder binder)throws Exception{
  if(!current(g))return;
  String descriptor=binder.getInterfaceDescriptor();
  if(!MEDIA.equals(descriptor))throw new RemoteException("主Binder descriptor不匹配: "+descriptor);
  media=binder;death=()->lost(g,"主Binder死亡");media.linkToDeath(death,0);
  sink.status("eas_connection","已连接："+descriptor);stage="音乐源注册";
  MusicCallback callback=new MusicCallback(g,sourceType);playbackInfo=new PlaybackInfo(sourceType);
  token=call(g,media,MEDIA,19,p->{p.writeString(context.getPackageName());p.writeStrongBinder(callback);},Parcel::readStrongBinder);
  if(!current(g))return;
  if(token==null){
   log("registerInMusic明确返回null；执行registerMusic兼容回退");
   token=call(g,media,MEDIA,1,p->p.writeStrongBinder(callback),Parcel::readStrongBinder);
  }
  if(token==null){sink.status("eas_registration","失败：注册token=null（可能授权/包配置限制）");return;}
  sink.registered();
  sink.status("eas_registration","已取得token；源类型="+sourceType);log("token descriptor="+token.getInterfaceDescriptor());
  try{
   call(g,media,MEDIA,7,p->{p.writeStrongBinder(token);p.writeIntArray(new int[]{sourceType});},p->null);
   call(g,media,MEDIA,8,p->{p.writeStrongBinder(token);p.writeInt(sourceType);},p->null);
   call(g,media,MEDIA,30,p->{p.writeStrongBinder(token);p.writeIntArray(new int[]{0,2,3});},p->null);
   Boolean updated=call(g,media,MEDIA,6,p->{p.writeStrongBinder(token);p.writeStrongBinder(playbackInfo);},p->p.readInt()!=0);
   log("source list/current/capabilities声明完成; neutral playback update="+updated);
  }catch(Exception e){error("注册后配置",e);}
  query(g);if(selectAfterRegister)request(g);
 }
 private void request(int g){
  if(!current(g)||!sink.shouldRequestFocus())return;
  if(media==null||token==null){sink.status("eas_request","阻断：尚未注册");return;}
  query(g);if(!current(g)||!sink.shouldRequestFocus())return;
  stage="requestPlay";
  try{
   Boolean accepted=call(g,media,MEDIA,5,p->p.writeStrongBinder(token),p->p.readInt()!=0);
   if(!current(g))return;
   sink.status("eas_request","requestPlay受理="+accepted+"；不代表焦点已取得");
   for(long delay:new long[]{0,500,1500,3000})worker.postDelayed(()->query(g),delay);
  }catch(Exception e){if(current(g))error("requestPlay",e);}
 }
 private void query(int g){
  if(!current(g))return;
  if(media==null||token==null){sink.status("eas_focus","未知：尚未注册");return;}
  stage="queryCurrentFocusClient";
  try{
   String pkg=call(g,media,MEDIA,42,p->p.writeStrongBinder(token),Parcel::readString);
   if(current(g)){
    boolean becameOurs=context.getPackageName().equals(pkg)&&!context.getPackageName().equals(lastFocusPackage);
    lastFocusPackage=pkg;
    sink.focus(pkg);sink.status("eas_focus",pkg==null||pkg.trim().isEmpty()?"未知：厂商返回空焦点":pkg);log("focus query package="+safe(pkg));
    if(becameOurs){
     publishPlayback(g,"focus-confirmed");
     // OEM requestPlay/metadata work can still be queued after the Binder reply.
     for(long delay:new long[]{500,1500})worker.postDelayed(()->{if(current(g)&&context.getPackageName().equals(lastFocusPackage))publishPlayback(g,"focus-settled");},delay);
    }
   }
  }catch(Exception e){if(current(g)){sink.status("eas_focus","未知：查询失败 "+e.getClass().getSimpleName());error("焦点查询",e);}}
 }
 private void lost(int g,String reason){
  if(!current(g))return;
  generation.compareAndSet(g,g+1);sink.disconnected();
  sink.status("eas_connection",reason+"；请手动重连");sink.status("eas_focus","未知：连接失效");log(reason);
  worker.post(this::release);
 }
 private interface Write{void write(Parcel p)throws Exception;}
 private interface Read<T>{T read(Parcel p)throws Exception;}
 private <T>T call(int g,IBinder binder,String descriptor,int code,Write writer,Read<T> reader)throws Exception{
  if(!current(g))throw new RemoteException("连接代际失效");
  Parcel data=Parcel.obtain(),reply=Parcel.obtain();long at=SystemClock.elapsedRealtime();
  final boolean[] finished={false};
  Runnable timeout=()->{synchronized(finished){if(finished[0])return;}
   if(current(g)&&generation.compareAndSet(g,g+1)){
    sink.disconnected();
    sink.status("eas_error","Binder事务超时4000ms："+descriptor+" #"+code+"；晚到结果失效，底层同步调用不能取消");
    sink.status("eas_connection","调用超时；连接失效");sink.status("eas_focus","未知：接口超时");
    unbindAll();
    worker.post(this::release);
   }
  };
  watchdog.postDelayed(timeout,4000);
  try{
   data.writeInterfaceToken(descriptor);writer.write(data);
   log("transact begin descriptor="+descriptor+" code="+code);
   if(!binder.transact(code,data,reply,0))throw new RemoteException("不支持事务 #"+code);
   reply.readException();T result=reader.read(reply);
   if(!current(g)){
    // Registration can complete after close/watchdog: release its exact late token.
    if(MEDIA.equals(descriptor)&&(code==19||code==1)&&result instanceof IBinder)unregister(binder,(IBinder)result);
    throw new RemoteException("迟到结果已忽略");
   }
   log("transact end code="+code+" ms="+(SystemClock.elapsedRealtime()-at));return result;
  }finally{
   synchronized(finished){finished[0]=true;}watchdog.removeCallbacks(timeout);data.recycle();reply.recycle();
  }
 }
 private void release(){
  IBinder old=media,oldToken=token;media=null;token=null;lastFocusPackage=null;playbackInfo=null;
  if(old!=null&&death!=null){try{old.unlinkToDeath(death,0);}catch(Exception ignored){}death=null;}
  // Release local bindings before a remote unregister which Android cannot cancel.
  unbindAll();
  if(old!=null&&oldToken!=null&&old.isBinderAlive())unregister(old,oldToken);
 }
 private void unregister(IBinder old,IBinder oldToken){
  Parcel d=Parcel.obtain(),r=Parcel.obtain();
  java.util.concurrent.atomic.AtomicBoolean done=new java.util.concurrent.atomic.AtomicBoolean();
  Runnable timeout=()->{if(!done.get())log("unregister timeout 4000ms; local callbacks invalidated; remote completion unknown");};
  watchdog.postDelayed(timeout,4000);log("unregister begin");
  try{d.writeInterfaceToken(MEDIA);d.writeStrongBinder(oldToken);
   if(old.transact(4,d,r,0)){r.readException();log("unregister returned="+(r.readInt()!=0));}
  }catch(Exception e){error("unregister",e);}finally{done.set(true);watchdog.removeCallbacks(timeout);d.recycle();r.recycle();}
 }

 private void unbindAll(){List<ServiceConnection> copy;synchronized(bound){copy=new ArrayList<>(bound);bound.clear();}for(ServiceConnection c:copy){try{context.unbindService(c);}catch(Exception e){log("unbind "+e.getClass().getSimpleName());}}}
 @Override public void close(){if(closed)return;closed=true;playback=PlaybackSnapshot.EMPTY;generation.incrementAndGet();unbindAll();worker.post(()->{release();thread.quitSafely();});}
 private static int actionFor(int raw){switch(raw){case 1:case 30:return 1;case 2:case 31:return 2;case 3:return 4;case 4:return 5;default:return -1;}}
 private static String safe(String s){return s==null?"<null>":s.length()>300?s.substring(0,300):s;}

 private final class MusicCallback extends Binder {
  final int g,type;
  MusicCallback(int g,int type){this.g=g;this.type=type;attachInterface(null,MUSIC);}
  @Override protected boolean onTransact(int code,Parcel data,Parcel reply,int flags)throws RemoteException{
   if(code==IBinder.INTERFACE_TRANSACTION){if(reply!=null)reply.writeString(MUSIC);return true;}
   if(code<1||code>36)return super.onTransact(code,data,reply,flags);
   data.enforceInterface(MUSIC);
   if(!current(g)){if(reply!=null){reply.writeNoException();writeNeutralCallback(code,reply);}return true;}
   log("raw IMusicClient callback code="+code+" bytes="+data.dataAvail());
   boolean ok=false;boolean start=true;
   if(code==22||code==23){start=data.readInt()!=0;log("media hold code="+code+" start="+start);}
   if(code<=6||code==22||code==23||code==26||code==30||code==31){
    if(code==30||code==31)log("media list type="+data.readInt());
    ok=EasProtocol.dispatch(code,start,current(g)&&sink.canDispatch(actionFor(code)),(action,raw)->{
     worker.post(()->{if(current(g)){sink.event(action,raw,"Flyme EAS");query(g);}});
    });
   }
   if(reply!=null)reply.writeNoException();
   switch(code){
    case 10: if(reply!=null)reply.writeStrongBinder(playbackInfo);break;
    case 11: if(reply!=null)reply.writeIntArray(new int[]{type});break;
    case 12: if(reply!=null)reply.writeInt(type);break;
    case 13: if(reply!=null)reply.writeLong(playback.position);break;
    case 14:case 28:if(reply!=null)reply.writeInt(-1);break;
    case 25:{String pkg=data.readString();log("focus change callback package="+safe(pkg));worker.post(()->{if(current(g))query(g);});break;}
    case 33:log("operationType="+data.readInt());break;
    case 34:case 35:log("不支持扩展回调 code="+code);break;
    case 36:
     // Target OTA: Bundle presence + Bundle payload; never MediaBridge's media-list layout.
     try{boolean present=data.readInt()!=0;log("onCustomAction(Bundle) present="+present+" payloadBytes="+data.dataAvail()+"（不反序列化未知Parcelable）");}
     catch(RuntimeException e){error("custom action结构",e);}break;
    case 8:{int selected=data.readInt();log("onSourceSelected="+selected);worker.post(()->query(g));if(reply!=null)reply.writeInt(selected==type?1:0);break;}
    case 17:{int selected=data.readInt();log("onSourceChanged type="+selected+" package="+safe(data.readString()));worker.post(()->query(g));if(reply!=null)reply.writeInt(selected==type?1:0);break;}
    case 29:if(reply!=null)reply.writeInt(0);break;
    default:if(reply!=null)reply.writeInt(ok?1:0);
   }
   return true;
  }
  private void writeNeutralCallback(int code,Parcel reply){
   switch(code){case 10:reply.writeStrongBinder(null);break;case 11:reply.writeIntArray(new int[0]);break;case 13:reply.writeLong(0);break;case 14:case 28:reply.writeInt(-1);break;case 25:case 33:case 34:case 35:case 36:break;default:reply.writeInt(0);}
  }
 }
 private final class PlaybackInfo extends Binder {
  final int type;
  final PlaybackSnapshot snapshot;
  final Uri artwork;
  final PendingIntent launch;
  PlaybackInfo(int type){
   this.type=type;snapshot=playback;
   Uri cover=null;
   try{cover=FlymeArtworkProvider.publish(context,snapshot.artwork);}catch(Exception e){error("artwork publish",e);}
   artwork=cover;
   PendingIntent intent=null;
   try{Intent open=context.getPackageManager().getLaunchIntentForPackage(context.getPackageName());
    if(open!=null)intent=PendingIntent.getActivity(context,0,open,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
   }catch(Exception e){error("media launch intent",e);}
   launch=intent;
   attachInterface(null,INFO);
  }
  private void parcelable(Parcel r,android.os.Parcelable value){r.writeInt(value==null?0:1);if(value!=null)value.writeToParcel(r,1);}
  @Override protected boolean onTransact(int code,Parcel d,Parcel r,int flags)throws RemoteException{
   if(code==IBinder.INTERFACE_TRANSACTION){if(r!=null)r.writeString(INFO);return true;}
   if(code<1||code>35)return super.onTransact(code,d,r,flags);
   d.enforceInterface(INFO);if(r==null)return true;r.writeNoException();
   switch(code){
    case 1:case 33:parcelable(r,closed?null:launch);break;
    case 10:case 13:case 15:case 17:parcelable(r,null);break;
    case 16:parcelable(r,closed?null:artwork);break;
    case 7:r.writeLong(closed?0:snapshot.duration);break;
    case 8:case 32:r.writeInt(-1);break;
    case 34:r.writeInt(0);break;
    case 11:r.writeInt(!closed&&snapshot.playing?1:0);break;
    case 2:r.writeString(closed?"":snapshot.title);break;
    case 25:r.writeString("Apple CarPlay");break;
    case 27:r.writeString(context.getPackageName());break;
    case 3:r.writeString(closed?"":snapshot.artist);break;
    case 4:r.writeString(closed?"":snapshot.album);break;
    case 24:r.writeString(closed?"":snapshot.trackId());break;
    case 26:{Uri icon=FlymeArtworkProvider.icon(context);r.writeString(icon==null?"":icon.toString());break;}
    case 5:case 6:case 12:case 14:case 30:case 35:r.writeString("");break;
    case 9:r.writeInt(type);break;
    default:r.writeInt(0); // All unimplemented optional capabilities remain false.
   }
   return true;
  }
 }
}
