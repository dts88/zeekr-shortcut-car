package com.kooo.evcam.settings;

import android.content.Context;
import android.content.res.Configuration;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.TextView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.kooo.evcam.R;
import com.kooo.evcam.ui.WrappingActionRow;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.util.Locale;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class PlaybackActionLayoutAndroidTest {
    @Test public void allPlaybackActionsRemainAlignedReadableAndReachableAcrossSizesAndLanguages() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Context target = InstrumentationRegistry.getInstrumentation().getTargetContext();
            for (String language : new String[]{"en", "zh", "ms"})
                for (float scale : new float[]{1f, 1.3f})
                    for (int mode : new int[]{Configuration.UI_MODE_NIGHT_NO, Configuration.UI_MODE_NIGHT_YES}) {
                        Configuration config = new Configuration(target.getResources().getConfiguration());
                        config.setLocale(Locale.forLanguageTag(language)); config.fontScale = scale;
                        config.uiMode = (config.uiMode & ~Configuration.UI_MODE_NIGHT_MASK) | mode;
                        Context context = new ContextThemeWrapper(target.createConfigurationContext(config), R.style.Theme_Cam);
                        WrappingActionRow row = (WrappingActionRow) LayoutInflater.from(context).inflate(R.layout.layout_timeline_controls, null);
                        row.findViewById(R.id.timeline_lock).setVisibility(View.VISIBLE);
                        for (int widthDp : new int[]{360, 600, 1000, 1800, 600}) {
                            int width = Math.round(widthDp*context.getResources().getDisplayMetrics().density);
                            row.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
                            row.layout(0, 0, width, row.getMeasuredHeight());
                            int previousTop = -1, previousBottom = -1, previousRight = -1;
                            for (int i = 0; i < row.getChildCount(); i++) {
                                TextView button = (TextView) row.getChildAt(i);
                                if (button.getVisibility() == View.GONE) continue;
                                assertTrue(button.getLeft() >= 0 && button.getRight() <= width);
                                assertTrue(button.getBottom() <= row.getHeight());
                                assertTrue(button.getHeight() >= 48*context.getResources().getDisplayMetrics().density);
                                assertTrue("Text height: "+language+" "+widthDp+" "+button.getText(),
                                        button.getLayout().getHeight()+button.getCompoundPaddingTop()+button.getCompoundPaddingBottom() <= button.getHeight());
                                assertEquals(button.length(), button.getLayout().getLineEnd(button.getLineCount()-1));
                                if (button.getTop() == previousTop) {
                                    assertEquals(previousBottom, button.getBottom());
                                    assertTrue(previousRight < button.getLeft());
                                } else if (previousTop >= 0) assertTrue(button.getTop() > previousBottom);
                                previousTop = button.getTop(); previousBottom = button.getBottom(); previousRight = button.getRight();
                            }
                        }
                    }
        });
    }
    @Test public void photoActionsAlsoWrapAndRtlActionsStayInsideTheirRow() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Context target = InstrumentationRegistry.getInstrumentation().getTargetContext();
            Context context = new ContextThemeWrapper(target, R.style.Theme_Cam);
            View photo = LayoutInflater.from(context).inflate(R.layout.activity_photo_playback, null);
            WrappingActionRow row = photo.findViewById(R.id.controls_layout);
            row.setVisibility(View.VISIBLE);
            row.findViewById(R.id.btn_lock).setVisibility(View.VISIBLE);
            row.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
            int width = Math.round(300*context.getResources().getDisplayMetrics().density);
            row.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            row.layout(0, 0, width, row.getMeasuredHeight());
            int previousTop = -1, previousLeft = width, previousBottom = -1;
            for (int i = 0; i < row.getChildCount(); i++) {
                TextView button = (TextView) row.getChildAt(i);
                assertTrue(button.getLeft() >= 0 && button.getRight() <= width);
                if (button.getTop() == previousTop) {
                    assertTrue(button.getRight() < previousLeft);
                    assertEquals(previousBottom, button.getBottom());
                }
                assertEquals(button.length(), button.getLayout().getLineEnd(button.getLineCount()-1));
                previousTop = button.getTop(); previousLeft = button.getLeft(); previousBottom = button.getBottom();
            }
        });
    }

}
