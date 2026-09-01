package com.kooo.evcam.settings;

import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 设置界面里用到的 key，XML 里必须真有。
 *
 * <h3>为什么要有这么一条测试</h3>
 *
 * <p>{@code findPreference} 找不到 key 时返回 null，<b>不报错</b>。
 * 于是「查一下、null 就整个 return」这种写法，在 key 被改名之后会安静地
 * 让一整块界面失去响应 —— 点了没反应，日志里也没有任何线索。</p>
 *
 * <p>这件事已经发生过：0.21.0 把设置分区改成嵌套 {@code PreferenceScreen} 之后，
 * 开发者选项那一块还在找旧的 {@code cat_developer}，四个入口一个都没接上，
 * 一直到有人点了才发现。</p>
 *
 * <p>这条测试直接读源文件比对，不需要跑 Android。</p>
 */
public class PreferenceKeysTest {

    private static final Pattern XML_KEY = Pattern.compile("android:key=\"([^\"]+)\"");

    /** 代码里按 key 去拿一个 Preference 的几种写法。 */
    private static final Pattern CODE_KEY = Pattern.compile(
            "(?:findPreference|onClick|bindSwitch|bindEnum|bindSlider|bindOverlaySwitch"
                    + "|bindGigabyteLimit)\\(\\s*\"([^\"]+)\"");

    @Test
    public void everyKeyUsedInCodeExistsInTheXml() throws IOException {
        File moduleRoot = findModuleRoot();
        // 找不到源码就跳过（换了工作目录的场景），不要报一个假的失败
        assumeTrue("定位不到模块根目录，跳过", moduleRoot != null);

        Set<String> declared = extract(
                new File(moduleRoot, "src/main/res/xml/preferences.xml"), XML_KEY);
        Set<String> used = new LinkedHashSet<>();
        used.addAll(extract(new File(moduleRoot,
                "src/main/java/com/kooo/evcam/settings/SettingsPreferenceFragment.java"), CODE_KEY));
        used.addAll(extract(new File(moduleRoot,
                "src/main/java/com/kooo/evcam/settings/SettingsHeadersFragment.java"), CODE_KEY));

        assumeTrue("源文件读不到，跳过", !declared.isEmpty() && !used.isEmpty());

        Set<String> missing = new TreeSet<>(used);
        missing.removeAll(declared);
        assertTrue("这些 key 在 preferences.xml 里不存在，对应的界面会点了没反应: " + missing,
                missing.isEmpty());
    }

    private static Set<String> extract(File file, Pattern pattern) throws IOException {
        Set<String> found = new LinkedHashSet<>();
        if (!file.isFile()) {
            return found;
        }
        String text = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            found.add(matcher.group(1));
        }
        return found;
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
