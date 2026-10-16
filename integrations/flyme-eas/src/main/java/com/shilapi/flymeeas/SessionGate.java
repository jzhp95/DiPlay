package com.shilapi.flymeeas;
/** One CarPlay session: main-thread attempts, synchronized lifecycle/focus, volatile worker guards. */
final class SessionGate {
 volatile boolean active, registered, owned, acquired;
 private int attempts;
 synchronized void start() {active=true;registered=false;owned=false;acquired=false;attempts=0;}
 synchronized void stop() {active=false;registered=false;owned=false;}
 boolean request() {
  if(!active||!registered||acquired||attempts>=4)return false;
  attempts++;return true;
 }
 synchronized void focus(boolean ours) {if(active){owned=ours;if(ours)acquired=true;}}
 boolean mayExecuteRequest() {return active&&registered&&!acquired;}
 boolean accepts(int action, boolean ready) {
  return active&&registered&&ready&&(action==1||action==2||action==4||action==5);
 }
}
