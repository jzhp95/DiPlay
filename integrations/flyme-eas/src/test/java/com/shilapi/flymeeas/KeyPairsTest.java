package com.shilapi.flymeeas;
import org.junit.Test;
import static org.junit.Assert.*;
public class KeyPairsTest {
 @Test public void unknownFocusStillPairsDuplicateHardwareAndEasInEitherOrder(){
  KeyPairs p=new KeyPairs();
  assertTrue(p.allow(4,false,100));assertFalse(p.allow(4,true,130));
  assertTrue(p.allow(5,true,200));assertFalse(p.allow(5,false,250));
 }
 @Test public void repeatedPhysicalPressesArePreservedAndPairedOneForOne(){
  KeyPairs p=new KeyPairs();
  assertTrue(p.allow(4,false,100));assertTrue(p.allow(4,false,140));
  assertFalse(p.allow(4,true,150));assertFalse(p.allow(4,true,160));
  assertTrue(p.allow(4,true,170));
 }
 @Test public void distinctCommandsAndLaterPressesAreNotPaired(){
  KeyPairs p=new KeyPairs();
  assertTrue(p.allow(4,false,100));assertTrue(p.allow(5,true,110));
  assertTrue(p.allow(4,true,251));
 }
 @Test public void hardwareToggleCanPairWithExplicitEasPlayOrPause(){
  KeyPairs p=new KeyPairs();
  assertTrue(p.allow(3,false,100));assertFalse(p.allow(2,true,120));
 }
}
