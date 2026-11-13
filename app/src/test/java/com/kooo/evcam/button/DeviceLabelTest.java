/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.button;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 蓝牙按键的「名称 / 位置」是用户打的字（也可能是导入的、被人改过的），{@link DeviceLabel#clean} 把它洗成只能当字用的
 * 一小段：不超长、没有看不见的字符、不会把一行顶乱。
 */
public class DeviceLabelTest {

    private static int points(String s) {
        return s.codePointCount(0, s.length());
    }

    @Test
    public void ordinaryNamesPassThrough() {
        assertEquals("方向盘左", DeviceLabel.clean("方向盘左"));
        assertEquals("Driver door", DeviceLabel.clean("Driver door"));
        assertEquals("后排-右 #2", DeviceLabel.clean("后排-右 #2"));
        assertEquals("Stereng (kiri)", DeviceLabel.clean("Stereng (kiri)"));
        assertEquals("中控台，靠近杯架", DeviceLabel.clean("中控台，靠近杯架"));
    }

    @Test
    public void nothingBecomesNothing() {
        assertEquals("", DeviceLabel.clean(null));
        assertEquals("", DeviceLabel.clean(""));
        assertEquals("", DeviceLabel.clean("   \t\n  "));
        assertEquals("全是看不见的：也是空", "", DeviceLabel.clean("\u0000​‎‮﻿"));
    }

    /** 最多 20 个字，按码点数：汉字、字母、表情各算一个；表情（代理对）不会被从中间劈开。 */
    @Test
    public void atMostTwentyCharactersWithoutSplittingOne() {
        assertEquals("一二三四五六七八九十一二三四五六七八九十",
                DeviceLabel.clean("一二三四五六七八九十一二三四五六七八九十多出来的"));
        assertEquals(20, points(DeviceLabel.clean("abcdefghijklmnopqrstuvwxyz")));
        String faces = new String(new char[30]).replace("\0", "😀");
        String kept = DeviceLabel.clean(faces);
        assertEquals("20 个表情", 20, points(kept));
        assertEquals("每个表情两个 char，一个都没劈开", 40, kept.length());
        assertFalse(Character.isHighSurrogate(kept.charAt(kept.length() - 1)));
        // 截到第 20 个字正好在空格后面：不留尾巴上的空格
        assertEquals("一二三四五六七八九十一二三四五六七八九", DeviceLabel.clean("一二三四五六七八九十一二三四五六七八九 十"));
    }

    /** 换行、制表、全角空格、不换行空格都变成一个普通空格；连着的并成一个；头尾的去掉。名字永远只有一行。 */
    @Test
    public void whitespaceCollapsesToSingleSpaces() {
        assertEquals("a b", DeviceLabel.clean("  a \t\r\n b  "));
        assertEquals("前排 左", DeviceLabel.clean("前排　 左"));
        assertEquals("line1 line2", DeviceLabel.clean("line1 line2"));
        assertFalse(DeviceLabel.clean("a\nb\nc").contains("\n"));
    }

    /**
     * 看不见的字符一律丢掉：控制字符（NUL、退格、响铃、ESC）、零宽字符、把文字方向反过来的那几个、字节序标记、私用区、
     * 没定义的码点、落单的代理项。它们能让一行字看着是另一段话，或者让后面的字乱掉。
     */
    @Test
    public void invisibleCharactersAreDropped() {
        assertEquals("ab", DeviceLabel.clean("a\u0000b"));
        assertEquals("ab", DeviceLabel.clean("a\u0007\u0008\u001Bb"));
        assertEquals("ab", DeviceLabel.clean("a​‌‍⁠b"));
        assertEquals("abc", DeviceLabel.clean("a‮b‬⁦c⁩"));
        assertEquals("ab", DeviceLabel.clean("﻿ab￿"));
        assertEquals("落单的代理项", "ab", DeviceLabel.clean("a\uD83Db"));
        assertEquals("ab", DeviceLabel.clean("a\uDE00b"));
    }

    /** 叠在一个字上的附加符号最多两个：叠几十层的那种会顶出这一行。开头的、空格后面没字可叠的不要。 */
    @Test
    public void stackedMarksAreCapped() {
        StringBuilder zalgo = new StringBuilder("Z");
        for (int i = 0; i < 40; i++) {
            zalgo.append('́');
        }
        zalgo.append("a");
        assertEquals("Ź́a", DeviceLabel.clean(zalgo.toString()));
        assertEquals("正常的重音留着", "café", DeviceLabel.clean("café"));
        assertEquals("a", DeviceLabel.clean("́a"));
        assertEquals("a b", DeviceLabel.clean("a ́b"));
    }

    /**
     * 常见的字符都留着 —— 引号、尖括号、百分号、反斜杠、斜杠、美元号、分号本身没有害处，有害的是把这段字当别的东西解释；
     * 这里只保证它是一小段看得见的字。当格式串的参数、放进 JSON、当 HTML 的字看待，都原样出来（见下面几条）。
     */
    @Test
    public void commonPunctuationIsKept() {
        for (String s : new String[] {"50% \"left\"", "<b>x</b> & y", "a\\b/c", "$(rm -rf /)", "'; DROP TABLE x;--",
                "../../etc/passwd", "%s%n%1$s%d", "{\"k\":1}", "`id`", "a|b>c"}) {
            assertEquals(s, DeviceLabel.clean(s));
        }
    }

    /** 当格式串的<b>参数</b>用是安全的：里面的 %s、%n 不会被再解释一遍。（应用里不拿它当格式串本身。） */
    @Test
    public void percentSignsAreInertAsFormatArguments() {
        String name = DeviceLabel.clean("%s%n%1$s%d");
        assertEquals("灵动贴 %s%n%1$s%d", String.format("灵动贴 %1$s", name));
    }

    /** 再长也很快、也只留 20 个字：一段一兆的字只看开头那一小截。 */
    @Test
    public void hugeInputIsCutEarly() {
        StringBuilder huge = new StringBuilder();
        for (int i = 0; i < 1_000_000; i++) {
            huge.append((char) ('a' + i % 26));
        }
        long start = System.nanoTime();
        String kept = DeviceLabel.clean(huge.toString());
        long tookMs = (System.nanoTime() - start) / 1_000_000;
        assertEquals(20, kept.length());
        assertTrue("洗一兆个字用了 " + tookMs + " ms", tookMs < 200);
        // 前面全是丢掉的字符、后面才是字：只看开头那一截，后面的不找了
        StringBuilder padded = new StringBuilder();
        for (int i = 0; i < DeviceLabel.READ_LIMIT; i++) {
            padded.append('​');
        }
        padded.append("late");
        assertEquals("", DeviceLabel.clean(padded.toString()));
    }

    /** 洗过的再洗还是它自己：存的时候洗一遍、读的时候再洗一遍，不会越洗越少。 */
    @Test
    public void cleaningIsIdempotent() {
        for (String s : new String[] {"  a \t b  ", "一二三四五六七八九十一二三四五六七八九 十", "Ź́́́a",
                "a‮b", "😀 😀", "x\uD83D", "%s \"q\" <b>", "café ‘left’"}) {
            String once = DeviceLabel.clean(s);
            assertEquals(once, DeviceLabel.clean(once));
            assertTrue(points(once) <= DeviceLabel.MAX);
        }
    }

    /** 随手乱打：什么都别想让它超长、带出看不见的字符、或者抛异常。 */
    @Test
    public void randomInputNeverBreaksTheRules() {
        java.util.Random random = new java.util.Random(20261114);
        for (int round = 0; round < 5000; round++) {
            int length = random.nextInt(80);
            StringBuilder raw = new StringBuilder();
            for (int i = 0; i < length; i++) {
                switch (random.nextInt(6)) {
                    case 0:
                        raw.append((char) random.nextInt(0x20));           // 控制字符
                        break;
                    case 1:
                        raw.append((char) (0x2000 + random.nextInt(0x70))); // 各种空白、零宽、方向控制
                        break;
                    case 2:
                        raw.append((char) (0xD800 + random.nextInt(0x800))); // 代理项，多半落单
                        break;
                    case 3:
                        raw.append((char) (0x0300 + random.nextInt(0x70))); // 附加符号
                        break;
                    case 4:
                        raw.appendCodePoint(0x1F600 + random.nextInt(0x40)); // 表情
                        break;
                    default:
                        raw.append((char) random.nextInt(0xFFFF));
                        break;
                }
            }
            String kept = DeviceLabel.clean(raw.toString());
            assertTrue(points(kept) <= DeviceLabel.MAX);
            assertEquals(kept, kept.trim());
            assertFalse(kept.contains("  "));
            assertEquals(kept, DeviceLabel.clean(kept));
            for (int i = 0; i < kept.length(); ) {
                int cp = kept.codePointAt(i);
                i += Character.charCount(cp);
                int type = Character.getType(cp);
                assertFalse("留下了看不见的字符 U+" + Integer.toHexString(cp),
                        type == Character.CONTROL || type == Character.FORMAT || type == Character.SURROGATE
                                || type == Character.PRIVATE_USE || type == Character.UNASSIGNED
                                || type == Character.LINE_SEPARATOR || type == Character.PARAGRAPH_SEPARATOR
                                || (cp != ' ' && Character.isSpaceChar(cp)));
            }
        }
    }
}
