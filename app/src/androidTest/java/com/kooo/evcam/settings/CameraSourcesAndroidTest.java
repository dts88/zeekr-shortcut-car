package com.kooo.evcam.settings;

import android.Manifest;
import android.content.*;
import android.os.Build;
import android.view.View;
import androidx.preference.Preference;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.kooo.evcam.*;
import com.kooo.evcam.camera.CameraManagerHolder;
import com.kooo.evcam.profile.*;
import com.kooo.evcam.zeekr.StreamLayoutTable;
import org.junit.*;
import org.junit.runner.RunWith;
import java.util.*;
import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;
import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.matcher.ViewMatchers.withText;

@RunWith(AndroidJUnit4.class)
public class CameraSourcesAndroidTest {
 private Context target;private AppConfig config;private SharedPreferences prefs;private boolean oldDeveloper;
 private Map<String,?> oldConfig,oldProfiles;
 @Before public void setup() {
  assumeTrue(Build.PRODUCT.contains("sdk")||Build.HARDWARE.contains("ranchu"));
  target=InstrumentationRegistry.getInstrumentation().getTargetContext();
  prefs=target.getSharedPreferences("app_config",0);oldConfig=prefs.getAll();oldProfiles=target.getSharedPreferences("profiles",0).getAll();
  com.kooo.evcam.ui.MotionPolicy.setRecording(true);
  oldDeveloper=DeveloperMode.isUnlocked();DeveloperMode.lock(target);
  prefs.edit().clear().putBoolean("first_launch",false).putBoolean("language_chosen",true).putBoolean("rail_side_chosen",true).commit();
  StreamLayoutTable.reset();config=new AppConfig(target);
  config.setAutoStartRecording(false);config.setRearViewEnabled(false);
  ProfileStore store=new ProfileStore(target);Profile p=ProfileMigration.migrate(new ProfileMigration.Snapshot());RecordingSources.ensure(p);
  for(CameraProfile c:p.cameras)c.enabled=CameraProfile.ROLE_COMPOSITE.equals(c.role);store.select(p.id);store.save(p);
  for(String permission:new String[]{Manifest.permission.CAMERA,Manifest.permission.READ_EXTERNAL_STORAGE,Manifest.permission.WRITE_EXTERNAL_STORAGE})
   InstrumentationRegistry.getInstrumentation().getUiAutomation().grantRuntimePermission(target.getPackageName(),permission);
 }
 @SuppressWarnings("unchecked") private void restore(String name,Map<String,?> data) {
  SharedPreferences.Editor e=target.getSharedPreferences(name,0).edit().clear();
  for(Map.Entry<String,?> item:data.entrySet()){Object v=item.getValue();String k=item.getKey();
   if(v instanceof String)e.putString(k,(String)v);else if(v instanceof Boolean)e.putBoolean(k,(Boolean)v);
   else if(v instanceof Integer)e.putInt(k,(Integer)v);else if(v instanceof Long)e.putLong(k,(Long)v);
   else if(v instanceof Float)e.putFloat(k,(Float)v);else if(v instanceof Set)e.putStringSet(k,new HashSet<>((Set<String>)v));}e.commit();
 }
 @After public void cleanup(){if(target==null)return;InstrumentationRegistry.getInstrumentation().runOnMainSync(()->CameraManagerHolder.getInstance().release());
  com.kooo.evcam.ui.MotionPolicy.setRecording(false);restore("profiles",oldProfiles);restore("app_config",oldConfig);StreamLayoutTable.reset();restoreDeveloper();}
 @Test public void activeLegacyChoicesMigrateOnceAndStayActiveWithDeveloperOff() {
  DeveloperMode.unlock(target,"6651");
  prefs.edit().remove(SourceMappings.MIGRATED).putString("zeekr_camera_override_front","4").putString("source_id_composite","8")
   .putString("zeekr_camera_override_back","6").putString("zeekr_camera_override_left","7").putString("source_id_cabin2","").commit();
  config=new AppConfig(target);assertEquals("8",config.getSourceCameraId("composite"));assertEquals("6",config.getSourceCameraId("cabin1"));assertEquals("",config.getSourceCameraId("cabin2"));
  assertEquals("8",config.getCameraOverride("front"));assertEquals("6",config.getCameraOverride("back"));
  DeveloperMode.lock(target);assertEquals("6",new AppConfig(target).getSourceCameraId("cabin1"));
  config.setCameraOverride("front","9");assertEquals("9",config.getSourceCameraId("composite"));
  config.setSourceCameraId("composite",null);assertNull(new AppConfig(target).getCameraOverride("front"));assertFalse(prefs.contains("zeekr_camera_override_front"));
 }
 @Test public void inactiveLegacyChoicesNeverActivateLaterAndResetDoesNotResurrectThem() {
  prefs.edit().remove(SourceMappings.MIGRATED).putString("zeekr_camera_override_front","4").commit();
  config=new AppConfig(target);assertNull(config.getSourceCameraId("composite"));
  DeveloperMode.unlock(target,"6651");assertNull(new AppConfig(target).getCameraOverride("front"));
  config.resetSourceCameraIds();assertFalse(prefs.contains("zeekr_camera_override_front"));assertNull(new AppConfig(target).getSourceCameraId("composite"));
 }
 @Test public void perSourceAndWholeResetUseDetectionAndPreserveOtherChoices() {
  config.setAutomaticSourceId("composite","5");StreamLayoutTable.setCompositeCameraId("8");
  config.setSourceCameraId("composite","8");assertEquals("8",ProfileSizes.cameraIdFor(target,"composite"));
  assertEquals("5",ProfileSizes.automaticCameraIdFor(target,"composite"));
  config.setSourceCameraId("composite",null);assertEquals("5",ProfileSizes.cameraIdFor(target,"composite"));
  DeveloperMode.unlock(target,"6651");DeveloperMode.unlock(target,"6651");config.setSourceCameraId("manual","9");config.setSideViewFov(105);
  String profile=new ProfileStore(target).current().toMap().toString();
  config.setStoredSlotMap(config.getCarModel(),"old mapping");config.resetSourceCameraIds();
  assertEquals("5",ProfileSizes.cameraIdFor(target,"composite"));assertNull(config.getSourceCameraId("manual"));
  assertEquals(105,config.getSideViewFov());
  assertEquals(profile,new ProfileStore(target).current().toMap().toString());assertEquals("old mapping",config.getStoredSlotMap(config.getCarModel()));
  assertFalse(config.isAutoStartRecording());
 }
 private void restoreDeveloper() { if(oldDeveloper)DeveloperMode.unlock(target,"6651");else DeveloperMode.lock(target); }
 private CameraSourcesFragment open(SettingsShellFragment shell,boolean developer) {
  shell.showSection(developer?"screen_developer":"screen_recording");shell.getChildFragmentManager().executePendingTransactions();
  SettingsPreferenceFragment page=(SettingsPreferenceFragment)shell.getChildFragmentManager().findFragmentById(R.id.settings_detail);
  Preference link=page.findPreference(developer?"pref_camera_mapping":"pref_camera_sources");assertNotNull(link);
  link.getOnPreferenceClickListener().onPreferenceClick(link);shell.getChildFragmentManager().executePendingTransactions();
  return (CameraSourcesFragment)shell.getChildFragmentManager().findFragmentById(R.id.settings_detail);
 }
 @Test public void ordinaryAndDeveloperEntriesSharePickerAndResetWithCancel() {
  DeveloperMode.unlock(target,"6651");config.setAutomaticSourceId("composite","5");config.setSourceCameraId("composite","8");
  final SettingsShellFragment[] host={null};final CameraSourcesFragment[] sources={null};
  try(ActivityScenario<SettingsTestHostActivity> scenario=ActivityScenario.launch(SettingsTestHostActivity.class)) {
   scenario.onActivity(activity->{SettingsShellFragment shell=new SettingsShellFragment();host[0]=shell;
    activity.findViewById(R.id.fragment_container).setVisibility(View.VISIBLE);
    activity.getSupportFragmentManager().beginTransaction().replace(R.id.fragment_container,shell).commitNow();shell.getChildFragmentManager().executePendingTransactions();
    sources[0]=open(shell,false);assertNotNull(sources[0].findPreference("cabin2"));Preference surround=sources[0].findPreference("composite");assertTrue(surround.getSummary().toString().contains("8"));
    surround.getOnPreferenceClickListener().onPreferenceClick(surround);
   });
   onView(withText(R.string.opt_camera_auto)).perform(click());onView(withText(R.string.action_cancel)).perform(click());assertEquals("8",config.getSourceCameraId("composite"));
   scenario.onActivity(a->{Preference row=sources[0].findPreference("composite");row.getOnPreferenceClickListener().onPreferenceClick(row);});
   onView(withText(R.string.opt_camera_auto)).perform(click());onView(withText(R.string.action_ok)).perform(click());assertNull(config.getSourceCameraId("composite"));
   scenario.onActivity(a->{sources[0]=open(host[0],true);assertTrue(sources[0].findPreference("composite").getSummary().toString().contains("5"));
    config.setSourceCameraId("manual","9");Preference reset=sources[0].findPreference("reset_sources");reset.getOnPreferenceClickListener().onPreferenceClick(reset);});
   onView(withText(R.string.action_cancel)).perform(click());assertEquals("9",config.getSourceCameraId("manual"));
   scenario.recreate();
   scenario.onActivity(a->{
    host[0]=(SettingsShellFragment)a.getSupportFragmentManager().findFragmentById(R.id.fragment_container);
    host[0].getChildFragmentManager().executePendingTransactions();
    sources[0]=(CameraSourcesFragment)host[0].getChildFragmentManager().findFragmentById(R.id.settings_detail);
    assertTrue(sources[0].isResumed());
    Preference reset=sources[0].findPreference("reset_sources");reset.getOnPreferenceClickListener().onPreferenceClick(reset);
   });
   onView(withText(R.string.action_ok)).perform(click());assertNull(config.getSourceCameraId("manual"));assertEquals("5",ProfileSizes.cameraIdFor(target,"composite"));
  }
 }
}
