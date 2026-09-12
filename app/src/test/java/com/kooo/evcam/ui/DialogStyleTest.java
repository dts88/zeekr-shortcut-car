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
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 对话框只有一种建法。
 *
 * <h3>为什么要钉</h3>
 *
 * <p>「设置里的确认键看不见」被报过五次，每次都当成颜色问题修：往主题里补
 * {@code alertDialogTheme}、把主题直接传进构造函数、显示时把按钮重画一遍。
 * 都没修好，因为根子不在颜色 —— 应用里大部分对话框是<b>框架</b>的
 * {@code android.app.AlertDialog}，它那一栏按钮长什么样由车机 ROM 说了算，
 * 在实车上整栏不显示。</p>
 *
 * <p>同一时期用 {@code MaterialAlertDialogBuilder} 的那几个（相机映射、设备名）
 * 一直是好的：Material 对话框自带布局，不问 ROM。所以现在只留这一条路，
 * 用这条测试钉住 —— 再有人写一个框架对话框，这里就红。</p>
 */
public class DialogStyleTest {

    /** 我们自己的对话框主题。Material 对话框也必须带上它，否则颜色回到默认。 */
    private static final String THEME = "R.style.Theme_Cam_MaterialAlertDialog";

    private static final Pattern FRAMEWORK =
            Pattern.compile("new\\s+(?:android\\.app\\.)?AlertDialog\\.Builder\\s*\\(");
    private static final Pattern MATERIAL =
            Pattern.compile("new\\s+(?:com\\.google\\.android\\.material\\.dialog\\.)?"
                    + "MaterialAlertDialogBuilder\\s*\\(");

    /**
     * 构造参数里有 {@code requireContext()} 这种带括号的写法，按括号配对找结尾不值得，
     * 所以往后看一小段就够了 —— 主题是第二个参数，不会离得更远。
     */
    private static final int ARGS_WINDOW = 160;

    @Test
    public void noFrameworkDialogs() throws IOException {
        File root = javaRoot();
        assumeTrue("定位不到源码目录，跳过", root != null);
        List<String> offenders = new ArrayList<>();
        for (Path path : sources(root)) {
            String source = strip(read(path));
            if (FRAMEWORK.matcher(source).find()) {
                offenders.add(relative(root, path));
            }
        }
        assertTrue("这些文件用了框架对话框（android.app.AlertDialog），"
                        + "它的按钮栏在实车上不显示。请改用 MaterialAlertDialogBuilder: " + offenders,
                offenders.isEmpty());
    }

    @Test
    public void everyDialogCarriesOurTheme() throws IOException {
        File root = javaRoot();
        assumeTrue("定位不到源码目录，跳过", root != null);
        List<String> offenders = new ArrayList<>();
        for (Path path : sources(root)) {
            String source = strip(read(path));
            Matcher matcher = MATERIAL.matcher(source);
            while (matcher.find()) {
                int end = Math.min(source.length(), matcher.end() + ARGS_WINDOW);
                if (!source.substring(matcher.end(), end).contains(THEME)) {
                    offenders.add(relative(root, path));
                    break;
                }
            }
        }
        assertTrue("这些对话框没带 " + THEME + "，颜色会回到默认: " + offenders, offenders.isEmpty());
    }

    // ------------------------------------------------------------------ 工具

    /** 注释里会提到框架对话框（说明为什么不用它），去掉注释再看代码。 */
    private static String strip(String source) {
        return source.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("(?m)//.*$", "");
    }

    private static File javaRoot() {
        for (String candidate : new String[]{".", "app"}) {
            File module = new File(candidate);
            File java = new File(module, "src/main/java/com/kooo/evcam");
            if (java.isDirectory()) {
                return java;
            }
        }
        return null;
    }

    private static List<Path> sources(File root) throws IOException {
        try (Stream<Path> stream = Files.walk(root.toPath())) {
            return stream.filter(p -> p.toString().endsWith(".java")).collect(Collectors.toList());
        }
    }

    private static String relative(File root, Path path) {
        return root.toPath().relativize(path).toString().replace(File.separatorChar, '/');
    }

    private static String read(Path path) throws IOException {
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8).replace("\r\n", "\n");
    }
}
