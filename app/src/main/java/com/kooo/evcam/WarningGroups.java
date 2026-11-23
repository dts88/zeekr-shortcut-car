/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 警告落盘时把同一种的合起来（{@link AppLog} 的 warnings.log，诊断报告「9.1 应用记下的警告和错误」读它）。
 *
 * <p>同一种 = 同一个 tag，第一行里两位以上的数字都当成「#」以后一样：「slow sync on camera 2: 785ms」和「…: 512ms」是同一种，
 * 相机号这种一位数的不动（相机 0 和相机 2 分开算）。同一种 {@link #GAP_MS} 内只存第一条；下一次存时补一句：中间省掉了几条，
 * 省掉的那几条第一行最后一个两位以上的数的中位、最大（带着紧跟它的单位，比如 ms）。以前只合完全一样的那一句，
 * 每条都带着不同的毫秒数的就一条不少地存。</p>
 *
 * <p>项目所有者 2026-11-22 同意：报告里最近 150 条几乎全是录像时每 5 秒一条的写盘同步慢，别的警告只留得下最近十几分钟。
 * 只整理落盘的清单；logcat、内存里那 5000 行照旧每条都有。</p>
 */
final class WarningGroups {

    /** 同一种多久只存一条。 */
    static final long GAP_MS = 60_000L;
    /** 记着的种类超过这么多就全忘掉重来（每一种第一条照存，只是少合一次）。 */
    private static final int MAX_KINDS = 500;
    /** 一种里最多记这么多个数（一分钟里同一种再多也够算中位、最大）。 */
    private static final int MAX_VALUES = 512;
    /** 一行只看这么长（键、最后一个数都在这里面找）。 */
    private static final int LINE_MAX = 240;
    private static final Pattern MULTI_DIGIT = Pattern.compile("\\d{2,}");
    private static final Pattern NUMBER_WITH_UNIT = Pattern.compile("(\\d{2,})([A-Za-z%]*)");

    private final Map<String, Kind> kinds = new HashMap<>();

    /** 同一种的键：tag 加第一行的样子（两位以上的数字换成「#」，最长 120 个字）。 */
    static String key(String tag, String firstLine) {
        String shape = MULTI_DIGIT.matcher(firstLine).replaceAll("#");
        return tag + "|" + (shape.length() > 120 ? shape.substring(0, 120) : shape);
    }

    /**
     * 来了一条警告或错误。
     *
     * @param now 此刻（同一个钟就行，开机起算）
     * @return 要存：补在它后面的那一句（上一段省掉过才有，带换行；没省掉过是空串）；不存：null
     */
    synchronized String offer(String tag, String firstLine, long now) {
        // 很长的一行（整段数据打进来的）只看前面一截：键本来就只取 120 个字，两遍正则不在锁里跑整行
        String line = firstLine.length() > LINE_MAX ? firstLine.substring(0, LINE_MAX) : firstLine;
        String key = key(tag, line);
        Kind kind = kinds.get(key);
        if (kind != null && now - kind.savedAt < GAP_MS) {
            kind.skip(line);
            return null;
        }
        String note = kind == null ? "" : kind.note();
        if (kind == null && kinds.size() >= MAX_KINDS) {
            kinds.clear();
        }
        kinds.put(key, new Kind(now));
        return note;
    }

    /** 一种：上一条存的时刻，之后省掉了几条、它们的数。 */
    private static final class Kind {
        final long savedAt;
        int skipped;
        long[] values = new long[8];
        int count;
        /** 数后面紧跟的单位（ms 这样）：它在键里（「#ms」），同一种都一样。 */
        String unit = "";

        Kind(long savedAt) {
            this.savedAt = savedAt;
        }

        void skip(String firstLine) {
            skipped++;
            Matcher m = NUMBER_WITH_UNIT.matcher(firstLine);
            String digits = null;
            String suffix = null;
            while (m.find()) {
                digits = m.group(1);
                suffix = m.group(2);
            }
            if (digits == null || count >= MAX_VALUES || digits.length() > 18) {
                return;
            }
            if (count == values.length) {
                values = Arrays.copyOf(values, values.length * 2);
            }
            values[count++] = Long.parseLong(digits);
            unit = suffix;
        }

        /** 补在下一条后面的那一句；没省掉过是空串。 */
        String note() {
            if (skipped == 0) {
                return "";
            }
            StringBuilder s = new StringBuilder("    (").append(skipped)
                    .append(skipped == 1 ? " similar message in the minute before this one was not saved"
                            : " similar messages in the minute before this one were not saved");
            if (count > 0) {
                long[] sorted = Arrays.copyOf(values, count);
                Arrays.sort(sorted);
                s.append("; median ").append(sorted[(count - 1) / 2]).append(unit)
                        .append(", max ").append(sorted[count - 1]).append(unit);
            }
            return s.append(")\n").toString();
        }
    }
}
