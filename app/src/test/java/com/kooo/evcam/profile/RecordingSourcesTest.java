package com.kooo.evcam.profile;
import org.junit.Test;import java.util.*;import static org.junit.Assert.*;
public class RecordingSourcesTest {
 @Test public void everySelectionRoundTripsWithoutChangingStreamTuning() {
  for(int mask=0;mask<64;mask++) {
   Profile p=ProfileMigration.migrate(new ProfileMigration.Snapshot());RecordingSources.ensure(p);
   int i=0;Set<String> expected=new LinkedHashSet<>();
   for(String role:RecordingSources.ALL_ROLES){CameraProfile c=p.camera(role);c.enabled=(mask&(1<<i++))!=0;
    c.record.resolution="1920x1080";c.preview.resolution="640x480";c.photo.jpegQuality=87;
    if(c.enabled)expected.add(RecordingSources.key(role));}
   Profile copy=Profile.fromMap(p.toMap());RecordingSources.ensure(copy);
   assertEquals(expected,RecordSpecs.enabledCameraKeys(copy));assertEquals(p.toMap(),copy.toMap());
   for(String role:RecordingSources.EXTRA_ROLES)assertFalse(copy.camera(role).lanes.get(0).mirrored);
  }
 }
 @Test public void newSourcesRemainDisabledAndDoNotReplaceExistingChoices() {
  Profile p=ProfileMigration.migrate(new ProfileMigration.Snapshot());p.camera("cabin2").enabled=true;
  RecordingSources.ensure(p);RecordingSources.ensure(p);assertEquals(6,p.cameras.size());assertTrue(p.camera("cabin2").enabled);
  for(String role:RecordingSources.EXTRA_ROLES){assertFalse(p.camera(role).enabled);assertFalse(p.camera(role).record.grid);}
 }
}
