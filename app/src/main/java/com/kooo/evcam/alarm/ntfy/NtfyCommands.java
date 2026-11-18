/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.alarm.ntfy;

/**
 * ntfy 能不能往车上发命令：三个条件都成立才算，判断一处，纯函数。
 *
 * <h3>为什么是「付费才给」</h3>
 *
 * <p>免费话题没有门：名字就是密码，而名字就印在手机的锁屏通知和 ntfy app 里。谁看见过一次通知，
 * 谁就能往这个话题里发东西 —— 那么一条命令通道就意味着<b>看见过通知的人都能让车拍张照、把哨兵模式打开</b>。
 * 这不是车主能替陌生人做的决定，所以免费档一律不给。</p>
 *
 * <p>付费档有访问令牌，发东西得带凭据。话题名仍然只有一把钥匙，但至少写不进去的人少了。</p>
 *
 * <h3>判断的顺序是有意义的</h3>
 *
 * <ol>
 *   <li>开发者选项关着 → {@link State#HIDDEN}。这一条盖过「付费且开着」：关掉开发者选项不能留下一个
 *       没人看得见、也停不掉的轮询线程在跑。</li>
 *   <li>免费档 → {@link State#NEEDS_PAID_PLAN}。开关摆在屏上、写清楚为什么点不动，比藏起来好。</li>
 *   <li>开关关着 → {@link State#OFF_BY_OWNER}：这就是上游接受的一向通行。</li>
 * </ol>
 *
 * <h3>Why paid only, and why the order matters</h3>
 *
 * <p>A free topic has no door: the name is the password, and the name is printed on the phone's lock
 * screen. Anyone who saw one notification can publish to it — so a command channel would mean
 * <b>anyone who saw a notification can ask the car for a picture or switch Sentry Mode on</b>. That is
 * not a decision an owner can make on a stranger's behalf, so the free tier never gets it.</p>
 *
 * <p>A paid account adds an access token: publishing needs a credential. The topic name is still the
 * only key, but fewer people hold it.</p>
 *
 * <p>The order is the rule. Developer options closed beats "paid and on", so closing them cannot leave
 * a poller running that nobody can see or stop. A free tier that is switched on still reads as
 * {@link State#NEEDS_PAID_PLAN}. Only then does the owner's own switch decide.</p>
 */
public final class NtfyCommands {

    /** 命令现在是什么状态。What the support for commands is doing right now. */
    public enum State {
        /** 开发者选项开着、付费档、开关也开着：发完警报后开五分钟的命令窗口。All three hold. */
        ON("on"),
        /** 开关关着：只发警报，车不回应任何东西。The owner's switch is off: one-way. */
        OFF_BY_OWNER("off-by-owner"),
        /** 免费档：公开话题，永远不给命令。Free topic: never. */
        NEEDS_PAID_PLAN("needs-paid-plan"),
        /** 开发者选项关着：这个功能对这台车不存在。Developer options closed: the feature is not here. */
        HIDDEN("hidden");

        private final String label;

        State(String label) {
            this.label = label;
        }

        /** 写进黑匣子和诊断报告的名字（ASCII，便于 grep）。The name written to the blackbox and diagnostics. */
        public String label() {
            return label;
        }
    }

    private NtfyCommands() {
    }

    /**
     * 现在是什么状态。
     *
     * @param developerUnlocked 开发者选项开着没有（{@code DeveloperMode.isUnlocked()}）
     * @param paid              ntfy 账号是不是付费档（{@code AppConfig.isAlertsNtfyPaid()}）
     * @param switchOn          车主那个开关存着的值（{@code alerts_ntfy_commands}）
     *
     * What the state is now. Developer options closed wins over everything, then the free tier, and
     * only then does the switch get a say.
     */
    public static State of(boolean developerUnlocked, boolean paid, boolean switchOn) {
        if (!developerUnlocked) {
            return State.HIDDEN;
        }
        if (!paid) {
            return State.NEEDS_PAID_PLAN;
        }
        return switchOn ? State.ON : State.OFF_BY_OWNER;
    }

    /** 现在真能收命令吗。Whether commands are actually read back right now. */
    public static boolean on(boolean developerUnlocked, boolean paid, boolean switchOn) {
        return of(developerUnlocked, paid, switchOn) == State.ON;
    }

    /**
     * 这一档、这个开关，值得不值得把那一行摆出来。
     *
     * <p>开发者选项关着就整行不出现（{@link State#HIDDEN} 是唯一「不出现」的状态）；免费档要出现，
     * 因为它带着那句「为什么点不动」。</p>
     *
     * Whether the row is worth showing at all. Only {@link State#HIDDEN} hides it; the free tier shows
     * it, because the row carries the sentence explaining why it does nothing.
     */
    public static boolean visible(boolean developerUnlocked) {
        return developerUnlocked;
    }
}
