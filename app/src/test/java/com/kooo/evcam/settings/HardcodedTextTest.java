package com.kooo.evcam.settings;

import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 界面文字不许写死在代码和布局里。
 *
 * <h3>为什么要钉</h3>
 *
 * <p>英文界面下一屏里冒出一行中文，最初的报告就是「主界面的『前』还是中文」。
 * 写死的中文不会让编译出错，只会在切了语言的那台车上露出来。
 * 所以规则是：出现在界面上的文字进 {@code strings.xml}，中英各一份。</p>
 *
 * <h3>哪些不算</h3>
 *
 * <ul>
 *   <li>注释；</li>
 *   <li>日志（{@code AppLog} / {@code Log}，包括跨行的）—— 日志是给维护者读的；</li>
 *   <li>拿来比较的字面量（{@code contains("…")} 这类）—— 那是和别的模块之间的约定，不上界面。</li>
 * </ul>
 *
 * <h3>白名单</h3>
 *
 * <p>下面列出的文件允许有中文，每一组写明原因。其中<b>开发者工具那几组是长期允许</b>：
 * 那些界面只要能用就行，不做英文化（项目拥有者 2026-09 定）。确实会出现在
 * 普通用户界面上、只是还没来得及搬的，要单列一组标「待迁移」，不能混进别的理由里。
 * 另有一条检查保证名单只减不增：名单里的文件已经清干净、或者已经不在了，测试就失败，
 * 提醒把它从名单里拿掉。</p>
 */
public class HardcodedTextTest {

    private static final Map<String, String> JAVA_ALLOWED = new LinkedHashMap<>();
    private static final Map<String, String> XML_ALLOWED = new LinkedHashMap<>();

    static {
        // ---- 报告与核对：诊断报告正文、当前配置全文、调试统计，给维护者看 ----
        String report = "诊断报告 / 当前配置全文 / 调试统计，给维护者看";
        for (String f : new String[]{
                "zeekr/DiagnosticsCollector.java", "zeekr/VehicleSignalProbe.java",
                "zeekr/VehicleEnumeration.java", "zeekr/PlaybackCapabilityProbe.java",
                "zeekr/SnapshotDiff.java", "zeekr/ZeekrCameraLocator.java", "zeekr/ZeekrMultiPlan.java",
                "zeekr/ZeekrCompositeProfile.java", "zeekr/CompositeStreamGeometry.java",
                "share/ShareDiagnostics.java",
                "camera/PreviewFrameRates.java", "camera/FrameRateMeter.java",
                "profile/Profile.java", "profile/CameraProfile.java", "profile/StreamSpec.java",
                "profile/LaneLayout.java", "profile/ProfileResolution.java"}) {
            JAVA_ALLOWED.put(f, report);
        }
        // ---- 纯开发者工具：入口都在开发者选项里（抽屉里的补盲 / 超视也只在解锁后显示） ----
        String developer = "开发者工具，只在开发者选项里出现";
        for (String f : new String[]{
                "AdbPermissionHelper.java", "PermissionSettingsFragment.java",
                "settings/PermissionsPreferenceFragment.java", "SystemWhitelistHelper.java",
                "CustomCameraConfigFragment.java", "CustomLayoutManager.java",
                "LogcatViewerActivity.java"}) {
            JAVA_ALLOWED.put(f, developer);
        }
        String blindSpot = "补盲 / 超视：设置页和抽屉入口都只在开发者模式解锁后出现";
        for (String f : new String[]{
                "BlindSpotCorrectionFragment.java", "BlindSpotDisclaimerDialogFragment.java",
                "BlindSpotFloatingWindowView.java", "BlindSpotLabFragment.java",
                "BlindSpotService.java", "BlindSpotSettingsFragment.java",
                "SecondaryBlindSpotAdjustFragment.java", "CarSignalManagerObserver.java",
                "DoorSignalObserver.java"}) {
            JAVA_ALLOWED.put(f, blindSpot);
        }
        JAVA_ALLOWED.put("AppLog.java", "日志上传（开发者选项）的提示和上传报告正文");
        JAVA_ALLOWED.put("StorageCleanupManager.java", "清理通知只在录到内部存储时才发，而内部存储只有开发者能选");
        // ---- 不上界面的文字 ----
        JAVA_ALLOWED.put("camera/EglSurfaceEncoder.java", "GLSL 着色器源码里的注释");
        JAVA_ALLOWED.put("settings/SettingSpec.java", "设置定义写错时抛给开发者的异常");
        JAVA_ALLOWED.put("settings/SegmentedPreference.java", "设置定义写错时抛给开发者的异常");
        JAVA_ALLOWED.put("settings/SettingsRegistry.java", "日志用的设置名；界面上的名字走 strings");
        JAVA_ALLOWED.put("KeepAliveReceiver.java", "触发原因只写进日志");
        JAVA_ALLOWED.put("zeekr/LaneCycle.java", "日志里的方位名");
        JAVA_ALLOWED.put("zeekr/RawFrameDump.java",
                "工程模式导出的那份说明文件的正文，写进 txt，不上界面");
        JAVA_ALLOWED.put("config/RecordingConfig.java", "水印颜色 / 风格名只写进日志");
        JAVA_ALLOWED.put("service/CameraRecordingService.java",
                "录制错误描述只写进日志（RecordingController.onError 不转给任何界面）");
        JAVA_ALLOWED.put("profile/ProfileMigration.java",
                "预设配置里存的名字；编辑器按配置 id 显示本地化名字，不读它");
        JAVA_ALLOWED.put("camera/SingleCamera.java",
                "拍照失败原因只写进日志（onFailed 唯一的实现改抓预览并记日志）");
        JAVA_ALLOWED.put("camera/MultiCameraManager.java",
                "相机错误码的日志说明和调试统计；状态回调用的是英文标记");
        // ---- 故意中英并列 ----
        JAVA_ALLOWED.put("share/FileShareServer.java",
                "手机浏览器里那一页：不知道手机是什么语言，中英并列");

        String xmlDeveloper = "开发者工具 / 自定义车型（排查用）的布局";
        for (String f : new String[]{
                "layout/activity_logcat_viewer.xml", "layout/activity_main_custom.xml",
                "layout/dialog_blind_spot_disclaimer.xml", "layout/dialog_wheel_settings.xml",
                "layout/fragment_blind_spot_correction.xml", "layout/fragment_blind_spot_lab.xml",
                "layout/fragment_custom_camera_config.xml", "layout/fragment_permission_settings.xml",
                "layout/fragment_secondary_blind_spot_adjust.xml",
                "layout/fragment_secondary_display_settings.xml",
                "layout/layout_custom_buttons_multi.xml",
                "layout/layout_custom_buttons_multi_vertical.xml",
                "layout/view_blind_spot_floating.xml", "layout/view_blind_spot_floating_multiview.xml",
                "layout/view_mock_turn_signal_floating.xml",
                "layout/view_preview_correction_floating.xml",
                // 自定义车型的画面长按打开的窗口调整；自定义只在开发者选项里能选
                "layout/dialog_fullscreen_preview.xml"}) {
            XML_ALLOWED.put(f, xmlDeveloper);
        }
    }

    private static final Pattern COMPARISON =
            Pattern.compile("\\.(contains|equals|startsWith|endsWith|matches)\\(\"");
    private static final Pattern XML_TEXT = Pattern.compile(
            "android:(?:text|title|summary|hint|contentDescription|dialogTitle|dialogMessage)=\"([^\"@]*)\"");

    @Test
    public void noNewHardcodedChineseInJava() throws IOException {
        File root = javaRoot();
        assumeTrue("定位不到源码目录，跳过", root != null);
        List<String> offenders = new ArrayList<>();
        for (Path path : files(root, ".java")) {
            String rel = relative(root, path);
            if (JAVA_ALLOWED.containsKey(rel)) {
                continue;
            }
            int count = countJava(read(path));
            if (count > 0) {
                offenders.add(rel + "（" + count + " 处）");
            }
        }
        assertTrue("这些文件里写死了中文，界面文字请放进 strings.xml（中英各一份）: " + offenders,
                offenders.isEmpty());
    }

    @Test
    public void noNewHardcodedChineseInLayouts() throws IOException {
        File root = resRoot();
        assumeTrue("定位不到资源目录，跳过", root != null);
        List<String> offenders = new ArrayList<>();
        for (Path path : files(root, ".xml")) {
            String rel = relative(root, path);
            // layout-sw600dp、layout-port 这些变体目录也要扫：车机走的正是 sw600dp 那一份，
            // 以前只扫 layout/，于是变体里写死的中文躲了过去（真出过这事）
            if (!(rel.startsWith("layout") || rel.startsWith("menu") || rel.startsWith("xml/"))
                    || XML_ALLOWED.containsKey(rel)) {
                continue;
            }
            int count = countXml(read(path));
            if (count > 0) {
                offenders.add(rel + "（" + count + " 处）");
            }
        }
        assertTrue("这些布局里写死了中文，请改用 @string: " + offenders, offenders.isEmpty());
    }

    /** 名单只减不增：清干净了、或者文件已经不在了，就该从名单里拿掉。 */
    @Test
    public void allowlistOnlyShrinks() throws IOException {
        File java = javaRoot();
        File res = resRoot();
        assumeTrue("定位不到源码目录，跳过", java != null && res != null);
        List<String> stale = new ArrayList<>();
        for (String rel : JAVA_ALLOWED.keySet()) {
            File file = new File(java, rel);
            if (!file.exists() || countJava(read(file.toPath())) == 0) {
                stale.add(rel);
            }
        }
        for (String rel : XML_ALLOWED.keySet()) {
            File file = new File(res, rel);
            if (!file.exists() || countXml(read(file.toPath())) == 0) {
                stale.add(rel);
            }
        }
        assertTrue("这些文件已经没有写死的中文（或已删除），请从白名单里拿掉: " + stale,
                stale.isEmpty());
    }

    // ------------------------------------------------------------------ 计数

    static int countJava(String source) {
        int count = 0;
        boolean inBlock = false;
        boolean inLog = false;
        for (String raw : source.split("\n")) {
            String line = raw.trim();
            if (inBlock) {
                if (line.contains("*/")) {
                    inBlock = false;
                }
                continue;
            }
            if (line.startsWith("/*")) {
                inBlock = !line.contains("*/");
                continue;
            }
            if (line.startsWith("//") || line.startsWith("*")) {
                continue;
            }
            if (line.contains("AppLog.") || line.startsWith("Log.") || line.contains(" Log.")) {
                inLog = !line.endsWith(";");
                continue;
            }
            if (inLog) {
                inLog = !line.endsWith(";");
                continue;
            }
            if (COMPARISON.matcher(line).find()) {
                continue;
            }
            String[] parts = line.split("\"", -1);
            for (int i = 1; i < parts.length; i += 2) {
                if (hasChinese(parts[i])) {
                    count++;
                }
            }
        }
        return count;
    }

    static int countXml(String source) {
        int count = 0;
        Matcher matcher = XML_TEXT.matcher(source);
        while (matcher.find()) {
            if (hasChinese(matcher.group(1))) {
                count++;
            }
        }
        return count;
    }

    private static boolean hasChinese(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= '一' && c <= '鿿') {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ 文件

    private static File moduleRoot() {
        for (String candidate : new String[]{".", "app"}) {
            File module = new File(candidate);
            if (new File(module, "src/main/res/values/strings.xml").exists()) {
                return module;
            }
        }
        return null;
    }

    private static File javaRoot() {
        File module = moduleRoot();
        return module == null ? null : new File(module, "src/main/java/com/kooo/evcam");
    }

    private static File resRoot() {
        File module = moduleRoot();
        return module == null ? null : new File(module, "src/main/res");
    }

    private static List<Path> files(File root, String suffix) throws IOException {
        try (Stream<Path> stream = Files.walk(root.toPath())) {
            return stream.filter(p -> p.toString().endsWith(suffix)).collect(Collectors.toList());
        }
    }

    private static String relative(File root, Path path) {
        return root.toPath().relativize(path).toString().replace(File.separatorChar, '/');
    }

    private static String read(Path path) throws IOException {
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8).replace("\r\n", "\n");
    }
}
