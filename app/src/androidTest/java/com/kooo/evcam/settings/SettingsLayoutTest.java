package com.kooo.evcam.settings;

import android.content.Context;
import android.content.res.Configuration;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.kooo.evcam.R;
import com.kooo.evcam.ui.ResponsiveCardRow;
import com.kooo.evcam.ui.SegmentedBar;

import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Real Android measurement: long/localized labels must never push controls offscreen. */
@RunWith(AndroidJUnit4.class)
public class SettingsLayoutTest {
    private Context context(float fontScale) {
        Context target = InstrumentationRegistry.getInstrumentation().getTargetContext();
        Configuration config = new Configuration(target.getResources().getConfiguration());
        config.fontScale = fontScale;
        return new ContextThemeWrapper(target.createConfigurationContext(config), R.style.Theme_Cam);
    }
    private int px(Context context, int dp) {
        return Math.round(dp * context.getResources().getDisplayMetrics().density);
    }
    private void measure(View view, int width) {
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        view.layout(0, 0, view.getMeasuredWidth(), view.getMeasuredHeight());
    }
    private View camera(Context context, String name) {
        View card = LayoutInflater.from(context).inflate(R.layout.item_camera_card, null);
        ((TextView) card.findViewById(R.id.camera_name)).setText(name);
        ((TextView) card.findViewById(R.id.camera_summary)).setText("2560×2560 · 20 fps (cap) · 8.0 Mbps");
        return card;
    }
    @Test public void longCameraNameAndCustomBadgeLeaveWholeSwitchTouchable() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Context context = context(1.3f);
            View card = camera(context, "Surround (composite)");
            card.findViewById(R.id.camera_tuned).setVisibility(View.VISIBLE);
            measure(card, px(context, 240));
            View toggle = card.findViewById(R.id.camera_enabled);
            View nameColumn = (View) card.findViewById(R.id.camera_name).getParent();
            View header = (View) toggle.getParent();
            assertTrue(toggle.getWidth() >= px(context, 48));
            assertTrue(toggle.getHeight() >= px(context, 48));
            assertTrue(nameColumn.getRight() <= toggle.getLeft());
            assertTrue(toggle.getRight() <= header.getWidth());
            assertTrue(toggle.getBottom() <= header.getHeight());
            TextView name = card.findViewById(R.id.camera_name);
            assertEquals(name.length(), name.getLayout().getLineEnd(name.getLineCount() - 1));
        });
    }
    @Test public void cardRowsReflowBothWaysWithoutRetainingHorizontalMargins() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Context context = context(1.3f);
            ResponsiveCardRow row = new ResponsiveCardRow(context, null);
            for (String name : new String[]{"Surround (composite)", "Front cabin", "Rear cabin"})
                row.addView(camera(context, name), new LinearLayout.LayoutParams(0, -2, 1));
            measure(row, px(context, 1100));
            assertEquals(LinearLayout.HORIZONTAL, row.getOrientation());
            assertEquals(row.getChildAt(0).getHeight(), row.getChildAt(2).getHeight());
            measure(row, px(context, 620));
            assertEquals(LinearLayout.VERTICAL, row.getOrientation());
            for (int i = 1; i < 3; i++) {
                assertEquals(row.getChildAt(0).getLeft(), row.getChildAt(i).getLeft());
                assertEquals(row.getChildAt(0).getRight(), row.getChildAt(i).getRight());
                assertTrue(row.getChildAt(i).getTop() > row.getChildAt(i - 1).getBottom());
            }
            measure(row, px(context, 1100));
            assertEquals(LinearLayout.HORIZONTAL, row.getOrientation());
            assertTrue(row.getChildAt(1).getLeft() > row.getChildAt(0).getRight());
            assertEquals(row.getChildAt(0).getTop(), row.getChildAt(2).getTop());
        });
    }
    @Test public void longSegmentLabelsWrapWithEqualHeightAndStillSelect() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Context context = context(1.3f);
            SegmentedBar bar = new SegmentedBar(context);
            String[] picked = {null};
            String[] labels = {"Front passenger side", "Follow the system", "Driver side"};
            bar.bind(labels, new String[]{"left", "auto", "right"}, "auto", value -> picked[0] = value);
            measure(bar, px(context, 360));
            for (int i = 0; i < 3; i++) {
                TextView item = (TextView) bar.getChildAt(i);
                assertEquals(bar.getChildAt(0).getHeight(), item.getHeight());
                assertTrue(item.getHeight() >= px(context, 48));
                assertTrue(item.getLayout().getHeight() + item.getPaddingTop() + item.getPaddingBottom() <= item.getHeight());
                assertEquals(item.length(), item.getLayout().getLineEnd(item.getLineCount() - 1));
            }
            assertTrue(bar.getChildAt(2).performClick());
            assertEquals("right", picked[0]);
            bar.setEnabled(false);
            for (int i = 0; i < 3; i++) assertFalse(bar.getChildAt(i).isEnabled());
        });
    }
}
