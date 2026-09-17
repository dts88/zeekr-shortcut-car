package com.kooo.evcam.update;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 把 GitHub 发布页上的说明，整理成「检查更新」对话框里能直接读的一段纯文本。
 *
 * <h3>为什么不原样显示</h3>
 *
 * <p>发布说明是给网页写的 Markdown：有标题、加粗、链接，而且后半截是每个版本都一样的样板 ——
 * 「车上验证过哪些」「怎么开始用」「安全须知」「致谢」。这些在网页上有用，
 * 在一个问「要不要装这一版」的对话框里只会把真正的改动挤到看不见的地方。
 * 所以只留<b>改了什么</b>，Markdown 记号去掉，剩下的就是人话。</p>
 *
 * <p>纯 Java，不碰 Android，方便直接跑单元测试。</p>
 */
public final class ReleaseNotes {

    /**
     * 到这几个标题就停：它们是 {@code .github/scripts/release_notes.py} 给每个版本都附上的样板，
     * 与「这一版改了什么」无关。
     */
    private static final String[] BOILERPLATE_HEADINGS = {
            "verified on the vehicle", "getting started", "safety", "credits",
    };

    /** 发布页上那句「下载下面的 apk 用 App Lab 装」—— 在应用里是应用自己下载，这句是错的。 */
    // 两头只吃空格不吃换行：这句常和别的话挤在同一段里，吃掉换行会把后面那一段也并进来
    private static final Pattern SIDELOAD_SENTENCE = Pattern.compile(
            "[ \\t]*Download the `?\\.apk`? below and sideload it through App Lab\\.[ \\t]*");
    private static final Pattern LINK = Pattern.compile("\\[([^\\]]+)]\\([^)]*\\)");
    private static final Pattern BOLD = Pattern.compile("\\*\\*([^*]+)\\*\\*");
    private static final Pattern CODE = Pattern.compile("`([^`]+)`");

    /** 一次最多拼几个版本：跨很多版升级时，最近几版最要紧，再往前的看网页。 */
    public static final int MAX_VERSIONS = 5;

    /** 对话框里放得下、也读得完的长度。 */
    public static final int MAX_CHARS = 3000;

    private ReleaseNotes() {
    }

    /** 一个版本的号和它在发布页上的说明原文。 */
    public static final class Entry {
        public final String tagName;
        public final String body;

        public Entry(String tagName, String body) {
            this.tagName = tagName;
            this.body = body;
        }
    }

    /**
     * 一个版本的说明：只留改动，去掉 Markdown。
     *
     * @return 纯文本；原文为空或只有样板时返回空字符串
     */
    public static String summarise(String markdown) {
        if (markdown == null || markdown.trim().isEmpty()) {
            return "";
        }
        String[] lines = markdown.replace("\r\n", "\n").split("\n", -1);
        StringBuilder out = new StringBuilder();
        boolean titleSkipped = false;

        for (String raw : lines) {
            String line = raw;
            String trimmed = line.trim();

            if (trimmed.startsWith("#")) {
                String heading = trimmed.replaceFirst("^#+\\s*", "");
                if (!titleSkipped) {
                    // 第一个标题是「应用名 + 版本号」，对话框标题里已经有了
                    titleSkipped = true;
                    continue;
                }
                if (isBoilerplate(heading)) {
                    break;
                }
                // 「What changed」「Main features」这类小标题留着，只是去掉井号
                appendParagraphBreak(out);
                out.append(inline(heading)).append('\n');
                continue;
            }

            if (trimmed.isEmpty()) {
                appendParagraphBreak(out);
                continue;
            }

            if (trimmed.startsWith("- ") || trimmed.startsWith("* ")) {
                if (out.length() > 0 && out.charAt(out.length() - 1) != '\n') {
                    out.append('\n');
                }
                out.append("• ").append(inline(trimmed.substring(2).trim()));
                continue;
            }

            if (line.startsWith("  ") && out.length() > 0
                    && out.charAt(out.length() - 1) != '\n') {
                // CHANGELOG 里一条要点折行后，续行是缩进两格的 —— 接回上一行
                out.append(' ').append(inline(trimmed));
                continue;
            }

            if (out.length() > 0 && out.charAt(out.length() - 1) != '\n') {
                out.append(' ');
            }
            out.append(inline(trimmed));
        }

        String text = SIDELOAD_SENTENCE.matcher(out.toString()).replaceAll(" ");
        text = text.replaceAll("[ \\t]+\\n", "\n")
                .replaceAll(" {2,}", " ")
                .replaceAll("\\n{3,}", "\n\n")
                .trim();
        return text;
    }

    /**
     * 跨多个版本升级时，把中间每一版的改动都列出来，新的在前。
     *
     * @param newerThanCurrent 比手上这版新、且会被推送的版本，已按新到旧排好
     * @return 纯文本；一个有内容的版本都没有时返回空字符串
     */
    public static String combine(List<Entry> newerThanCurrent) {
        if (newerThanCurrent == null || newerThanCurrent.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        int shown = 0;
        for (Entry entry : newerThanCurrent) {
            if (shown >= MAX_VERSIONS) {
                break;
            }
            String summary = summarise(entry.body);
            if (summary.isEmpty()) {
                continue;
            }
            if (out.length() > 0) {
                out.append("\n\n");
            }
            // 只有一个版本时不必再写版本号，对话框标题里就有；多个版本才需要分隔
            if (newerThanCurrent.size() > 1) {
                out.append("[").append(entry.tagName).append("]\n");
            }
            out.append(summary);
            shown++;
        }
        String text = out.toString();
        if (text.length() > MAX_CHARS) {
            int cut = text.lastIndexOf('\n', MAX_CHARS);
            text = text.substring(0, cut > MAX_CHARS / 2 ? cut : MAX_CHARS).trim() + "\n…";
        }
        return text;
    }

    private static boolean isBoilerplate(String heading) {
        String lower = heading.toLowerCase(Locale.US);
        for (String boilerplate : BOILERPLATE_HEADINGS) {
            if (lower.startsWith(boilerplate)) {
                return true;
            }
        }
        return false;
    }

    /** 行内的 Markdown 记号：链接留文字，加粗和代码留内容。 */
    private static String inline(String text) {
        String result = replaceGroup(LINK, text);
        result = replaceGroup(BOLD, result);
        result = replaceGroup(CODE, result);
        return result;
    }

    private static String replaceGroup(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        StringBuffer out = new StringBuffer();
        while (matcher.find()) {
            matcher.appendReplacement(out, Matcher.quoteReplacement(matcher.group(1)));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    private static void appendParagraphBreak(StringBuilder out) {
        if (out.length() == 0) {
            return;
        }
        if (out.charAt(out.length() - 1) != '\n') {
            out.append('\n');
        }
        if (out.length() < 2 || out.charAt(out.length() - 2) != '\n') {
            out.append('\n');
        }
    }
}
