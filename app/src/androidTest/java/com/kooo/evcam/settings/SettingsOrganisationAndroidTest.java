package com.kooo.evcam.settings;

import android.content.Intent;
import android.content.Context;
import androidx.preference.SwitchPreferenceCompat;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.kooo.evcam.AppConfig;
import com.kooo.evcam.R;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class SettingsOrganisationAndroidTest {
    @Test public void nestedStampsKeepStoredValuesAndCategoryNavigationClearsHistory() throws Exception {
        Intent intent=new Intent(InstrumentationRegistry.getInstrumentation().getTargetContext(), SettingsTestHostActivity.class);
        try(ActivityScenario<SettingsTestHostActivity> scenario=ActivityScenario.launch(intent)) {
            scenario.onActivity(activity -> {
                SettingsShellFragment shell=(SettingsShellFragment)activity.getSupportFragmentManager().findFragmentById(R.id.fragment_container);
                shell.getChildFragmentManager().executePendingTransactions();
                shell.showSection("screen_recording");shell.getChildFragmentManager().executePendingTransactions();
                SettingsPreferenceFragment page=(SettingsPreferenceFragment)shell.getChildFragmentManager().findFragmentById(R.id.settings_detail);
                assertNotNull(page.findPreference("pref_screen_off_keep_recording"));
                page.findPreference("sub_recording_stamps").performClick();shell.getChildFragmentManager().executePendingTransactions();
                page=(SettingsPreferenceFragment)shell.getChildFragmentManager().findFragmentById(R.id.settings_detail);
                AppConfig config=new AppConfig(activity);
                boolean before=config.isLicensePlateEnabled();
                SwitchPreferenceCompat plate=page.findPreference("pref_license_plate_enabled");
                assertEquals(before,plate.isChecked());plate.performClick();
                assertEquals(!before,new AppConfig(activity).isLicensePlateEnabled());plate.performClick();
                assertEquals(before,new AppConfig(activity).isLicensePlateEnabled());
                assertEquals(1,shell.getChildFragmentManager().getBackStackEntryCount());
                shell.showSection("screen_storage");shell.getChildFragmentManager().executePendingTransactions();
                assertEquals(0,shell.getChildFragmentManager().getBackStackEntryCount());
                page=(SettingsPreferenceFragment)shell.getChildFragmentManager().findFragmentById(R.id.settings_detail);
                assertNotNull(page.findPreference("pref_storage_location"));
                shell.showSection("screen_system");shell.getChildFragmentManager().executePendingTransactions();
                page=(SettingsPreferenceFragment)shell.getChildFragmentManager().findFragmentById(R.id.settings_detail);
                assertNull(page.findPreference("pref_screen_off_keep_recording"));
            });
            for(String section:new String[]{"recording","storage","rearview","floating","interface","system"}) {
                scenario.onActivity(activity -> {
                    SettingsShellFragment shell=(SettingsShellFragment)activity.getSupportFragmentManager().findFragmentById(R.id.fragment_container);
                    shell.showSection("screen_"+section);shell.getChildFragmentManager().executePendingTransactions();
                });
                capture(section);
            }
        }
    }
    static void capture(String name) throws Exception {
        InstrumentationRegistry.getInstrumentation().getUiAutomation().waitForIdle(150,2000);
        android.os.SystemClock.sleep(150);
        android.graphics.Bitmap bitmap=InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();
        Context target=InstrumentationRegistry.getInstrumentation().getTargetContext();
        String prefix=InstrumentationRegistry.getArguments().getString("capturePrefix", "pr04-");
        try(java.io.FileOutputStream out=new java.io.FileOutputStream(new java.io.File(target.getExternalFilesDir(null),prefix+name+".png"))) {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out);
        }
        bitmap.recycle();
    }
}
