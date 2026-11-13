/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.button;

/**
 * 蓝牙按键（灵动贴，以后的蓝牙旋钮也用）的「名称 / 位置」：用户自己打的一小段字（项目所有者 2026-11-14）。
 *
 * <h3>这段字不可信</h3>
 *
 * <p>它来自键盘、粘贴板，也可能来自导入的设置备份、被人改过的设置文件。所以写进去之前洗一遍，<b>读出来时再洗一遍</b>
 * （{@code AppConfig.getSmartButtonName}）—— 应用里拿到手的永远是洗过的：</p>
 *
 * <ul>
 *   <li>最多 {@link #MAX} 个字（按码点数：一个表情、一个生僻字算一个，不会从中间劈开）；</li>
 *   <li>只留看得见的字：各种文字的字母、数字、标点、符号、表情；换行、制表、各种空白都变成一个普通空格，连着的并成一个，
 *       头尾的去掉；</li>
 *   <li>控制字符（含 NUL）、看不见的格式字符（零宽字符、把文字方向反过来的那几个）、私用区、没定义的码点、落单的代理项
 *       一律丢掉 —— 它们能让一行字看着是另一段话、把后面的界面文字挤乱；</li>
 *   <li>叠在一个字上的附加符号最多留 {@link #MARKS} 个：叠几十层的那种会顶出这一行、盖住上下的行。</li>
 * </ul>
 *
 * <h3>它只当字用</h3>
 *
 * <p>洗过的字里仍然可以有引号、尖括号、百分号、反斜杠、斜杠这些「常见的字符」—— 它们本身没有害处，有害的是把这段字
 * 当成别的东西去解释。所以这段字只做一件事：原样显示（{@code setText} / {@code setTitle}）和原样存取。不拿它当格式串、
 * 不拼进文件名或路径、不拼进任何命令、不当 HTML 解析，也不写进黑匣子（日志里照旧用按键的短编号）。导出设置时它是 JSON 里的
 * 一个字符串值，转义由 JSON 库做。</p>
 */
public final class DeviceLabel {

    /** 最多这么多个字（码点）。 */
    public static final int MAX = 20;
    /** 叠在一个字上的附加符号最多留这么多个。 */
    static final int MARKS = 2;
    /** 进来的再长也只看前这么多个 char：不让一段几兆的字拖住界面（后面的反正留不下）。 */
    static final int READ_LIMIT = 512;

    private DeviceLabel() {
    }

    /** 洗一遍（规矩见类说明）。null、全是空白、全是丢掉的字符都得空串。洗过的再洗还是它自己。 */
    public static String clean(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        int end = Math.min(raw.length(), READ_LIMIT);
        StringBuilder out = new StringBuilder(MAX * 2);
        int kept = 0;          // 留下了几个码点
        int marks = 0;         // 上一个字后面已经叠了几个附加符号
        boolean space = false; // 欠着一个空格（前面留下过字才补）
        for (int i = 0; i < end && kept < MAX; ) {
            int cp = raw.codePointAt(i);
            int width = Character.charCount(cp);
            if (i + width > end) {
                break;  // 截断处正好劈开一个代理对：不要这半个
            }
            i += width;
            int type = Character.getType(cp);
            if (Character.isWhitespace(cp) || Character.isSpaceChar(cp)) {
                space = kept > 0;
                continue;
            }
            switch (type) {
                case Character.CONTROL:
                case Character.FORMAT:
                case Character.SURROGATE:
                case Character.PRIVATE_USE:
                case Character.UNASSIGNED:
                    continue;
                case Character.NON_SPACING_MARK:
                case Character.ENCLOSING_MARK:
                case Character.COMBINING_SPACING_MARK:
                    // 附加符号要有个字给它叠（开头的、空格后面的不要），叠到上限为止
                    if (kept == 0 || space || marks >= MARKS) {
                        continue;
                    }
                    marks++;
                    break;
                default:
                    if (space) {
                        if (kept + 1 >= MAX) {
                            // 空格之后放不下一个字了：空格也不要（不留尾巴上的空格）
                            return out.toString();
                        }
                        out.append(' ');
                        kept++;
                        space = false;
                    }
                    marks = 0;
                    break;
            }
            out.appendCodePoint(cp);
            kept++;
        }
        return out.toString();
    }
}
