package com.kooo.evcam.zeekr;
import org.junit.Test;
import static org.junit.Assert.*;
public class CameraFrameFreshnessTest {
 @Test public void needsProgressionAndExpiresExactlyAtLimit() {
  CameraFrameFreshness f=new CameraFrameFreshness(); Object source=new Object();
  assertFalse(f.fresh(0)); f.observe(source,100,0); assertFalse(f.fresh(0));
  f.observe(source,200,10); assertTrue(f.fresh(509)); assertFalse(f.fresh(510));
  f.observe(source,200,511); assertFalse(f.fresh(511));
  f.observe(source,300,512); assertTrue(f.fresh(512));
 }
 @Test public void sourceReplacementRegressionAndResetNeedNewProgression() {
  CameraFrameFreshness f=new CameraFrameFreshness(); Object a=new Object(),b=new Object();
  f.observe(a,10,0); f.observe(a,20,1); assertTrue(f.fresh(1));
  f.observe(b,30,2); assertFalse(f.fresh(2));
  f.observe(b,40,3); assertTrue(f.fresh(3));
  f.observe(b,10,4); assertFalse(f.fresh(4));
  f.observe(b,20,5); assertTrue(f.fresh(5));
  f.reset(); assertFalse(f.fresh(5));
 }
 @Test public void invalidValuesAndClockRollbackDoNotLookLive() {
  CameraFrameFreshness f=new CameraFrameFreshness();Object a=new Object();
  f.observe(a,1,10);f.observe(a,2,11);assertFalse(f.fresh(10));
  f.observe(a,0,12);assertFalse(f.fresh(12));
  f.observe(a,1,13);f.observe(a,2,14);f.observe(null,3,15);assertFalse(f.fresh(15));
 }
}
