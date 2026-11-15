/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * Portions Copyright (C) 2026 Fab (Fabbbrrr) - https://github.com/Fabbbrrr
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.alarm;

/**
 * 车主发来的一句话是什么意思 —— 纯解析，不碰网络、不碰车。
 *
 * <h3>认什么</h3>
 *
 * <ul>
 *   <li>{@code pic} / {@code photo} / {@code picture} / {@code snap} / {@code snapshot}：现在拍一张；</li>
 *   <li>后面可以跟一路：{@code front} / {@code rear} / {@code left} / {@code right}；</li>
 *   <li>{@code status} / {@code state}：只回一句话，不拍。</li>
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
 *   <li>{@code status} / {@code state}: reply with one line, no picture.</li>
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
         * 不是命令，或者不认识。
         *
         * Not a command, or not recognized.
         */
        UNKNOWN
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

    private TelegramCommand(Verb verb, int lane) {
        this.verb = verb;
        this.lane = lane;
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
        if ("status".equals(verb) || "state".equals(verb) || "s".equals(verb)) {
            return new TelegramCommand(Verb.STATUS, ANY_LANE);
        }
        if (!"pic".equals(verb) && !"photo".equals(verb) && !"picture".equals(verb)
                && !"snap".equals(verb) && !"snapshot".equals(verb)) {
            return new TelegramCommand(Verb.UNKNOWN, ANY_LANE);
        }
        int lane = ANY_LANE;
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
        }
        return new TelegramCommand(Verb.PICTURE, lane);
    }

    @Override
    public String toString() {
        return verb + (lane == ANY_LANE ? "" : "#" + lane);
    }
}
