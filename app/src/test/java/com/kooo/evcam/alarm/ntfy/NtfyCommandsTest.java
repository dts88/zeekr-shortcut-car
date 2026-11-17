/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.alarm.ntfy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * ntfy 命令那三道门：开发者选项、付费档、开关 —— 一个纯函数，八种组合全在这里。
 *
 * <h3>钉住的是顺序，不只是结果</h3>
 *
 * <p>这类判断最容易写反的一步是「付费且开着」该不该盖过「开发者选项关着」。答案是不该：
 * 关掉开发者选项之后，如果轮询线程还在跑，车主既看不见它、也停不掉它 —— 而它读的是一条任何人都能
 * 往里写的公开话题。所以 {@code HIDDEN} 排第一，顺序由这些断言钉住。</p>
 *
 * The three gates on ntfy commands: developer options, paid plan, switch. One pure function, all
 * eight combinations here. What is pinned is the <b>order</b>, not just the answers: "paid and on"
 * must not survive developer options being closed, because a poller still running after that is
 * invisible, unstoppable, and reading a topic anyone can write to.
 */
public class NtfyCommandsTest {

    @Test
    public void allThreeHaveToHold() {
        assertEquals(NtfyCommands.State.ON, NtfyCommands.of(true, true, true));
        assertTrue(NtfyCommands.on(true, true, true));
    }

    @Test
    public void developerOptionsClosedBeatsPaidAndOn() {
        assertEquals("关掉开发者选项不能留下在跑的轮询", NtfyCommands.State.HIDDEN,
                NtfyCommands.of(false, true, true));
        assertFalse(NtfyCommands.on(false, true, true));
        // 其余三种关着的组合同样是 HIDDEN：关门就是关门，不看别的两项。
        assertEquals(NtfyCommands.State.HIDDEN, NtfyCommands.of(false, true, false));
        assertEquals(NtfyCommands.State.HIDDEN, NtfyCommands.of(false, false, true));
        assertEquals(NtfyCommands.State.HIDDEN, NtfyCommands.of(false, false, false));
    }

    @Test
    public void theFreePlanNeverGetsCommandsEvenWhenSwitchedOn() {
        assertEquals("免费话题谁都能写，命令不能给", NtfyCommands.State.NEEDS_PAID_PLAN,
                NtfyCommands.of(true, false, true));
        assertFalse(NtfyCommands.on(true, false, true));
        assertEquals(NtfyCommands.State.NEEDS_PAID_PLAN, NtfyCommands.of(true, false, false));
    }

    @Test
    public void theOwnerSwitchDecidesLastAndOnlyLast() {
        assertEquals(NtfyCommands.State.OFF_BY_OWNER, NtfyCommands.of(true, true, false));
        assertFalse(NtfyCommands.on(true, true, false));
    }

    @Test
    public void theRowIsVisibleOnlyWithDeveloperOptionsOpen() {
        assertTrue(NtfyCommands.visible(true));
        assertFalse(NtfyCommands.visible(false));
        // 免费档也要露出来：那一行带着「为什么点不动」。
        assertEquals(NtfyCommands.State.NEEDS_PAID_PLAN, NtfyCommands.of(true, false, false));
    }

    @Test
    public void everyStateHasALogLabel() {
        for (NtfyCommands.State state : NtfyCommands.State.values()) {
            assertFalse(state.name(), state.label().isEmpty());
            // 黑匣子会被导出，标签只写 ASCII。
            for (int i = 0; i < state.label().length(); i++) {
                assertTrue(state.name(), state.label().charAt(i) < 128);
            }
        }
    }
}
