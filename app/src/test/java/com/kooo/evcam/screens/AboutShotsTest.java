/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.screens;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.view.View;
import android.widget.ScrollView;
import android.widget.TextView;

import com.kooo.evcam.R;
import com.kooo.evcam.zeekr.AboutActivity;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import java.io.File;

/**
 * 「关于与致谢」的「开源与来源」一段（项目所有者 2026-11-15）：贡献者里有 lelelolo，下面列着随应用分发的第三方开源组件和许可证。
 * 中英各一张（关于页只有这两种文字）。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = android.app.Application.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class AboutShotsTest {

    @Test
    public void creditsByDayInChinese() {
        shoot(Shots.DAY, "about-credits-zh", "lelelolo", "NanoHTTPD");
    }

    @Test
    public void creditsByDayInEnglish() {
        shoot(Shots.DAY.replace("zh-rCN-", "en-"), "about-credits-en", "lelelolo", "Media3 ExoPlayer");
    }

    private void shoot(String qualifiers, String name, String... mustContain) {
        RuntimeEnvironment.setQualifiers(qualifiers);
        AboutActivity activity = Robolectric.buildActivity(AboutActivity.class).setup().get();
        Shots.settle();
        TextView libraries = activity.findViewById(R.id.about_credits_libraries);
        assertNotNull(libraries);
        TextView contributors = null;
        View parent = (View) libraries.getParent();
        String all = "";
        if (parent instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) parent;
            for (int i = 0; i < group.getChildCount(); i++) {
                if (group.getChildAt(i) instanceof TextView) {
                    all += ((TextView) group.getChildAt(i)).getText() + "\n";
                }
            }
        }
        for (String text : mustContain) {
            assertTrue("少了 " + text, all.contains(text));
        }
        // 滚到「开源与来源」那一段
        View scroller = parent;
        while (scroller != null && !(scroller instanceof ScrollView)
                && !(scroller instanceof androidx.core.widget.NestedScrollView)) {
            scroller = scroller.getParent() instanceof View ? (View) scroller.getParent() : null;
        }
        File first = Shots.save(activity, name);
        if (scroller != null) {
            final View target = libraries;
            int y = target.getTop() - 600;
            scroller.scrollTo(0, Math.max(0, y));
        }
        File file = Shots.save(activity, name);
        assertTrue(file + " 是空的", file.length() > 10_000 && first.length() > 10_000);
    }
}
