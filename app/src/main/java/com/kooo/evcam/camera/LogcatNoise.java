package com.kooo.evcam.camera;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * 从 logcat 抓来的行里，哪些是噪声。
 *
 * <p>诊断报告的「最近日志」和卡顿现场都从 logcat 抓本进程的行。本进程里说话最多的
 * 不是我们：App Lab 容器（标签 {@code falco_32}）把每一次跨进程调用连参数整个打出来，
 * 一行能有 4KB；编解码框架每开一次编码器就刷一屏配置。2026-09-26 那份报告，
 * 「最近日志」400 行里几乎全是容器的，只盖住了 2 秒。</p>
 *
 * <p>只按标签滤，不按内容猜。纯逻辑，有单元测试。</p>
 */
public final class LogcatNoise {

    /**
     * 滤掉的标签。
     *
     * <ul>
     *   <li>{@code falco_32}：App Lab 容器的调用跟踪。它说明过的事（前台服务由 StubService
     *       代注册、Intent 被拦截改写）已经写进平台笔记 §4.9，不必每份报告再看一遍。</li>
     *   <li>其余：Codec2 框架开编码器时的配置细节，录制出问题要看的在我们自己的日志里。</li>
     * </ul>
     */
    static final Set<String> TAGS = new HashSet<>(Arrays.asList(
            "falco_32",
            "CCodecConfig", "CCodec", "CCodecBufferChannel", "Codec2Client",
            "ReflectedParamUpdater", "ColorUtils", "MediaCodecList"));

    /** 一行最多留多长。超长的多半是在打整个对象，前面几百个字已经够认出是什么。 */
    public static final int MAX_LINE_CHARS = 400;

    private LogcatNoise() {
    }

    /** 这一行是不是噪声。 */
    public static boolean isNoise(String line) {
        String tag = tagOf(line);
        return tag != null && TAGS.contains(tag);
    }

    /** 太长就截断，并标出原来多长。 */
    public static String clip(String line) {
        if (line == null || line.length() <= MAX_LINE_CHARS) {
            return line;
        }
        return line.substring(0, MAX_LINE_CHARS) + " ... (" + line.length() + " chars)";
    }

    /**
     * {@code logcat -v time} 那种行里的标签：{@code 09-26 10:00:57.863 D/falco_32( 9314): …}。
     *
     * @return 认不出格式时返回 null
     */
    static String tagOf(String line) {
        if (line == null) {
            return null;
        }
        int slash = line.indexOf('/');
        if (slash < 0) {
            return null;
        }
        int paren = line.indexOf('(', slash);
        int colon = line.indexOf(':', slash);
        int end = paren >= 0 && (colon < 0 || paren < colon) ? paren : colon;
        if (end < 0) {
            return null;
        }
        return line.substring(slash + 1, end).trim();
    }
}
