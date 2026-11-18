/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * Portions Copyright (C) 2026 Fab (Fabbbrrr) - https://github.com/Fabbbrrr
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.alarm.telegram;

import com.kooo.evcam.alarm.guard.AlertMoment;
import com.kooo.evcam.alarm.media.MotionSensitivity;

/**
 * 车主发来的一句话是什么意思 —— 纯解析，不碰网络、不碰车。
 *
 * <h3>认什么</h3>
 *
 * <ul>
 *   <li>{@code pic} / {@code photo} / {@code picture} / {@code snap} / {@code snapshot}：现在拍一张；</li>
 *   <li>后面可以跟一路：{@code front} / {@code rear} / {@code left} / {@code right}；</li>
 *   <li>{@code status} / {@code state}：只回一句话，不拍；</li>
 *   <li>{@code sens} / {@code sensitivity}，可空，或跟 {@code up} / {@code down} / 一个 1–10 的数字：
 *       画面移动的灵敏度，见 {@link SensitivityAction}；</li>
 *   <li>{@code alerts} / {@code alarm}，可空，或跟 {@code on} / {@code off}（{@code resume} /
 *       {@code pause} 也算）：暂停警报、恢复警报，见 {@link AlertsState}；</li>
 *   <li>{@code guard} 及后面一个词（{@code on} / {@code off} / {@code home} / {@code home clear} /
 *       {@code auto off|ask|then|on} / {@code then}）：忘了开哨兵那一块的事，见 {@link GuardAction}。</li>
 * </ul>
 *
 * <p>命令前面的 {@code /}、命令后面的 {@code @BotName}（群里会带）、大小写、前后空白都不计较。
 * 别的词一律是 {@link Verb#UNKNOWN}，不猜。</p>
 *
 * <h3>谁能发</h3>
 *
 * <p>{@link #fromOwner} 只认已经在「停车警报」里配好的那个会话号。别人发什么都不回 ——
 * 回了，这辆车就成了一个谁都能要照片的相机。</p>
 *
 * What one message from the owner means; pure parsing, no network, no car.
 *
 * <h3>What it recognizes</h3>
 *
 * <ul>
 *   <li>{@code pic} / {@code photo} / {@code picture} / {@code snap} / {@code snapshot}: take one picture now;</li>
 *   <li>optionally followed by one lane: {@code front} / {@code rear} / {@code left} / {@code right};</li>
 *   <li>{@code status} / {@code state}: reply with one line, no picture;</li>
 *   <li>{@code sens} / {@code sensitivity}, alone or followed by {@code up} / {@code down} / a 1–10
 *       number: motion-detection sensitivity, see {@link SensitivityAction};</li>
 *   <li>{@code alerts} / {@code alarm}, alone or followed by {@code on} / {@code off} (also
 *       {@code resume} / {@code pause}): pause and resume alerts, see {@link AlertsState};</li>
 *   <li>{@code guard} plus one word ({@code on} / {@code off} / {@code home} / {@code home clear} /
 *       {@code auto off|ask|then|on} / {@code then}): the forgotten-sentry block, see
 *       {@link GuardAction}.</li>
 * </ul>
 *
 * <p>A leading {@code /}, a trailing {@code @BotName} (present in groups), letter case, and
 * surrounding whitespace are all ignored. Any other word is {@link Verb#UNKNOWN}, never guessed.</p>
 *
 * <h3>Who may send</h3>
 *
 * <p>{@link #fromOwner} only accepts the chat id configured in "Parking Alarm". Anyone else gets
 * no reply; replying would turn the car into a camera anyone could ask for photos.</p>
 */
public final class TelegramCommand {

    /**
     * 一句话想干什么。
     *
     * What one message wants to do.
     */
    public enum Verb {
        /**
         * 现在拍一张。
         *
         * Take one picture now.
         */
        PICTURE,
        /**
         * 报一句状态，不拍。
         *
         * Report the status, no picture.
         */
        STATUS,
        /**
         * 忘了开哨兵那一块说的话（{@code /guard …}）。
         *
         * The forgotten-sentry block's verb ({@code /guard …}).
         */
        GUARD,
        /**
         * 画面移动的灵敏度（{@code /sens …}）：问一句、往上挪、往下挪，或者直接给一个数。
         *
         * Motion-detection sensitivity ({@code /sens …}): report it, move it a step, or set a number.
         */
        SENSITIVITY,
        /**
         * 暂停或者恢复警报（{@code /alerts …}）。
         *
         * <p>暂停<b>不动</b>总开关：命令就是从聊天里来的，把总开关关掉等于把回程路一起关掉。</p>
         *
         * Pause or resume alerts ({@code /alerts …}).
         *
         * <p>A pause leaves the master switch alone: commands arrive through the chat, and turning the
         * master switch off would close the way back.</p>
         */
        ALERTS,
        /**
         * 不是命令，或者不认识。
         *
         * Not a command, or not recognized.
         */
        UNKNOWN
    }

    /**
     * {@code /guard} 后面跟的那一个词。
     *
     * <p>{@link #REPORT} 是光写一个 {@code guard}；其他的每一种都对应一个整齐的动作，
     * 认不出来的词不会撑到某个最近的意思上，而是整句 {@link Verb#UNKNOWN}。</p>
     *
     * The one word after {@code /guard}.
     *
     * <p>{@link #REPORT} is a bare {@code guard}; every other value names one action. An unrecognized
     * word does not snap to the nearest meaning; the whole message becomes {@link Verb#UNKNOWN}.</p>
     */
    public enum GuardAction {
        /** 没说做什么：报一句现在。 No word: one line of state. */
        REPORT,
        /** {@code guard on}：现在就开。 Turn it on now. */
        ON,
        /** {@code guard off}：关掉。 Switch it off. */
        OFF,
        /** {@code guard home}：把车现在的位置记成家。 Remember where the car is now as home. */
        HOME_SET,
        /** {@code guard home clear}：忘掉那个家。 Forget that home point. */
        HOME_CLEAR,
        /** {@code guard auto off}：不再自己开。 Stop acting by itself. */
        AUTO_OFF,
        /** {@code guard auto ask}：只问。 Only ask. */
        AUTO_ASK,
        /** {@code guard auto then}：问了不开就自己开。 Ask, then switch on. */
        AUTO_ASK_THEN_ON,
        /** {@code guard auto on}：不问，自己开。 Switch on silently. */
        AUTO_ON
    }

    /**
     * {@code /sens} 后面跟的那一个词。
     *
     * <p>光写 {@code sens} 是 {@link #REPORT}；{@link #SET} 要带一个数。认不出来的词、或者 1–10 之外
     * 的数字，整句 {@link Verb#UNKNOWN} —— 灵敏度调到 11 和调到 0 都不是「最近的合法值」的意思。</p>
     *
     * The one word after {@code /sens}. A bare {@code sens} is {@link #REPORT}; {@link #SET} carries a
     * number. An unrecognized word, or a number outside 1–10, makes the whole message
     * {@link Verb#UNKNOWN}: "11" and "0" are not shorthand for the nearest legal value.
     */
    public enum SensitivityAction {
        /** 光写一个 {@code sens}：报一句现在几档。 Bare {@code sens}: say which level it is on. */
        REPORT,
        /** {@code sens up}：灵敏一点。 One step more sensitive. */
        UP,
        /** {@code sens down}：钝一点。 One step less sensitive. */
        DOWN,
        /** {@code sens 7}：直接要第 7 档。 Ask for level 7 by number. */
        SET
    }

    /**
     * {@code /alerts} 后面跟的那一个词。
     *
     * <p>光写 {@code alerts} 是 {@link #REPORT}。认不出来的词整句 {@link Verb#UNKNOWN} ——
     * 「alerts maybe」既不是开也不是关，猜一个方向比不回更糟。</p>
     *
     * The one word after {@code /alerts}. A bare {@code alerts} is {@link #REPORT}; an unrecognized word
     * makes the whole message {@link Verb#UNKNOWN}, because guessing a direction is worse than silence.
     */
    public enum AlertsState {
        /** 光写一个 {@code alerts}：报一句现在是开是停。 Bare {@code alerts}: say whether they run. */
        REPORT,
        /** {@code alerts on}：恢复。 Resume. */
        ON,
        /** {@code alerts off}：暂停。 Pause. */
        OFF
    }

    /**
     * 不指定某一路。
     *
     * No specific lane.
     */
    public static final int ANY_LANE = -1;

    /**
     * 环视四路的名字，顺序与 {@code LaneCycle} 一致：前、后、左、右。
     *
     * Names of the four surround-view lanes, in {@code LaneCycle} order: front, rear, left, right.
     */
    private static final String[] LANE_WORDS = {"front", "rear", "left", "right"};

    public final Verb verb;
    /**
     * {@link #ANY_LANE} 表示按设置发（一张四宫格或每一路各一张）。
     *
     * {@link #ANY_LANE} means send per the settings (one four-grid, or one per lane).
     */
    public final int lane;
    /**
     * {@code pic} 后面那个像时间的词（{@code 18:22} / {@code 6:22pm}）；空串表示没有。
     *
     * <p>这里是纯粹的字符串，不是时刻：要拿「现在」才能算出是哪一天的几点，
     * 那件事在 {@code alarm.AlertMoment} 里做，调用方给时间。</p>
     *
     * The word after {@code pic} that looks like a time, or an empty string. Parsing it into an instant
     * needs "now", so that happens in {@code alert.AlertMoment} with the caller's clock.
     */
    public final String timeWord;
    /**
     * 只有 {@link Verb#GUARD} 有值；别的命令全是 {@code null}。
     *
     * Set only for {@link Verb#GUARD}; {@code null} for every other verb.
     */
    public final GuardAction guard;
    /**
     * 只有 {@link Verb#SENSITIVITY} 有值；别的命令全是 {@code null}。
     *
     * Set only for {@link Verb#SENSITIVITY}; {@code null} for every other verb.
     */
    public final SensitivityAction sensitivity;
    /**
     * 只有 {@link SensitivityAction#SET} 有值：要哪一档，已经确认在 1–10 之内。
     *
     * Set only for {@link SensitivityAction#SET}: which level, already checked to be inside 1–10.
     */
    public final int sensitivityLevel;
    /**
     * 只有 {@link Verb#ALERTS} 有值；别的命令全是 {@code null}。
     *
     * Set only for {@link Verb#ALERTS}; {@code null} for every other verb.
     */
    public final AlertsState alerts;

    private TelegramCommand(Verb verb, int lane) {
        this(verb, lane, null, "");
    }

    private TelegramCommand(Verb verb, int lane, GuardAction guard) {
        this(verb, lane, guard, "");
    }

    private TelegramCommand(Verb verb, int lane, GuardAction guard, String timeWord) {
        this(verb, lane, guard, timeWord, null, 0);
    }

    private TelegramCommand(Verb verb, int lane, GuardAction guard, String timeWord,
                            SensitivityAction sensitivity, int sensitivityLevel) {
        this(verb, lane, guard, timeWord, sensitivity, sensitivityLevel, null);
    }

    private TelegramCommand(Verb verb, int lane, GuardAction guard, String timeWord,
                            SensitivityAction sensitivity, int sensitivityLevel, AlertsState alerts) {
        this.verb = verb;
        this.lane = lane;
        this.guard = guard;
        this.timeWord = timeWord == null ? "" : timeWord;
        this.sensitivity = sensitivity;
        this.sensitivityLevel = sensitivityLevel;
        this.alerts = alerts;
    }

    /**
     * 这句话的主人是不是配好的那个人。
     *
     * Whether this message came from the configured owner.
     */
    public static boolean fromOwner(String chatId, String configuredChatId) {
        if (chatId == null || configuredChatId == null) {
            return false;
        }
        return chatId.trim().equals(configuredChatId.trim());
    }

    /**
     * 解析一句话。
     *
     * @param text 消息原文；空、纯空白、纯表情都得到 {@link Verb#UNKNOWN}
     *
     * Parse one message.
     *
     * @param text the raw message; empty, all-whitespace, or emoji-only input
     *             yields {@link Verb#UNKNOWN}
     */
    public static TelegramCommand parseText(String text) {
        if (text == null) {
            return new TelegramCommand(Verb.UNKNOWN, ANY_LANE);
        }
        String body = text.trim();
        if (body.startsWith("/")) {
            body = body.substring(1);
        }
        // 群里发命令时 Telegram 会把命令写成 /pic@ZeekrSentry_bot
        // In groups Telegram writes commands as /pic@ZeekrSentry_bot
        int at = body.indexOf('@');
        if (at >= 0) {
            int space = body.indexOf(' ');
            if (space < 0 || at < space) {
                body = space < 0 ? body.substring(0, at)
                        : body.substring(0, at) + body.substring(space);
            }
        }
        body = body.trim();
        if (body.isEmpty()) {
            return new TelegramCommand(Verb.UNKNOWN, ANY_LANE);
        }
        String[] words = body.split("\\s+");
        String verb = words[0].toLowerCase(java.util.Locale.US);
        if ("guard".equals(verb)) {
            return parseGuard(words);
        }
        if ("status".equals(verb) || "state".equals(verb) || "s".equals(verb)) {
            return new TelegramCommand(Verb.STATUS, ANY_LANE);
        }
        if ("sens".equals(verb) || "sensitivity".equals(verb)) {
            return parseSensitivity(words);
        }
        if ("alerts".equals(verb) || "alarm".equals(verb)) {
            return parseAlerts(words);
        }
        if (!"pic".equals(verb) && !"photo".equals(verb) && !"picture".equals(verb)
                && !"snap".equals(verb) && !"snapshot".equals(verb)) {
            return new TelegramCommand(Verb.UNKNOWN, ANY_LANE);
        }
        int lane = ANY_LANE;
        String timeWord = "";
        for (int i = 1; i < words.length; i++) {
            String word = words[i].toLowerCase(java.util.Locale.US);
            for (int index = 0; index < LANE_WORDS.length; index++) {
                if (LANE_WORDS[index].equals(word)) {
                    lane = index;
                    break;
                }
            }
            if (lane != ANY_LANE) {
                break;
            }
            // 像时间的那个词留着，由调用方拿「现在」去算 —— 这里没有时钟。
            // Keep a word that looks like a time; the caller resolves it against "now".
            if (timeWord.isEmpty() && looksLikeClock(word)) {
                timeWord = word;
            }
        }
        return new TelegramCommand(Verb.PICTURE, lane, null, timeWord);
    }

    /**
     * {@code alerts} 及后面的词：不写、{@code on} / {@code resume}、{@code off} / {@code pause}。
     *
     * <p>只收一个词。多写一个词（{@code alerts off now}）整句不认 —— 和 {@code guard} 同一条规矩。</p>
     *
     * {@code alerts} and the words after it: nothing, {@code on} / {@code resume}, or {@code off} /
     * {@code pause}. Exactly one word is accepted; {@code alerts off now} is {@link Verb#UNKNOWN}, the
     * same rule {@code guard} follows.
     */
    private static TelegramCommand parseAlerts(String[] words) {
        if (words.length == 1) {
            return new TelegramCommand(Verb.ALERTS, ANY_LANE, null, "", null, 0, AlertsState.REPORT);
        }
        if (words.length != 2) {
            return new TelegramCommand(Verb.UNKNOWN, ANY_LANE);
        }
        String word = words[1].toLowerCase(java.util.Locale.US);
        if ("on".equals(word) || "resume".equals(word) || "start".equals(word)) {
            return new TelegramCommand(Verb.ALERTS, ANY_LANE, null, "", null, 0, AlertsState.ON);
        }
        if ("off".equals(word) || "pause".equals(word) || "stop".equals(word)) {
            return new TelegramCommand(Verb.ALERTS, ANY_LANE, null, "", null, 0, AlertsState.OFF);
        }
        return new TelegramCommand(Verb.UNKNOWN, ANY_LANE);
    }

    /**
     * {@code sens} 及后面的词。
     *
     * <p>{@code up} / {@code higher} / {@code more} / {@code +} 都是往上，{@code down} / {@code lower} /
     * {@code less} / {@code -} 都是往下 —— 同一件事的说法不止一种，收下就是了。数字要在 1–10 之内，
     * 之外的不猜。</p>
     *
     * {@code sens} and the words after it. {@code up} / {@code higher} / {@code more} / {@code +} all
     * mean one step more sensitive, {@code down} / {@code lower} / {@code less} / {@code -} one step
     * less; the same act said several ways is worth accepting. A number must be inside 1–10, and
     * anything outside it is not guessed at.
     */
    private static TelegramCommand parseSensitivity(String[] words) {
        if (words.length == 1) {
            return new TelegramCommand(Verb.SENSITIVITY, ANY_LANE, null, "",
                    SensitivityAction.REPORT, 0);
        }
        int value = -1;
        String word = words[1].toLowerCase(java.util.Locale.US);
        if (words.length == 3 && "set".equals(word)) {
            value = numberOf(words[2]);
        } else if (words.length == 2) {
            if ("up".equals(word) || "higher".equals(word) || "more".equals(word)
                    || "+".equals(word) || "+1".equals(word)) {
                return new TelegramCommand(Verb.SENSITIVITY, ANY_LANE, null, "",
                        SensitivityAction.UP, 0);
            }
            if ("down".equals(word) || "lower".equals(word) || "less".equals(word)
                    || "-".equals(word) || "-1".equals(word)) {
                return new TelegramCommand(Verb.SENSITIVITY, ANY_LANE, null, "",
                        SensitivityAction.DOWN, 0);
            }
            value = numberOf(word);
        }
        if (value < MotionSensitivity.MIN || value > MotionSensitivity.MAX) {
            return new TelegramCommand(Verb.UNKNOWN, ANY_LANE);
        }
        return new TelegramCommand(Verb.SENSITIVITY, ANY_LANE, null, "",
                SensitivityAction.SET, value);
    }

    /** 整串都是数字才算；返回 -1 表示不是数字。「全是数字」：没有别的字符混进来。 */
    /** Only an all-digit word counts; -1 means it is not a number. */
    private static int numberOf(String word) {
        if (word == null || word.isEmpty() || word.length() > 2) {
            return -1;
        }
        for (int i = 0; i < word.length(); i++) {
            if (word.charAt(i) < '0' || word.charAt(i) > '9') {
                return -1;
            }
        }
        return Integer.parseInt(word);
    }

    /**
     * {@code guard} 及后面的词。认不出来就整句不认。
     *
     * <p>{@code guard then} 是 {@code guard auto then} 的短写法：同一件事说两遍，不如两个都收。</p>
     *
     * {@code guard} and the words after it. An unrecognized word makes the whole message unknown.
     *
     * <p>{@code guard then} is shorthand for {@code guard auto then}: the same act said twice is better
     * accepted both ways.</p>
     */
    private static TelegramCommand parseGuard(String[] words) {
        if (words.length == 1) {
            return new TelegramCommand(Verb.GUARD, ANY_LANE, GuardAction.REPORT);
        }
        String first = words[1].toLowerCase(java.util.Locale.US);
        if (words.length == 2) {
            if ("on".equals(first)) {
                return new TelegramCommand(Verb.GUARD, ANY_LANE, GuardAction.ON);
            }
            if ("off".equals(first)) {
                return new TelegramCommand(Verb.GUARD, ANY_LANE, GuardAction.OFF);
            }
            if ("home".equals(first)) {
                return new TelegramCommand(Verb.GUARD, ANY_LANE, GuardAction.HOME_SET);
            }
            if ("then".equals(first)) {
                return new TelegramCommand(Verb.GUARD, ANY_LANE, GuardAction.AUTO_ASK_THEN_ON);
            }
            return new TelegramCommand(Verb.UNKNOWN, ANY_LANE);
        }
        if (words.length != 3) {
            return new TelegramCommand(Verb.UNKNOWN, ANY_LANE);
        }
        String second = words[2].toLowerCase(java.util.Locale.US);
        if ("home".equals(first) && "clear".equals(second)) {
            return new TelegramCommand(Verb.GUARD, ANY_LANE, GuardAction.HOME_CLEAR);
        }
        if (!"auto".equals(first)) {
            return new TelegramCommand(Verb.UNKNOWN, ANY_LANE);
        }
        if ("off".equals(second)) {
            return new TelegramCommand(Verb.GUARD, ANY_LANE, GuardAction.AUTO_OFF);
        }
        if ("ask".equals(second)) {
            return new TelegramCommand(Verb.GUARD, ANY_LANE, GuardAction.AUTO_ASK);
        }
        if ("then".equals(second)) {
            return new TelegramCommand(Verb.GUARD, ANY_LANE, GuardAction.AUTO_ASK_THEN_ON);
        }
        if ("on".equals(second)) {
            return new TelegramCommand(Verb.GUARD, ANY_LANE, GuardAction.AUTO_ON);
        }
        return new TelegramCommand(Verb.UNKNOWN, ANY_LANE);
    }

    /** 像不像一个时刻：带冒号，或者以 am / pm 结尾。Whether the word looks like a time of day. */
    static boolean looksLikeClock(String word) {
        if (word == null || word.isEmpty()) {
            return false;
        }
        String body = word.trim();
        return body.indexOf(':') > 0
                || body.endsWith("am") || body.endsWith("pm")
                || body.endsWith("a.m.") || body.endsWith("p.m.");
    }

    @Override
    public String toString() {
        return verb + (guard == null ? "" : ":" + guard)
                + (alerts == null ? "" : ":" + alerts)
                + (sensitivity == null ? "" : ":" + sensitivity
                        + (sensitivity == SensitivityAction.SET ? "=" + sensitivityLevel : ""))
                + (lane == ANY_LANE ? "" : "#" + lane);
    }
}
