package com.kooo.evcam;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 关闭开发者选项后，「其中的功能停止生效」（项目所有者 2026-10-05）。
 *
 * <p>规则只有一条，在 {@code AppConfig.DEVELOPER_KEYS}：开发者选项关着时，归它管的设置按没存过算，
 * 读到的是默认值 —— 也就是普通用户的值；存着的不动。这里钉两件事：</p>
 *
 * <ul>
 *   <li>开发者选项分区（{@code preferences.xml} 的 {@code screen_developer}）的<b>每一行</b>都在下面
 *       {@link #ROWS} 里写明它存在哪个键（只是动作、不存设置的写空）。分区里新加一行而这里没写，测试失败 ——
 *       提醒把它的键加进 {@code DEVELOPER_KEYS}，不然关掉开发者选项后它照样生效；</li>
 *   <li>写明的每一个键，关着开发者选项时按没存过算、开着时照常读；普通设置不受影响。</li>
 * </ul>
 *
 * <p>各项默认值就是关着时该用的值（关、自动、U 盘），这一点写在 {@code DEVELOPER_KEYS} 的注释里，靠人看。</p>
 */
public class DeveloperSettingsTest {

    /** 开发者选项分区里每一行 → 它存的键。 */
    private static final Map<String, List<String>> ROWS = new LinkedHashMap<>();
    /** 别的分区里只有开发者才选得了的值：存储位置（内置存储）、中转写入、拍照使用 JPEG 输出（关）。 */
    private static final Map<String, List<String>> OUTSIDE = new LinkedHashMap<>();
    /** 只是动作或说明，不存设置。 */
    private static final List<String> NONE = Collections.emptyList();

    static {
        ROWS.put("pref_recording_mode", Arrays.asList("recording_mode"));
        ROWS.put("pref_force_h264", Arrays.asList("force_h264_encoding"));
        ROWS.put("pref_screen_off_recording", Arrays.asList("screen_off_recording"));
        ROWS.put("pref_screen_off_wake_hours", Arrays.asList("screen_off_wake_min"));
        // 画面调节的参数只在开着时用到，不按没存过算（理由见 DEVELOPER_KEYS）
        ROWS.put("pref_image_adjust", Arrays.asList("image_adjust_enabled"));
        ROWS.put("pref_image_adjust_reset", NONE);
        ROWS.put("pref_camera_mapping", Arrays.asList("zeekr_camera_override_front",
                "zeekr_camera_override_back", "zeekr_camera_override_left"));
        ROWS.put("pref_permissions", NONE);
        ROWS.put("pref_raw_frame_dump", Arrays.asList("raw_frame_dump"));
        ROWS.put("pref_gpu_fisheye_preview", Arrays.asList("gpu_fisheye_preview"));
        ROWS.put("pref_gpu_fisheye_video", Arrays.asList("gpu_fisheye_video"));
        ROWS.put("pref_camera_holder_suspects", Arrays.asList("camera_holder_suspects"));
        ROWS.put("pref_repair_mp4", NONE);
        ROWS.put("pref_archive", NONE);
        ROWS.put("pref_developer_note", NONE);

        OUTSIDE.put("pref_storage_location", Arrays.asList("storage_location"));
        OUTSIDE.put("pref_relay_write", Arrays.asList("relay_write_enabled"));
        OUTSIDE.put("pref_photo_via_jpeg", Arrays.asList("photo_via_jpeg"));
    }

    /** 普通设置：关着开发者选项也照常读。 */
    private static final List<String> ORDINARY = Arrays.asList(
            "screen_off_keep_recording", "auto_start_recording", "footage_lock",
            "video_storage_limit_gb", "photo_storage_limit_gb", "custom_sd_card_path",
            "exposure_compensation", "awb_mode", "car_model");

    private static final Pattern KEY = Pattern.compile("android:key=\"([^\"]+)\"");

    @Test
    public void everyRowOfTheDeveloperSectionIsAccountedFor() throws IOException {
        Set<String> rows = developerSectionRows();
        assumeTrue("读不到 preferences.xml 的开发者选项分区，跳过", !rows.isEmpty());

        Set<String> unknown = new TreeSet<>(rows);
        unknown.removeAll(ROWS.keySet());
        assertTrue("开发者选项分区里这几行没写它存在哪个键：把键加进 AppConfig.DEVELOPER_KEYS，"
                + "再写进这里的 ROWS（只是动作的写 NONE）: " + unknown, unknown.isEmpty());

        Set<String> gone = new TreeSet<>(ROWS.keySet());
        gone.removeAll(rows);
        assertTrue("这几行已经不在开发者选项分区里了，从 ROWS 里拿掉（键也从 DEVELOPER_KEYS 里拿掉）: "
                + gone, gone.isEmpty());
    }

    @Test
    public void developerSettingsReadAsUnsetOnlyWhileLocked() {
        for (Map<String, List<String>> group : Arrays.asList(ROWS, OUTSIDE)) {
            for (Map.Entry<String, List<String>> row : group.entrySet()) {
                for (String key : row.getValue()) {
                    assertTrue(row.getKey() + "（" + key + "）关着开发者选项时应当按没存过算",
                            AppConfig.readsAsUnset(key, false));
                    assertFalse(row.getKey() + "（" + key + "）开着开发者选项时应当照常读",
                            AppConfig.readsAsUnset(key, true));
                }
            }
        }
    }

    @Test
    public void ordinarySettingsAreNeverHidden() {
        for (String key : ORDINARY) {
            assertFalse(key + " 不归开发者选项管", AppConfig.readsAsUnset(key, false));
            assertFalse(key + " 不归开发者选项管", AppConfig.readsAsUnset(key, true));
        }
    }

    /** {@code screen_developer} 里各行的 key（分区里没有嵌套的分区，读到它的结束标签为止）。 */
    private static Set<String> developerSectionRows() throws IOException {
        Set<String> rows = new LinkedHashSet<>();
        File module = findModuleRoot();
        if (module == null) {
            return rows;
        }
        String xml = new String(Files.readAllBytes(
                new File(module, "src/main/res/xml/preferences.xml").toPath()), StandardCharsets.UTF_8);
        int start = xml.indexOf("android:key=\"screen_developer\"");
        int end = start < 0 ? -1 : xml.indexOf("</PreferenceScreen>", start);
        if (end < 0) {
            return rows;
        }
        Matcher matcher = KEY.matcher(xml.substring(start, end));
        while (matcher.find()) {
            if (!"screen_developer".equals(matcher.group(1))) {
                rows.add(matcher.group(1));
            }
        }
        return rows;
    }

    /** 从工作目录往上找，直到看见 {@code src/main/res/xml/preferences.xml}。 */
    private static File findModuleRoot() {
        File dir = new File(System.getProperty("user.dir", "."));
        for (int i = 0; i < 4 && dir != null; i++) {
            if (new File(dir, "src/main/res/xml/preferences.xml").isFile()) {
                return dir;
            }
            File app = new File(dir, "app");
            if (new File(app, "src/main/res/xml/preferences.xml").isFile()) {
                return app;
            }
            dir = dir.getParentFile();
        }
        return null;
    }
}
