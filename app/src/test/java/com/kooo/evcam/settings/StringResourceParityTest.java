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
 * 每一份翻译的 strings.xml 都要和中文那份对得上。
 *
 * <h3>为什么值得测</h3>
 *
 * <p>少一条翻译，界面上就会在一片外文里冒出一句中文 —— 不报错，只是难看，
 * 而且往往要等到有人截图才被发现。语言是一份一份加的，所以这里按目录列表遍历：
 * 新增一种语言时只改这一处。</p>
 *
 * <p>更严重的是<b>占位符对不上</b>：中文写 {@code %1$s} 而英文漏了，
 * {@code getString(id, arg)} 在运行期直接抛异常，界面当场崩。
 * 这类错误编译期完全看不出来。</p>
 *
 * <h3>只做中英两份的文字</h3>
 *
 * <p>关于页这类长文字只做中文和英文：中文在 {@code values-zh/<文件>}，英文在 {@code values/<文件>}（默认，
 * 其他所有语言都落到它），文件名列在 {@link #ZH_EN_ONLY}。这两份要条目一一对得上、占位符一致；
 * 这些条目不能再出现在任何一份 strings.xml 里，否则谁覆盖谁就说不清了。</p>
 */
public class StringResourceParityTest {

    private static final Pattern STRING = Pattern.compile(
            "<string\\s+name=\"([^\"]+)\"[^>]*>(.*?)</string>", Pattern.DOTALL);
    private static final Pattern PLACEHOLDER = Pattern.compile("%\\d\\$[sd]");
    private static final Pattern PLURALS = Pattern.compile(
            "<plurals\\s+name=\"([^\"]+)\"[^>]*>(.*?)</plurals>", Pattern.DOTALL);
    private static final Pattern ITEM = Pattern.compile(
            "<item\\s+quantity=\"([a-z]+)\"\\s*>(.*?)</item>", Pattern.DOTALL);
    /** 复数条目里也有不带序号的 {@code %d}（「已锁定 %d 个文件」），一起算。 */
    private static final Pattern PLURAL_PLACEHOLDER = Pattern.compile("%(\\d\\$)?[sd]");

    /** 翻译目录。新增一种语言时，这里和 xml/locales_config.xml 一起改。 */
    private static final String[] TRANSLATIONS = {"values-en", "values-ms"};

    /** 只有中文（values-zh）和英文（values，默认）两份的文件。 */
    private static final String[] ZH_EN_ONLY = {"strings_about.xml"};

    @Test
    public void everyChineseStringHasATranslationWithMatchingPlaceholders() throws IOException {
        File module = findModuleRoot();
        assumeTrue("定位不到模块根目录，跳过", module != null);

        Map<String, String> zh = parse(new File(module, "src/main/res/values/strings.xml"));
        assumeTrue("读不到中文 strings.xml，跳过", !zh.isEmpty());

        for (String dir : TRANSLATIONS) {
            Map<String, String> other = parse(
                    new File(module, "src/main/res/" + dir + "/strings.xml"));
            assertTrue("读不到 " + dir + "/strings.xml", !other.isEmpty());

            TreeSet<String> missing = new TreeSet<>(zh.keySet());
            missing.removeAll(other.keySet());
            assertTrue("这些条目没有 " + dir + " 翻译，那个界面上会露出中文: " + missing,
                    missing.isEmpty());

            TreeSet<String> stray = new TreeSet<>(other.keySet());
            stray.removeAll(zh.keySet());
            assertTrue("这些 " + dir + " 条目在中文里没有对应，多半是改名后忘了删: " + stray,
                    stray.isEmpty());

            List<String> mismatched = new ArrayList<>();
            for (Map.Entry<String, String> entry : zh.entrySet()) {
                if (!placeholders(entry.getValue())
                        .equals(placeholders(other.get(entry.getKey())))) {
                    mismatched.add(entry.getKey());
                }
            }
            assertTrue("这些条目和 " + dir + " 的占位符不一致，运行期格式化会抛异常: "
                    + mismatched, mismatched.isEmpty());
        }
    }

    /**
     * 复数（{@code <plurals>}）同样要每种语言都有、占位符对得上。
     *
     * <p>中文和马来文只有 {@code other} 一条，英文另有 {@code one}。每种语言都必须有 {@code other}
     * （任何数量都能落到它）；{@code other} 的占位符要和中文的一致，其他条目不能多出中文没有的占位符
     * （{@code one} 可以不写数字，但不能多要参数）。</p>
     */
    @Test
    public void everyChinesePluralHasATranslationWithMatchingPlaceholders() throws IOException {
        File module = findModuleRoot();
        assumeTrue("定位不到模块根目录，跳过", module != null);

        Map<String, Map<String, String>> zh = parsePlurals(
                new File(module, "src/main/res/values/strings.xml"));
        for (Map.Entry<String, Map<String, String>> entry : zh.entrySet()) {
            assertTrue("中文复数 " + entry.getKey() + " 缺 other 条目",
                    entry.getValue().containsKey("other"));
        }

        for (String dir : TRANSLATIONS) {
            Map<String, Map<String, String>> other = parsePlurals(
                    new File(module, "src/main/res/" + dir + "/strings.xml"));

            TreeSet<String> missing = new TreeSet<>(zh.keySet());
            missing.removeAll(other.keySet());
            assertTrue("这些复数条目没有 " + dir + " 翻译: " + missing, missing.isEmpty());

            TreeSet<String> stray = new TreeSet<>(other.keySet());
            stray.removeAll(zh.keySet());
            assertTrue("这些 " + dir + " 复数条目在中文里没有对应: " + stray, stray.isEmpty());

            List<String> mismatched = new ArrayList<>();
            for (Map.Entry<String, Map<String, String>> entry : zh.entrySet()) {
                TreeSet<String> expected = pluralPlaceholders(entry.getValue().get("other"));
                Map<String, String> items = other.get(entry.getKey());
                if (!items.containsKey("other")
                        || !pluralPlaceholders(items.get("other")).equals(expected)) {
                    mismatched.add(entry.getKey() + "/other");
                }
                for (Map.Entry<String, String> item : items.entrySet()) {
                    if (!expected.containsAll(pluralPlaceholders(item.getValue()))) {
                        mismatched.add(entry.getKey() + "/" + item.getKey());
                    }
                }
            }
            assertTrue("这些复数条目和 " + dir + " 的占位符不一致，运行期格式化会出错: "
                    + mismatched, mismatched.isEmpty());
        }
    }

    @Test
    public void zhEnOnlyFilesMatchAndStayOutOfTheTranslations() throws IOException {
        File module = findModuleRoot();
        assumeTrue("定位不到模块根目录，跳过", module != null);

        for (String name : ZH_EN_ONLY) {
            Map<String, String> en = parse(new File(module, "src/main/res/values/" + name));
            Map<String, String> zh = parse(new File(module, "src/main/res/values-zh/" + name));
            assertTrue("读不到 values/" + name, !en.isEmpty());
            assertTrue("读不到 values-zh/" + name, !zh.isEmpty());
            assertTrue(name + " 的中英两份条目对不上: 英文 " + new TreeSet<>(en.keySet())
                    + " / 中文 " + new TreeSet<>(zh.keySet()), en.keySet().equals(zh.keySet()));
            for (Map.Entry<String, String> entry : zh.entrySet()) {
                assertTrue(name + " 里 " + entry.getKey() + " 的中英占位符不一致",
                        placeholders(entry.getValue()).equals(placeholders(en.get(entry.getKey()))));
            }
            for (String dir : new String[]{"values", "values-en", "values-ms"}) {
                TreeSet<String> twice = new TreeSet<>(en.keySet());
                twice.retainAll(parse(new File(module, "src/main/res/" + dir + "/strings.xml")).keySet());
                assertTrue(dir + "/strings.xml 里又出现了只做中英两份的条目: " + twice, twice.isEmpty());
            }
        }
    }

    private static TreeSet<String> placeholders(String text) {
        TreeSet<String> found = new TreeSet<>();
        Matcher matcher = PLACEHOLDER.matcher(text == null ? "" : text);
        while (matcher.find()) {
            found.add(matcher.group());
        }
        return found;
    }

    private static TreeSet<String> pluralPlaceholders(String text) {
        TreeSet<String> found = new TreeSet<>();
        Matcher matcher = PLURAL_PLACEHOLDER.matcher(text == null ? "" : text);
        while (matcher.find()) {
            found.add(matcher.group());
        }
        return found;
    }

    /** 键 → （quantity → 文字）。 */
    private static Map<String, Map<String, String>> parsePlurals(File file) throws IOException {
        Map<String, Map<String, String>> out = new LinkedHashMap<>();
        if (!file.isFile()) {
            return out;
        }
        String text = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        Matcher plurals = PLURALS.matcher(text);
        while (plurals.find()) {
            Map<String, String> items = new LinkedHashMap<>();
            Matcher item = ITEM.matcher(plurals.group(2));
            while (item.find()) {
                items.put(item.group(1), item.group(2));
            }
            out.put(plurals.group(1), items);
        }
        return out;
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
        List<File> files = new ArrayList<>();
        for (String dir : new String[]{"values", "values-en", "values-ms", "values-zh"}) {
            File[] inDir = new File(module, "src/main/res/" + dir).listFiles(
                    (d, n) -> n.startsWith("strings") && n.endsWith(".xml"));
            if (inDir != null) {
                files.addAll(java.util.Arrays.asList(inDir));
            }
        }
        for (File file : files) {
            String dir = file.getParentFile().getName() + "/" + file.getName();
            String text = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            Matcher matcher = STRING.matcher(text);
            while (matcher.find()) {
                if (hasBareApostrophe(matcher.group(2))) {
                    offenders.add(dir + ":" + matcher.group(1));
                }
            }
            // 复数条目里的每一条也一样
            Matcher plurals = PLURALS.matcher(text);
            while (plurals.find()) {
                Matcher item = ITEM.matcher(plurals.group(2));
                while (item.find()) {
                    if (hasBareApostrophe(item.group(2))) {
                        offenders.add(dir + ":" + plurals.group(1) + "/" + item.group(1));
                    }
                }
            }
        }
        assertTrue("这些字符串里的撇号没有转义，aapt 会直接编不过（写成 \\' 即可）: "
                + offenders, offenders.isEmpty());
    }

    private static boolean hasBareApostrophe(String body) {
        for (int i = 0; i < body.length(); i++) {
            if (body.charAt(i) == '\'' && (i == 0 || body.charAt(i - 1) != '\\')) {
                return true;
            }
        }
        return false;
    }

}
