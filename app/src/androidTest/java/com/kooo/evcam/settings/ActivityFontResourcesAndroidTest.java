package com.kooo.evcam.settings;

import android.content.Context;
import android.content.res.Resources;
import android.os.Build;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.os.LocaleListCompat;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.kooo.evcam.R;
import com.kooo.evcam.zeekr.AboutActivity;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

/** Main's existing enlargement must not mutate other screens or application resources. */
@RunWith(AndroidJUnit4.class)
public class ActivityFontResourcesAndroidTest {
    @Test public void localScaleSurvivesRecreationWithoutChangingOtherContextsOrLocales() {
        assumeTrue(Build.PRODUCT.contains("sdk") || Build.HARDWARE.contains("ranchu"));
        Context target = InstrumentationRegistry.getInstrumentation().getTargetContext();
        Resources app = target.getApplicationContext().getResources();
        float originalScale = app.getConfiguration().fontScale;
        float originalDensity = app.getDisplayMetrics().scaledDensity;
        LocaleListCompat originalLocales = AppCompatDelegate.getApplicationLocales();
        target.getSharedPreferences("app_config", Context.MODE_PRIVATE).edit().putBoolean("first_launch", false)
                .putBoolean("language_chosen", true).putBoolean("rail_side_chosen", true).commit();
        try {
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() ->
                    AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("en")));
            try (ActivityScenario<TypographyTestHostActivity> main = ActivityScenario.launch(TypographyTestHostActivity.class)) {
                for (int i = 0; i < 2; i++) {
                    main.onActivity(activity -> {
                        Resources local = activity.getResources();
                        assertEquals(1.2f, local.getConfiguration().fontScale, .001f);
                        assertEquals(18 * local.getDisplayMetrics().density * 1.2f,
                                local.getDimension(R.dimen.text_body), .1f);
                        assertEquals("en", local.getConfiguration().getLocales().get(0).getLanguage());
                        assertEquals(originalScale, app.getConfiguration().fontScale, .001f);
                        assertEquals(originalDensity, app.getDisplayMetrics().scaledDensity, .001f);
                    });
                    if (i == 0) {
                        main.onActivity(activity -> activity.startActivity(
                                new android.content.Intent(activity, AboutActivity.class)));
                        androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers
                                .withId(R.id.about_close)).perform(androidx.test.espresso.action.ViewActions.click());
                        main.recreate();
                    }
                }
            }
            try (ActivityScenario<AboutActivity> about = ActivityScenario.launch(AboutActivity.class)) {
                about.onActivity(activity -> assertEquals("Other screens retain their own scale",
                        originalScale, activity.getResources().getConfiguration().fontScale, .001f));
            }
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() ->
                    AppCompatDelegate.setApplicationLocales(originalLocales));
        }
    }
}
