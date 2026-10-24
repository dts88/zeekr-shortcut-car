package com.kooo.evcam.profile;
import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;
public class SourceMappingsTest {
 @Test public void newChoicesIncludingUnassignedWinWhileActiveLegacyChoicesFillOnlyMissingRoles() {
  Map<String,Object> saved=new HashMap<>();saved.put("source_id_composite","8");saved.put("source_id_cabin2","");
  saved.put("zeekr_camera_override_front","4");saved.put("zeekr_camera_override_back","6");saved.put("zeekr_camera_override_left","7");
  assertEquals(Collections.singletonMap("source_id_cabin1","6"),SourceMappings.migrate(saved,true));
  assertEquals("8",saved.get("source_id_composite"));assertEquals("",saved.get("source_id_cabin2"));
 }
 @Test public void disabledLegacyAndMalformedValuesNeverBecomeActive() {
  Map<String,Object> saved=new HashMap<>();saved.put("zeekr_camera_override_front","4");
  assertTrue(SourceMappings.migrate(saved,false).isEmpty());
  saved.put("zeekr_camera_override_front",17);saved.put("zeekr_camera_override_back"," ");
  assertTrue(SourceMappings.migrate(saved,true).isEmpty());
 }
}
