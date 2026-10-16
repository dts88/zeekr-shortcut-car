package com.kooo.evcam.recovery;
import org.junit.Test;
import static org.junit.Assert.*;
public class RecoveryUiPolicyTest {
 @Test public void reverseOrAnyFactoryCameraFlagBlocksAutomaticForeground(){
  assertTrue(RecoveryUiPolicy.factoryCameraActive("R",false,false,false));
  assertTrue(RecoveryUiPolicy.factoryCameraActive("P",true,false,false));
  assertTrue(RecoveryUiPolicy.factoryCameraActive("D",false,true,false));
  assertTrue(RecoveryUiPolicy.factoryCameraActive(null,false,false,true));
 }
 @Test public void inactiveAndUnknownSignalsKeepExistingRecoveryBehavior(){
  assertFalse(RecoveryUiPolicy.factoryCameraActive("P",false,false,false));
  assertFalse(RecoveryUiPolicy.factoryCameraActive("D",null,false,null));
  assertFalse(RecoveryUiPolicy.factoryCameraActive(null,null,null,null));
 }
}
