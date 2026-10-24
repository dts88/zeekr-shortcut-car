package com.kooo.evcam.profile;
import org.junit.Test;import java.util.*;import static org.junit.Assert.*;
public class PreviewSelectionTest {
 @Test public void everyPreviewChoicePreservesEveryRecordingCombinationAndQuality() {
  for(int mask=0;mask<64;mask++)for(String mode:new String[]{"selected","surround","roads","roadfront","roadrear","cabin","surround_roads"}) {
   Profile p=ProfileMigration.migrate(new ProfileMigration.Snapshot());RecordingSources.ensure(p);int i=0;
   for(String role:RecordingSources.ALL_ROLES)p.camera(role).enabled=(mask&(1<<i++))!=0;
   p.camera("composite").preview.resolution="640x480";p.camera("composite").photo.jpegQuality=87;
   Map<String,String> before=p.toMap();Set<String> record=RecordSpecs.enabledCameraKeys(p);ProfileChannels c=ProfileChannels.of(p,mode);
   assertEquals(before,p.toMap());assertEquals(record,RecordSpecs.enabledCameraKeys(p));assertTrue(c.keys.containsAll(record));
   assertTrue(c.keys.containsAll(c.previewKeys));if("selected".equals(mode))assertEquals(record,c.previewKeys);
   if("roads".equals(mode))assertEquals(new LinkedHashSet<>(Arrays.asList("roadfront","roadrear")),c.previewKeys);
  }
 }
}
