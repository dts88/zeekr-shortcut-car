package com.kooo.evcam.settings;
import android.Manifest;
import android.content.*;
import android.os.*;
import android.view.View;
import android.widget.TextView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.kooo.evcam.*;
import com.kooo.evcam.playback.*;
import com.kooo.evcam.zeekr.*;
import org.junit.*;
import org.junit.runner.RunWith;
import java.io.*;
import java.util.*;
import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

/** Generated one-second video fixtures only; never opens or records a vehicle camera. */
@RunWith(AndroidJUnit4.class)
public class RecordingSourcePlaybackAndroidTest {
 @Test public void roadOnlyPlaybackUsesAnOrdinaryPictureAndCountsCompanionsOnce() throws Exception {
  assumeTrue(Build.PRODUCT.contains("sdk")||Build.HARDWARE.contains("ranchu"));
  Context target=InstrumentationRegistry.getInstrumentation().getTargetContext();
  for(String p:new String[]{Manifest.permission.READ_EXTERNAL_STORAGE,Manifest.permission.WRITE_EXTERNAL_STORAGE})
   InstrumentationRegistry.getInstrumentation().getUiAutomation().grantRuntimePermission(target.getPackageName(),p);
  boolean old=DeveloperMode.isUnlocked();DeveloperMode.unlock(target,"6651");
  AppConfig config=new AppConfig(target);String oldStorage=config.getStorageLocation();config.setStorageLocation("internal");
  File dir=StorageHelper.getVideoDir(target,false);assertNotNull(dir);
  List<File> made=new ArrayList<>();
  try {
   for(String name:new String[]{"20350101_120000_roadfront.mp4","20350101_120000_roadrear.mp4"}) {
    File file=new File(dir,name);assertFalse("Never overwrite existing files",file.exists());made.add(file);
    try(InputStream in=InstrumentationRegistry.getInstrumentation().getContext().getAssets().open("recording-source-fixture.mp4");OutputStream out=new FileOutputStream(file)) {
     byte[] bytes=new byte[8192];int n;while((n=in.read(bytes))!=-1)out.write(bytes,0,n);
    }
   }
   long start=RecordingTimeline.parseStartEpochMs("20350101_120000_roadfront.mp4");
   Intent intent=new Intent(target,TimelinePlayerActivity.class);
   try(ActivityScenario<TimelinePlayerActivity> scenario=ActivityScenario.launch(intent)) {
    long until=SystemClock.elapsedRealtime()+10000;boolean[] ready={false};
    do {scenario.onActivity(a->{TextView label=a.findViewById(R.id.label_surround);ready[0]=target.getString(R.string.slot_road_front).contentEquals(label.getText());});if(!ready[0])SystemClock.sleep(50);}while(!ready[0]&&SystemClock.elapsedRealtime()<until);
    assertTrue("Road-only timeline opens its front-road source",ready[0]);
    scenario.onActivity(a->{
     assertEquals(View.VISIBLE,a.findViewById(R.id.frame_surround).getVisibility());assertEquals(View.VISIBLE,a.findViewById(R.id.cabin_column).getVisibility());
     try {java.lang.reflect.Method method=TimelinePlayerActivity.class.getDeclaredMethod("filesOf",int.class);method.setAccessible(true);
      assertEquals("Primary and companion files counted only once",2,((java.util.List<?>)method.invoke(a,0)).size());
     } catch(ReflectiveOperationException e){throw new AssertionError(e);}
    });
   }
  } finally {for(File file:made)file.delete();config.setStorageLocation(oldStorage);restoreDeveloper(target,old);}
 }
 private void restoreDeveloper(Context c,boolean old){if(old)DeveloperMode.unlock(c,"6651");else DeveloperMode.lock(c);}
}
