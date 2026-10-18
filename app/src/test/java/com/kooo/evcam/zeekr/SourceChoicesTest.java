package com.kooo.evcam.zeekr;
import org.junit.Test; import static org.junit.Assert.*;
public class SourceChoicesTest {
 @Test public void manualAbsentUnassignedAndDuplicateChoicesNeverShiftOtherRoles() {
  ZeekrMultiPlan base=ZeekrMultiPlan.build(new String[]{"3","0","1"},"3",null,null,null);
  ZeekrMultiPlan p=base.withSourceChoices(new String[]{"3"},"3","9","","3");
  assertEquals("9",p.compositeId);assertNull(p.cabin1Id);assertEquals("3",p.cabin2Id);
  assertFalse(p.compositeIsReal);assertTrue(p.missing.contains("9"));
  p=base.withSourceChoices(new String[]{"3"},"3",null,"3","");
  assertEquals("3",p.compositeId);assertTrue(p.compositeIsReal);assertNull(p.cabin1Id);assertNull(p.cabin2Id);
 }
}
