package com.kooo.evcam.settings;

import android.Manifest;
import android.content.*;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.view.*;
import android.widget.CompoundButton;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.kooo.evcam.*;
import com.kooo.evcam.camera.CameraManagerHolder;
import com.kooo.evcam.profile.*;
import org.junit.*;
import org.junit.runner.RunWith;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;
import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.action.ViewActions.scrollTo;
import static org.hamcrest.Matchers.startsWith;
import static androidx.test.espresso.matcher.ViewMatchers.withText;

@RunWith(AndroidJUnit4.class)
public class RecordingSourceGroupsAndroidTest {
 private Context target; private AppConfig config; private Map<String,?> oldConfig,oldProfiles;
 @Before public void setup() throws Exception {
  assumeTrue(Build.PRODUCT.contains("sdk") || Build.HARDWARE.contains("ranchu"));
  target=InstrumentationRegistry.getInstrumentation().getTargetContext();
  oldConfig=target.getSharedPreferences("app_config",0).getAll();oldProfiles=target.getSharedPreferences("profiles",0).getAll();
  for(String p:new String[]{Manifest.permission.CAMERA,Manifest.permission.READ_EXTERNAL_STORAGE,Manifest.permission.WRITE_EXTERNAL_STORAGE})
   InstrumentationRegistry.getInstrumentation().getUiAutomation().grantRuntimePermission(target.getPackageName(),p);
  try(ParcelFileDescriptor fd=InstrumentationRegistry.getInstrumentation().getUiAutomation().executeShellCommand("appops set "+target.getPackageName()+" SYSTEM_ALERT_WINDOW allow");java.io.InputStream in=new ParcelFileDescriptor.AutoCloseInputStream(fd)){while(in.read()!=-1){}}
  com.kooo.evcam.ui.MotionPolicy.setRecording(true);
  target.getSharedPreferences("app_config",0).edit().clear().putBoolean("first_launch",false).putBoolean("language_chosen",true).putBoolean("rail_side_chosen",true).commit();
  config=new AppConfig(target);config.setRearViewEnabled(false);
  config.setSourceCameraId("composite","3");config.setSourceCameraId("roadfront","0");config.setSourceCameraId("roadrear","1");
  config.setSourceCameraId("cabin1","2");config.setSourceCameraId("cabin2","4");
  ProfileStore store=new ProfileStore(target);Profile profile=ProfileMigration.migrate(new ProfileMigration.Snapshot());RecordingSources.ensure(profile);
  for(CameraProfile c:profile.cameras)c.enabled=CameraProfile.ROLE_COMPOSITE.equals(c.role);
  store.select(profile.id);store.save(profile);
 }
 @SuppressWarnings("unchecked") private void restore(String name,Map<String,?> before) {
  SharedPreferences.Editor e=target.getSharedPreferences(name,0).edit().clear();
  for(Map.Entry<String,?> x:before.entrySet()){Object v=x.getValue();String k=x.getKey();
   if(v instanceof Boolean)e.putBoolean(k,(Boolean)v);else if(v instanceof String)e.putString(k,(String)v);
   else if(v instanceof Integer)e.putInt(k,(Integer)v);else if(v instanceof Long)e.putLong(k,(Long)v);
   else if(v instanceof Float)e.putFloat(k,(Float)v);else if(v instanceof Set)e.putStringSet(k,new HashSet<>((Set<String>)v));}
  e.commit();
 }
 @After public void cleanup(){if(target==null)return;InstrumentationRegistry.getInstrumentation().runOnMainSync(()->CameraManagerHolder.getInstance().release());com.kooo.evcam.ui.MotionPolicy.setRecording(false);restore("profiles",oldProfiles);restore("app_config",oldConfig);}
 private CompoundButton toggle(View v,int label) {
  if(v instanceof CompoundButton && target.getString(label).contentEquals(v.getContentDescription()==null?"":v.getContentDescription()))return (CompoundButton)v;
  if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++){CompoundButton found=toggle(((ViewGroup)v).getChildAt(i),label);if(found!=null)return found;}
  return null;
 }
 private ProfileEditorFragment install(SettingsTestHostActivity activity) {
  activity.findViewById(R.id.fragment_container).setVisibility(View.VISIBLE);
  ProfileEditorFragment editor=new ProfileEditorFragment();activity.getSupportFragmentManager().beginTransaction().replace(R.id.fragment_container,editor).commitNow();return editor;
 }
 private void screenshot(String name) throws Exception {
  InstrumentationRegistry.getInstrumentation().waitForIdleSync(); android.os.SystemClock.sleep(250);
  android.graphics.Bitmap shot=InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();
  try(java.io.FileOutputStream out=new java.io.FileOutputStream(new java.io.File(target.getCacheDir(),name))){shot.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out);}finally{shot.recycle();}
 }
 @Test public void fourGroupsCanCombineAndManualMappingOpensItsPicker() throws Exception {
  try(ActivityScenario<SettingsTestHostActivity> scenario=ActivityScenario.launch(SettingsTestHostActivity.class)) {
   AtomicReference<ProfileEditorFragment> ref=new AtomicReference<>();
   scenario.onActivity(a->{ProfileEditorFragment e=install(a);ref.set(e);
    assertTrue(toggle(e.requireView(),R.string.record_group_surround).isChecked());
    toggle(e.requireView(),R.string.record_group_roads).performClick();
    Profile p=new ProfileStore(target).current();assertTrue(p.camera("composite").enabled);assertTrue(p.camera("roadfront").enabled);assertTrue(p.camera("roadrear").enabled);
    toggle(e.requireView(),R.string.record_group_cabin).performClick();p=new ProfileStore(target).current();assertTrue(p.camera("cabin1").enabled);assertTrue(p.camera("cabin2").enabled);
    toggle(e.requireView(),R.string.record_group_manual).performClick();assertFalse(new ProfileStore(target).current().camera("manual").enabled);
   });
   screenshot("recording-source-picker.png");
   onView(withText(R.string.action_cancel)).perform(click());
   scenario.onActivity(a->ref.get().selectCamera("roadfront"));
   screenshot("recording-source-groups.png");
   scenario.onActivity(a->{ProfileEditorFragment e=ref.get();toggle(e.requireView(),R.string.record_group_roads).performClick();
    Profile p=new ProfileStore(target).current();assertFalse(p.camera("roadfront").enabled);assertFalse(p.camera("roadrear").enabled);assertTrue(p.camera("composite").enabled);assertTrue(p.camera("cabin1").enabled);
   });

  }
 }
 @Test public void openingAndChangingGroupsPreservesSavedStreamTuning() {
  Profile p=new ProfileStore(target).current();CameraProfile surround=p.camera("composite");
  surround.photo.jpegQuality=87;surround.photo.resolution="1280x720";surround.preview.resolution="640x480";
  surround.record.resolution="1920x1080";new ProfileStore(target).save(p);
  Map<String,String> before=new ProfileStore(target).current().toMap();
  try(ActivityScenario<SettingsTestHostActivity> scenario=ActivityScenario.launch(SettingsTestHostActivity.class)) {
   scenario.onActivity(a->{ProfileEditorFragment e=install(a);assertEquals(before,new ProfileStore(target).current().toMap());
    toggle(e.requireView(),R.string.record_group_roads).performClick();
    CameraProfile after=new ProfileStore(target).current().camera("composite");
    assertEquals(87,after.photo.jpegQuality);assertEquals("1280x720",after.photo.resolution);
    assertEquals("640x480",after.preview.resolution);assertEquals("1920x1080",after.record.resolution);
   });
  }
 }
}
