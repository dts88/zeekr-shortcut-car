package com.kooo.evcam.zeekr;
import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;
public class RecordingCatalogTest {
 private RecordingTimeline.Source source(String folder,String slot,long start,long duration) {return new RecordingTimeline.Source(folder+"/20261018_000000_"+slot+".mp4",start,duration,100);}
 @Test public void roadOnlyRecordingsAreVisibleAndFrontAndRearAreNotConcatenated() {
  Map<String,List<RecordingTimeline.Source>> sources=new HashMap<>();
  sources.put("roadfront",Arrays.asList(source("/usb","roadfront",10000,60000)));
  sources.put("roadrear",Arrays.asList(source("/usb","roadrear",10000,60000)));
  List<RecordingTimeline.Session> sessions=RecordingCatalog.build(sources);assertEquals(1,sessions.size());
  assertEquals("roadfront",RecordingCatalog.primarySlot(sessions.get(0)));assertEquals(60000,sessions.get(0).totalDurationMs);
  assertEquals(1,RecordingCatalog.owned(sources.get("roadrear"),sessions,60000).get(0).size());
 }
 @Test public void surroundWinsWhenPresentButDoesNotHideOtherDrives() {
  Map<String,List<RecordingTimeline.Source>> sources=new HashMap<>();
  sources.put("surround",Arrays.asList(source("/usb","surround",10000,60000)));
  sources.put("roadfront",Arrays.asList(source("/usb","roadfront",10000,60000),source("/usb","roadfront",200000,60000)));
  List<RecordingTimeline.Session> sessions=RecordingCatalog.build(sources);assertEquals(2,sessions.size());
  assertEquals("surround",RecordingCatalog.primarySlot(sessions.get(0)));assertEquals("roadfront",RecordingCatalog.primarySlot(sessions.get(1)));
 }
 @Test public void identicalTimestampsOnDifferentDrivesStaySeparate() {
  Map<String,List<RecordingTimeline.Source>> sources=new HashMap<>();
  sources.put("roadfront",Arrays.asList(source("/usbA","roadfront",10000,60000),source("/usbB","roadfront",10000,60000)));
  sources.put("roadrear",Arrays.asList(source("/usbB","roadrear",10000,60000)));
  List<RecordingTimeline.Session> sessions=RecordingCatalog.build(sources);assertEquals(2,sessions.size());
  int owners=0;for(LaneTrack track:RecordingCatalog.owned(sources.get("roadrear"),sessions,60000))owners+=track.size();assertEquals(1,owners);
  for(RecordingTimeline.Session session:sessions) {
   int expected=session.segments.get(0).path.startsWith("/usbB/")?1:0;
   assertEquals(expected,RecordingCatalog.shown(sources.get("roadrear"),session).size());
  }
 }
}
