package com.kooo.evcam.ui;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 哪些布局算「核心界面」。
 *
 * <p>规范（`docs/ui-spec.md`）管的是用户用得到的那些界面。开发者工具、自定义车型、
 * 副屏这些是排查用的，不参与设计体系 —— 名单写在这一处，几条测试共用，
 * 不各抄一份（抄三份的下场是改了两份、忘了第三份）。</p>
 *
 * <p>要扫<b>所有</b> layout 变体目录，不只是 `layout/`：车机屏走的曾经是
 * `layout-sw600dp/`，而重做只做在 `layout/` 上，于是界面改了四个月、车上一直是旧的。</p>
 */
final class CoreLayouts {

    /** 排查用的界面，不参与设计体系。 */
    private static final Set<String> TROUBLESHOOTING = new HashSet<>(Arrays.asList(
            "activity_logcat_viewer.xml", "activity_main_custom.xml",
            "dialog_blind_spot_disclaimer.xml", "dialog_fullscreen_preview.xml",
            "dialog_wheel_settings.xml", "fragment_blind_spot_correction.xml",
            "fragment_blind_spot_lab.xml", "fragment_custom_camera_config.xml",
            "fragment_secondary_blind_spot_adjust.xml",
            "fragment_secondary_display_settings.xml", "layout_custom_buttons_multi.xml",
            "layout_custom_buttons_multi_vertical.xml", "layout_custom_buttons_standard.xml",
            "layout_custom_buttons_standard_vertical.xml", "presentation_secondary_display.xml",
            "presentation_secondary_display_multiview.xml", "view_blind_spot_floating.xml",
            "view_blind_spot_floating_multiview.xml",
            "view_mock_turn_signal_floating.xml", "view_preview_correction_floating.xml"));

    private CoreLayouts() {
    }

    /** `app/src/main/res`，从模块目录或仓库根目录跑都能找到；找不到返回 null。 */
    static File resRoot() {
        for (String candidate : new String[]{".", "app"}) {
            File res = new File(candidate, "src/main/res");
            if (new File(res, "values/type.xml").isFile()) {
                return res;
            }
        }
        return null;
    }

    /** 所有 layout 目录下的布局，含 layout-sw600dp 这类变体。 */
    static List<Path> all(File res) throws IOException {
        try (Stream<Path> stream = Files.walk(res.toPath())) {
            return stream.filter(p -> p.toString().endsWith(".xml"))
                    .filter(p -> p.getParent().getFileName().toString().startsWith("layout"))
                    .collect(Collectors.toList());
        }
    }

    static boolean isCore(Path path) {
        return !TROUBLESHOOTING.contains(path.getFileName().toString());
    }

    static String name(File res, Path path) {
        return res.toPath().relativize(path).toString().replace(File.separatorChar, '/');
    }

    static String read(Path path) throws IOException {
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8).replace("\r\n", "\n");
    }
}
