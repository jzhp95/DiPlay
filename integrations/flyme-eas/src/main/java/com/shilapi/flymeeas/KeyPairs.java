package com.shilapi.flymeeas;
import java.util.ArrayDeque;
import java.util.Iterator;
/** Pair a duplicate across routes, never debounce consecutive presses on the same route. */
final class KeyPairs {
 private static final class Press {
  final int action;final boolean eas;final long at;
  Press(int action,boolean eas,long at){this.action=action;this.eas=eas;this.at=at;}
 }
 private final ArrayDeque<Press> pending=new ArrayDeque<>();
 synchronized boolean allow(int action,boolean eas,long now){
  for(Iterator<Press> it=pending.iterator();it.hasNext();){
   Press p=it.next();
   if(now-p.at>150){it.remove();continue;}
   boolean same=p.action==action||(p.action==3&&(action==1||action==2))||
    (action==3&&(p.action==1||p.action==2));
   if(p.eas!=eas&&same){it.remove();return false;}
  }
  pending.addLast(new Press(action,eas,now));
  while(pending.size()>16)pending.removeFirst();
  return true;
 }
}
