package com.kooo.evcam.settings;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 日间与夜间两份 colors.xml 必须同名、同数量。
 *
 * <h3>为什么要钉</h3>
 *
 * <p>日间加了一个颜色、夜间忘了加，编译不会报错 —— 夜间会安静地借用日间的值。
 * 表现是「夜里某一块突然是白的」，而那往往是在车上、晚上、开着车时才看到。
 * 以前那份夜间配色就是这样一点点和日间漂开的。</p>
 *
 * <p>还有一条：夜间主题文件不许再出现。主题只有一份，颜色靠语义名分昼夜 ——
 * 多一份主题就又多一处要「记得两边都改」的地方。</p>
 */
public class ColorResourceParityTest {

    private static final Pattern COLOR_NAME = Pattern.compile("<color\\s+name=\"([^\"]+)\"");

    @Test
    public void dayAndNightDefineTheSameColors() throws IOException {
        File module = findModuleRoot();
        assumeTrue("定位不到模块根目录，跳过", module != null);

        Set<String> day = names(new File(module, "src/main/res/values/colors.xml"));
        Set<String> night = names(new File(module, "src/main/res/values-night/colors.xml"));
        assumeTrue("颜色文件读不到，跳过", !day.isEmpty() && !night.isEmpty());

        Set<String> onlyDay = new TreeSet<>(day);
        onlyDay.removeAll(night);
        Set<String> onlyNight = new TreeSet<>(night);
        onlyNight.removeAll(day);
        assertTrue("只在日间定义的颜色（夜间会借用日间的值）: " + onlyDay, onlyDay.isEmpty());
        assertTrue("只在夜间定义的颜色: " + onlyNight, onlyNight.isEmpty());
    }

    @Test
    public void thereIsOnlyOneTheme() {
        File module = findModuleRoot();
        assumeTrue("定位不到模块根目录，跳过", module != null);
        assertFalse("values-night/themes.xml 又出现了 —— 主题只该有一份，昼夜靠颜色区分",
                new File(module, "src/main/res/values-night/themes.xml").exists());
    }

    private static Set<String> names(File file) throws IOException {
        Set<String> out = new TreeSet<>();
        if (!file.exists()) {
            return out;
        }
        String text = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        Matcher matcher = COLOR_NAME.matcher(text);
        while (matcher.find()) {
            out.add(matcher.group(1));
        }
        return out;
    }

    private static File findModuleRoot() {
        for (String candidate : new String[]{".", "app"}) {
            File module = new File(candidate);
            if (new File(module, "src/main/res/values/colors.xml").exists()) {
                return module;
            }
        }
        return null;
    }
}
