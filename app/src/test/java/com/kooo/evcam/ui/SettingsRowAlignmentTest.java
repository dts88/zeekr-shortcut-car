package com.kooo.evcam.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 设置行的名字从同一条竖线开始（ui-spec §三「文字对齐」，项目所有者 2026-10-23）。
 *
 * <p>开关在行首的行：行内边距 + 开关 + 间距，名字在后面；没有开关的行在开关的位置留出同样宽的空位，
 * 名字从 {@code pref_text_start} 开始（分组小标题不跟着挪，贴着卡片边）。这里钉住三件事：</p>
 *
 * <ul>
 *   <li>{@code pref_text_start} 就是那三个数的和 —— 改了其中一个忘了改和，没开关的行就错开了；</li>
 *   <li>开关真的是 {@code pref_switch_width} 那么宽：SwitchCompat 量出来是两个滑块宽，最小宽也照它写；</li>
 *   <li>每一种设置行都照它排：新加一种行忘了留空位、或者有人把 20dp 写回去，这里就红。</li>
 * </ul>
 */
public class SettingsRowAlignmentTest {

    private static final String FRAME = "android:id=\"@android:id/widget_frame\"";
    private static final Pattern TAG = Pattern.compile("<(?![?!/])[^>]*>");

    @Test
    public void textStartIsPaddingPlusSwitchPlusGap() throws IOException {
        File res = CoreLayouts.resRoot();
        assumeTrue("定位不到资源目录，跳过", res != null);
        String dimens = CoreLayouts.read(new File(res, "values/dimens.xml").toPath());
        int padding = dp(dimens, "pref_row_padding_start");
        int width = dp(dimens, "pref_switch_width");
        int gap = dp(dimens, "pref_switch_gap");
        assertEquals("pref_text_start 应该是行内边距 + 开关 + 间距", padding + width + gap,
                dp(dimens, "pref_text_start"));
    }

    @Test
    public void switchIsAsWideAsItsSlot() throws IOException {
        File res = CoreLayouts.resRoot();
        assumeTrue("定位不到资源目录，跳过", res != null);
        String widget = CoreLayouts.read(new File(res, "layout/pref_widget_switch.xml").toPath());
        assertTrue("开关的最小宽要用 pref_switch_width",
                widget.contains("app:switchMinWidth=\"@dimen/pref_switch_width\""));

        // SwitchCompat 的宽 = max(最小宽, 两个滑块宽)：滑块宽了，开关就比那一格宽，名字又错开
        String thumb = CoreLayouts.read(new File(res, "drawable/pref_switch_thumb.xml").toPath());
        int thumbWidth = dpAttr(thumb, "android:left") + dpAttr(thumb, "android:right")
                + dpAttr(thumb, "android:width");
        String dimens = CoreLayouts.read(new File(res, "values/dimens.xml").toPath());
        assertEquals("两个滑块宽应该正好是 pref_switch_width", dp(dimens, "pref_switch_width"), 2 * thumbWidth);
    }

    @Test
    public void everySettingsRowStartsItsTextAtTheSameLine() throws IOException {
        File res = CoreLayouts.resRoot();
        assumeTrue("定位不到资源目录，跳过", res != null);
        List<String> offenders = new ArrayList<>();
        int checked = 0;
        for (Path path : CoreLayouts.all(res)) {
            String file = path.getFileName().toString();
            if (!file.startsWith("pref_row")) {
                continue;
            }
            checked++;
            String xml = CoreLayouts.read(path).replaceAll("(?s)<!--.*?-->", "");
            Matcher tags = TAG.matcher(xml);
            assertTrue(file, tags.find());
            String root = tags.group();
            String first = tags.find() ? tags.group() : "";
            String problem;
            if (first.contains(FRAME)) {
                problem = leadingSwitchProblem(root, first);
            } else {
                problem = "@dimen/pref_text_start".equals(attr(root, "android:paddingStart"))
                        ? null : "没有开关的行，根上的 paddingStart 要用 @dimen/pref_text_start";
            }
            if (problem != null) {
                offenders.add(CoreLayouts.name(res, path) + "：" + problem);
            }
        }
        assertFalse("一种设置行都没找到", checked == 0);
        assertTrue("这些设置行的名字和别的行没对齐（ui-spec §三「文字对齐」）: " + offenders, offenders.isEmpty());
    }

    /** 开关在行首的行：要么根上是行内边距、开关那一格是开关宽加间距，要么那一格自己带着边距和间距（开关加下一层）。 */
    private static String leadingSwitchProblem(String root, String frame) {
        String rootPadding = attr(root, "android:paddingStart");
        if (rootPadding != null) {
            if (!"@dimen/pref_row_padding_start".equals(rootPadding)) {
                return "根上的 paddingStart 要用 @dimen/pref_row_padding_start";
            }
            if (!"@dimen/pref_switch_width".equals(attr(frame, "android:layout_width"))
                    || !"@dimen/pref_switch_gap".equals(attr(frame, "android:layout_marginEnd"))) {
                return "开关那一格要是 @dimen/pref_switch_width 宽、后面隔 @dimen/pref_switch_gap";
            }
            return null;
        }
        if (!"@dimen/pref_row_padding_start".equals(attr(frame, "android:paddingStart"))
                || !"@dimen/pref_switch_gap".equals(attr(frame, "android:paddingEnd"))
                || !"wrap_content".equals(attr(frame, "android:layout_width"))) {
            return "开关那一格自己带边距时，要是 @dimen/pref_row_padding_start + 开关 + @dimen/pref_switch_gap";
        }
        return null;
    }

    private static String attr(String tag, String name) {
        Matcher m = Pattern.compile("(?:^|\\s)" + Pattern.quote(name) + "=\"([^\"]*)\"").matcher(tag);
        return m.find() ? m.group(1) : null;
    }

    private static int dp(String dimens, String name) {
        Matcher m = Pattern.compile("<dimen name=\"" + Pattern.quote(name) + "\">(\\d+)dp</dimen>").matcher(dimens);
        assertTrue("values/dimens.xml 里没有 " + name, m.find());
        return Integer.parseInt(m.group(1));
    }

    private static int dpAttr(String xml, String name) {
        Matcher m = Pattern.compile("(?:^|\\s)" + Pattern.quote(name) + "=\"(\\d+)dp\"").matcher(xml);
        assertTrue("滑块里没有 " + name, m.find());
        return Integer.parseInt(m.group(1));
    }
}
