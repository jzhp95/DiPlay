package com.shilapi.flymeeas;

/** Pure semantic layer for the target OTA IMusicClient callbacks. */
final class EasProtocol {
 interface CallbackSink { void event(int action,int raw); }
 static boolean dispatch(int raw,boolean start,boolean current,CallbackSink sink) {
  if(!current)return false;
  int action;
  switch(raw){
   case 1:action=1;break; case 2:action=2;break;
   case 3:action=4;break; case 4:action=5;break;
   case 5:action=9;break; case 6:action=10;break;
   case 22: if(!start)return true;action=9;break;
   case 23: if(!start)return true;action=10;break;
   case 26:action=11;break;case 30:action=1;break;case 31:action=2;break;
   default:return false;
  }
  sink.event(action,raw);return true;
 }
 private EasProtocol(){}
}
