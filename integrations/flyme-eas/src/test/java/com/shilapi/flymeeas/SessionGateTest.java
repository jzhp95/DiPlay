package com.shilapi.flymeeas;
import org.junit.Test;
import static org.junit.Assert.*;
public class SessionGateTest {
 @Test public void acquisitionIsBounded() {
  SessionGate g=new SessionGate();g.start();g.registered=true;
  for(int i=0;i<4;i++)assertTrue(g.request());
  assertFalse(g.request());
 }
 @Test public void stopRejectsLateFocusAndCommands() {
  SessionGate g=new SessionGate();g.start();g.registered=true;g.focus(true);assertTrue(g.owned);assertTrue(g.accepts(4,true));g.stop();g.focus(true);
  assertFalse(g.owned);assertFalse(g.mayExecuteRequest());assertFalse(g.request());assertFalse(g.accepts(4,true));
 }
 @Test public void userSelectingOtherSourceDoesNotTriggerReclaim() {
  SessionGate g=new SessionGate();g.start();g.registered=true;g.focus(true);assertTrue(g.owned);assertFalse(g.mayExecuteRequest());g.focus(false);
  assertFalse(g.request());assertFalse(g.owned);
 }
 @Test public void supportedCommandsRequireLivePhoneButNotFocusQuerySuccess() {
  SessionGate g=new SessionGate();g.start();g.registered=true;
  assertTrue(g.accepts(4,true));assertTrue(g.accepts(5,true));
  assertTrue(g.accepts(1,true));assertTrue(g.accepts(2,true));
  assertFalse(g.accepts(4,false));assertFalse(g.accepts(9,true));
 }
}
