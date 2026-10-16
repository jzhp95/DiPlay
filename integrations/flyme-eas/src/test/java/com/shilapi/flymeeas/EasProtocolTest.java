package com.shilapi.flymeeas;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;
/** Ported regression coverage for the proven demo's wire-to-semantic mapping. */
public class EasProtocolTest {
 @Test public void nextPreviousAndExplicitPlayPauseRemainDistinct(){
  List<Integer> actions=new ArrayList<>();
  for(int raw:new int[]{3,4,1,2,30,31})
   assertTrue(EasProtocol.dispatch(raw,true,true,(a,r)->actions.add(a)));
  assertEquals(Arrays.asList(4,5,1,2,1,2),actions);
 }
 @Test public void staleUnknownAndCustomBundleDoNotBecomeMediaCommands(){
  EasProtocol.CallbackSink unexpected=(a,r)->fail("Unexpected event "+a);
  assertFalse(EasProtocol.dispatch(3,true,false,unexpected));
  assertFalse(EasProtocol.dispatch(36,true,true,unexpected));
  assertFalse(EasProtocol.dispatch(47,true,true,unexpected));
  assertTrue(EasProtocol.dispatch(22,false,true,unexpected));
  assertTrue(EasProtocol.dispatch(23,false,true,unexpected));
 }
}
