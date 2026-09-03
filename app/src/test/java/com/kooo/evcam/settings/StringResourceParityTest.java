package com.kooo.evcam.settings;

import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 中英两份 strings.xml 必须对得上。
 *
 * <h3>为什么值得测</h3>
 *
 * <p>少一条英文，界面上就会在一片英文里冒出一句中文 —— 不报错，只是难看，
 * 而且往往要等到有人截图才被发现。</p>
 *
 * <p>更严重的是<b>占位符对不上</b>：中文写 {@code %1$s} 而英文漏了，
 * {@code getString(id, arg)} 在运行期直接抛异常，界面当场崩。
 * 这类错误编译期完全看不出来。</p>
 */
public class StringResourceParityTest {

    private static final Pattern STRING = Pattern.compile(
            "<string\\s+name=\"([^\"]+)\"[^>]*>(.*?)</string>", Pattern.DOTALL);
    private static final Pattern PLACEHOLDER = Pattern.compile("%\\d\\$[sd]");

    @Test
    public void everyChineseStringHasAnEnglishOneWithMatchingPlaceholders() throws IOException {
        File module = findModuleRoot();
        assumeTrue("定位不到模块根目录，跳过", module != null);

        Map<String, String> zh = parse(new File(module, "src/main/res/values/strings.xml"));
        Map<String, String> en = parse(new File(module, "src/main/res/values-en/strings.xml"));
        assumeTrue("读不到 strings.xml，跳过", !zh.isEmpty() && !en.isEmpty());

        TreeSet<String> missing = new TreeSet<>(zh.keySet());
        missing.removeAll(en.keySet());
        assertTrue("这些条目没有英文，英文界面上会露出中文: " + missing, missing.isEmpty());

        TreeSet<String> stray = new TreeSet<>(en.keySet());
        stray.removeAll(zh.keySet());
        assertTrue("这些英文条目在中文里没有对应，多半是改名后忘了删: " + stray, stray.isEmpty());

        List<String> mismatched = new ArrayList<>();
        for (Map.Entry<String, String> entry : zh.entrySet()) {
            if (!placeholders(entry.getValue()).equals(placeholders(en.get(entry.getKey())))) {
                mismatched.add(entry.getKey());
            }
        }
        assertTrue("这些条目中英占位符不一致，运行期格式化会抛异常: " + mismatched,
                mismatched.isEmpty());
    }

    private static TreeSet<String> placeholders(String text) {
        TreeSet<String> found = new TreeSet<>();
        Matcher matcher = PLACEHOLDER.matcher(text == null ? "" : text);
        while (matcher.find()) {
            found.add(matcher.group());
        }
        return found;
    }

    private static Map<String, String> parse(File file) throws IOException {
        Map<String, String> out = new LinkedHashMap<>();
        if (!file.isFile()) {
            return out;
        }
        String text = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        Matcher matcher = STRING.matcher(text);
        while (matcher.find()) {
            out.put(matcher.group(1), matcher.group(2));
        }
        return out;
    }

    private static File findModuleRoot() {
        File dir = new File(System.getProperty("user.dir", "."));
        for (int i = 0; i < 4 && dir != null; i++) {
            if (new File(dir, "src/main/res/values/strings.xml").isFile()) {
                return dir;
            }
            File app = new File(dir, "app");
            if (new File(app, "src/main/res/values/strings.xml").isFile()) {
                return app;
            }
            dir = dir.getParentFile();
        }
        return null;
    }

    /**
     * 撇号必须转义。
     *
     * <p>{@code app's} 这样的写法会让 aapt 直接失败：
     * 「Invalid unicode escape sequence in string」—— 报的位置和错的东西都对不上，
     * 每次都要重新想一遍才认出来是撇号。这条测试替 CI 挡住它。</p>
     */
    @Test
    public void apostrophesAreEscaped() throws IOException {
        File module = findModuleRoot();
        assumeTrue("定位不到模块根目录，跳过", module != null);

        List<String> offenders = new ArrayList<>();
        for (String dir : new String[]{"values", "values-en"}) {
            File file = new File(module, "src/main/res/" + dir + "/strings.xml");
            if (!file.isFile()) {
                continue;
            }
            String text = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            Matcher matcher = STRING.matcher(text);
            while (matcher.find()) {
                String body = matcher.group(2);
                for (int i = 0; i < body.length(); i++) {
                    if (body.charAt(i) == '\''
                            && (i == 0 || body.charAt(i - 1) != '\\')) {
                        offenders.add(dir + "/" + matcher.group(1));
                        break;
                    }
                }
            }
        }
        assertTrue("这些字符串里的撇号没有转义，aapt 会直接编不过（写成 \\' 即可）: "
                + offenders, offenders.isEmpty());
    }

}
