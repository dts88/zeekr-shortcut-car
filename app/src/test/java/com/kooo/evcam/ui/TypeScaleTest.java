package com.kooo.evcam.ui;

import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

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
 * <p>开发者工具、自定义车型、副屏的布局不在此列：那些界面是排查用的，
 * 本来就不参与设计体系，也不该为了体系去动它们。</p>
 */
public class TypeScaleTest {

    /** 排查用的界面，不参与字号体系。 */
    private static final Set<String> SKIP = new HashSet<>(Arrays.asList(
            "activity_logcat_viewer.xml", "activity_main_custom.xml",
            "dialog_blind_spot_disclaimer.xml", "dialog_fullscreen_preview.xml",
            "dialog_wheel_settings.xml", "fragment_blind_spot_correction.xml",
            "fragment_blind_spot_lab.xml", "fragment_custom_camera_config.xml",
            "fragment_permission_settings.xml", "fragment_secondary_blind_spot_adjust.xml",
            "fragment_secondary_display_settings.xml", "layout_custom_buttons_multi.xml",
            "layout_custom_buttons_multi_vertical.xml", "layout_custom_buttons_standard.xml",
            "layout_custom_buttons_standard_vertical.xml", "presentation_secondary_display.xml",
            "presentation_secondary_display_multiview.xml", "view_blind_spot_floating.xml",
            "view_blind_spot_floating_multiview.xml", "view_fisheye_correction_floating.xml",
            "view_mock_turn_signal_floating.xml", "view_preview_correction_floating.xml"));

    private static final Pattern APPEARANCE =
            Pattern.compile("android:textAppearance=\"@style/(TextAppearance\\.Cam[.\\w]*)\"");
    private static final Pattern DECLARED =
            Pattern.compile("<style name=\"(TextAppearance\\.Cam[.\\w]*)\"");

    @Test
    public void coreLayoutsTakeSizesFromTheScale() throws IOException {
        File res = resRoot();
        assumeTrue("定位不到资源目录，跳过", res != null);
        List<String> offenders = new ArrayList<>();
        for (Path path : layouts(res)) {
            if (SKIP.contains(path.getFileName().toString())) {
                continue;
            }
            String source = read(path);
            if (source.contains("android:textSize=")) {
                offenders.add(name(res, path));
            }
        }
        assertTrue("这些布局写死了字号，请改用 android:textAppearance=\"@style/TextAppearance.Cam.*\""
                + "（体系见 values/type.xml）: " + offenders, offenders.isEmpty());
    }

    @Test
    public void everyAppearanceExists() throws IOException {
        File res = resRoot();
        assumeTrue("定位不到资源目录，跳过", res != null);
        Set<String> declared = new HashSet<>();
        Matcher styles = DECLARED.matcher(read(new File(res, "values/type.xml").toPath()));
        while (styles.find()) {
            declared.add(styles.group(1));
        }
        assertTrue("values/type.xml 里一个样式都没有？", declared.size() > 1);

        List<String> offenders = new ArrayList<>();
        for (Path path : layouts(res)) {
            Matcher used = APPEARANCE.matcher(read(path));
            while (used.find()) {
                if (!declared.contains(used.group(1))) {
                    offenders.add(name(res, path) + " -> " + used.group(1));
                }
            }
        }
        assertTrue("这些布局引用了不存在的字号样式: " + offenders, offenders.isEmpty());
    }

    // ------------------------------------------------------------------ 工具

    private static File resRoot() {
        for (String candidate : new String[]{".", "app"}) {
            File res = new File(candidate, "src/main/res");
            if (new File(res, "values/type.xml").isFile()) {
                return res;
            }
        }
        return null;
    }

    private static List<Path> layouts(File res) throws IOException {
        try (Stream<Path> stream = Files.walk(res.toPath())) {
            return stream.filter(p -> p.toString().endsWith(".xml"))
                    .filter(p -> p.getParent().getFileName().toString().startsWith("layout"))
                    .collect(Collectors.toList());
        }
    }

    private static String name(File res, Path path) {
        return res.toPath().relativize(path).toString().replace(File.separatorChar, '/');
    }

    private static String read(Path path) throws IOException {
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8).replace("\r\n", "\n");
    }
}
