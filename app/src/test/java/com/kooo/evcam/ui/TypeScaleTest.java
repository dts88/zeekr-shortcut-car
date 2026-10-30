package com.kooo.evcam.ui;

import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 字号只能从体系里取。
 *
 * <h3>为什么要钉</h3>
 *
 * <p>0.39.1 之前每个布局各写各的 sp，核心界面里同时存在八档，其中三对只差 1sp。
 * 没有人是故意的 —— 一个新界面照着旁边那个抄，抄到的是上一次随手写的数。
 * 收敛成六档之后，再放任 {@code android:textSize} 写回布局，几个版本就散回去了。</p>
 *
 * <h3>哪些不算</h3>
 *
 * <p>哪些布局算核心界面，见 {@link CoreLayouts} —— 排查用的那些不在此列。</p>
 */
public class TypeScaleTest {

    private static final Pattern APPEARANCE =
            Pattern.compile("android:textAppearance=\"@style/(TextAppearance\\.Cam[.\\w]*)\"");
    private static final Pattern DECLARED =
            Pattern.compile("<style name=\"(TextAppearance\\.Cam[.\\w]*)\"");

    @Test
    public void coreLayoutsTakeSizesFromTheScale() throws IOException {
        File res = CoreLayouts.resRoot();
        assumeTrue("定位不到资源目录，跳过", res != null);
        List<String> offenders = new ArrayList<>();
        for (Path path : CoreLayouts.all(res)) {
            if (!CoreLayouts.isCore(path)) {
                continue;
            }
            String source = CoreLayouts.read(path);
            if (source.contains("android:textSize=")) {
                offenders.add(CoreLayouts.name(res, path));
            }
        }
        assertTrue("这些布局写死了字号，请改用 android:textAppearance=\"@style/TextAppearance.Cam.*\""
                + "（体系见 values/type.xml）: " + offenders, offenders.isEmpty());
    }

    @Test
    public void everyAppearanceExists() throws IOException {
        File res = CoreLayouts.resRoot();
        assumeTrue("定位不到资源目录，跳过", res != null);
        Set<String> declared = new HashSet<>();
        Matcher styles = DECLARED.matcher(CoreLayouts.read(new File(res, "values/type.xml").toPath()));
        while (styles.find()) {
            declared.add(styles.group(1));
        }
        assertTrue("values/type.xml 里一个样式都没有？", declared.size() > 1);

        List<String> offenders = new ArrayList<>();
        for (Path path : CoreLayouts.all(res)) {
            Matcher used = APPEARANCE.matcher(CoreLayouts.read(path));
            while (used.find()) {
                if (!declared.contains(used.group(1))) {
                    offenders.add(CoreLayouts.name(res, path) + " -> " + used.group(1));
                }
            }
        }
        assertTrue("这些布局引用了不存在的字号样式: " + offenders, offenders.isEmpty());
    }

    /**
     * 每一档都要有自己的行高：不写就继承 Material 的 20sp，24sp 的字排在 20sp 的行里，多行的英文挤成一团
     * （「发现新版本」里的说明，2026-10-30）。对话框的标题和正文不走这几个样式，一起看。
     */
    @Test
    public void everySizeHasItsOwnLineHeight() throws IOException {
        File res = CoreLayouts.resRoot();
        assumeTrue("定位不到资源目录，跳过", res != null);
        List<String> offenders = new ArrayList<>();
        String type = CoreLayouts.read(new File(res, "values/type.xml").toPath());
        for (String style : new String[]{"TextAppearance.Cam.Micro", "TextAppearance.Cam.Caption",
                "TextAppearance.Cam.Body", "TextAppearance.Cam.Row", "TextAppearance.Cam.Title"}) {
            if (!hasLineHeight(type, style)) {
                offenders.add(style);
            }
        }
        String themes = CoreLayouts.read(new File(res, "values/themes.xml").toPath());
        for (String style : new String[]{"MaterialAlertDialog.Cam.Title", "MaterialAlertDialog.Cam.Body"}) {
            if (!hasLineHeight(themes, style)) {
                offenders.add(style);
            }
        }
        assertTrue("这些样式没写行高（android:lineHeight 和 lineHeight 都要）: " + offenders, offenders.isEmpty());
    }

    private static boolean hasLineHeight(String xml, String style) {
        int start = xml.indexOf("<style name=\"" + style + "\"");
        int end = start < 0 ? -1 : xml.indexOf("</style>", start);
        if (end < 0) {
            return false;
        }
        String body = xml.substring(start, end);
        return body.contains("name=\"android:lineHeight\"") && body.contains("name=\"lineHeight\"");
    }

    /** 布局里的行距没有用：MaterialTextView 用字号样式的行高盖掉它。写了只会让人以为生效了。 */
    @Test
    public void layoutsDoNotSetLineSpacing() throws IOException {
        File res = CoreLayouts.resRoot();
        assumeTrue("定位不到资源目录，跳过", res != null);
        List<String> offenders = new ArrayList<>();
        for (Path path : CoreLayouts.all(res)) {
            if (CoreLayouts.read(path).contains("android:lineSpacing")) {
                offenders.add(CoreLayouts.name(res, path));
            }
        }
        assertTrue("这些布局写了行距，它不生效，行高在 values/type.xml: " + offenders, offenders.isEmpty());
    }

}
