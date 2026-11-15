/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * Portions Copyright (C) 2026 Fab (Fabbbrrr) - https://github.com/Fabbbrrr
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.alarm;

/**
 * 一条更新该不该执行 —— 纯判断，便于单测（{@code PollerGateTest}）。
 *
 * <h3>为什么不是「会话号对上就行」</h3>
 *
 * <p>会话号对上只说明消息来自<b>那个会话</b>。会话可能是个群：群里任何一个人按一下
 * 机器人那排「pic」按钮，车周围的照片就会发到群里。所以还要两条：</p>
 *
 * <ul>
 *   <li><b>必须是私聊</b>（{@code chat.type == "private"}）；</li>
 *   <li><b>发消息的人就是会话本人</b>：私聊里 {@code chat.id} 与 {@code from.id} 相等，
 *       两者都要等于设置里那个号。</li>
 * </ul>
 *
 * <p>另外，睡了一夜的车醒过来时会把停机期间的命令一起收到。超过 {@link #NOTE_AGE_MS} 的命令照执行，
 * 但照片配文里写上“要求于几点”；超过 {@link #MAX_AGE_MS} 的只确认、不执行。</p>
 *
 * Whether an update should run; pure judgment, easy to unit test ({@code PollerGateTest}).
 *
 * <h3>Why not "the session id matches is enough"</h3>
 *
 * <p>A matching session id only means the message came from <b>that session</b>. The session can be
 * a group: anyone in the group presses the "pic" button on the bot's button row and photos around
 * the car get sent to the group. So two more rules:</p>
 *
 * <ul>
 *   <li><b>Must be a private chat</b> ({@code chat.type == "private"});</li>
 *   <li><b>The sender is the session owner himself</b>: in a private chat {@code chat.id} equals
 *       {@code from.id}, and both must equal the number in settings.</li>
 * </ul>
 *
 * <p>Also, when a car that slept overnight wakes up it receives the commands queued during shutdown
 * all at once. A command older than {@link #NOTE_AGE_MS} still runs, but the photo caption says
 * "requested at such-and-such time"; one older than {@link #MAX_AGE_MS} is only acknowledged, not
 * executed.</p>
 */
public final class PollerGate {

    /**
     * 超过这么久的命令就不算了。
     *
     * <p>为什么不是几分钟：车停在外面、车机睡着时消息只能堆着，等车醒过来
     * 才能取；那时拍一张现在的照片仍然有用，只要配文里写清楚是几点要的。
     * 超过 6 小时就太旧了（车已经换了地方），只确认、不执行。</p>
     *
     * A command older than this is not run.
     *
     * <p>Why not a few minutes: when the car is parked outside and the head unit is asleep, messages
     * can only pile up until the car wakes and can fetch them; taking a photo of the current moment
     * then is still useful, as long as the caption says clearly what time it was requested. Over 6
     * hours is too old (the car has already moved), so only acknowledge, don't execute.</p>
     */
    public static final long MAX_AGE_MS = 6 * 60 * 60_000L;

    /**
     * 超过这么久的命令，照片配文里要写上“要求于几点”。
     *
     * For a command older than this, the photo caption must say "requested at what time".
     */
    public static final long NOTE_AGE_MS = 2 * 60_000L;

    public enum Verdict {
        /**
         * 认，执行。
         *
         * Accepted, execute.
         */
        ACCEPT,
        /**
         * 不是私聊（群、频道…）。
         *
         * Not a private chat (group, channel, ...).
         */
        NOT_PRIVATE,
        /**
         * 私聊，但不是配好的那个会话 / 那个人。
         *
         * Private chat, but not the configured session / person.
         */
        NOT_OWNER,
        /**
         * 太旧了：车刚醒，这条命令已经过期。
         *
         * Too old: the car just woke and this command has already expired.
         */
        STALE,
        /**
         * 会话对了，但这句话不是命令。
         *
         * The session is right, but this message is not a command.
         */
        NOT_A_COMMAND
    }

    private PollerGate() {
    }

    /**
     * @param chatType          Telegram 的 {@code chat.type}
     * @param chatId            消息所在会话
     * @param fromUserId        发消息的人
     * @param configuredChatId  设置里配的会话号
     * @param ownerUserId       车主自己的用户号；空串表示还没记下来
     * @param messageDateMs     消息时间（{@code date} × 1000）；0 表示不知道
     * @param nowMs             现在
     *
     * @param chatType          Telegram's {@code chat.type}
     * @param chatId            the session the message is in
     * @param fromUserId        the person who sent the message
     * @param configuredChatId  the session number configured in settings
     * @param ownerUserId       the owner's own user number; empty string means not recorded yet
     * @param messageDateMs     message time ({@code date} × 1000); 0 means unknown
     * @param nowMs             now
     */
    public static Verdict verdict(String chatType, String chatId, String fromUserId,
                                  String configuredChatId, String ownerUserId,
                                  long messageDateMs, long nowMs,
                                  TelegramCommand command) {
        Verdict identity = identityVerdict(chatType, chatId, fromUserId, configuredChatId, ownerUserId);
        if (identity != null) {
            return identity;
        }
        Verdict freshness = freshnessVerdict(messageDateMs, nowMs);
        if (freshness != null) {
            return freshness;
        }
        if (command == null || command.verb == TelegramCommand.Verb.UNKNOWN) {
            return Verdict.NOT_A_COMMAND;
        }
        return Verdict.ACCEPT;
    }

    /**
     * 一颗按钮按下了（{@code callback_query}）：同样两条身份、同样的 {@link #MAX_AGE_MS}。
     *
     * <p>和 {@link #verdict} 只差一点：按钮上带的是 {@code data}，没有句子可解析，所以永远不会是
     * {@link Verdict#NOT_A_COMMAND}。</p>
     *
     * @param chatType         {@code message.chat.type}（按钮挂在哪条消息上）
     * @param chatId           {@code message.chat.id}
     * @param fromUserId       {@code callback_query.from.id}，按按钮的那个人
     * @param configuredChatId 设置里配的会话号
     * @param ownerUserId      车主自己的用户号；空串表示还没记下来
     * @param messageDateMs    那条消息的时间（{@code message.date} × 1000）；0 表示不知道
     * @param nowMs            现在
     *
     * A button was pressed ({@code callback_query}): the same two identities and the same
     * {@link #MAX_AGE_MS}.
     *
     * <p>Only one difference from {@link #verdict}: a button carries {@code data} and no sentence to
     * parse, so it is never {@link Verdict#NOT_A_COMMAND}.</p>
     *
     * @param chatType         {@code message.chat.type} (the message the button hangs on)
     * @param chatId           {@code message.chat.id}
     * @param fromUserId       {@code callback_query.from.id}, the person who pressed
     * @param configuredChatId the session number configured in settings
     * @param ownerUserId      the owner's own user number; empty string means not recorded yet
     * @param messageDateMs    that message's time ({@code message.date} × 1000); 0 means unknown
     * @param nowMs            now
     */
    public static Verdict verdictForCallback(String chatType, String chatId, String fromUserId,
                                             String configuredChatId, String ownerUserId,
                                             long messageDateMs, long nowMs) {
        Verdict identity = identityVerdict(chatType, chatId, fromUserId, configuredChatId, ownerUserId);
        if (identity != null) {
            return identity;
        }
        Verdict freshness = freshnessVerdict(messageDateMs, nowMs);
        if (freshness != null) {
            return freshness;
        }
        return Verdict.ACCEPT;
    }

    /**
     * 两条身份：先看会话，再看人。其于 {@code null} 表示两条都过了。
     *
     * <p>抽出来只为了让它只写一遍：文字命令和按钮走的是同一套判断，两处各写一遍就会有一天只改了一边。</p>
     *
     * Two identities: the session first, then the person. {@code null} means both passed.
     *
     * <p>Pulled out so it is written once: text commands and buttons pass the same judgment, and two
     * copies drift the day one of them is edited alone.</p>
     */
    private static Verdict identityVerdict(String chatType, String chatId, String fromUserId,
                                          String configuredChatId, String ownerUserId) {
        if (!TelegramCommand.fromOwner(chatId, configuredChatId)) {
            return Verdict.NOT_OWNER;
        }
        boolean privateChat = "private".equals(chatType);
        if (ownerUserId != null && !ownerUserId.isEmpty()) {
            // 记下了车主的用户号：私聊、群聊都行，但只有他本人算数
            // The owner's user number is recorded: private or group chat both work, but only he himself counts
            if (!TelegramCommand.fromOwner(fromUserId, ownerUserId)) {
                return Verdict.NOT_OWNER;
            }
        } else if (!privateChat || !TelegramCommand.fromOwner(fromUserId, configuredChatId)) {
            // 还不知道车主是谁时，只认私聊里的本人（那时会话号 == 用户号）
            // When the owner is still unknown, only accept the person himself in a private chat (then session id == user id)
            return Verdict.NOT_OWNER;
        }
        return null;
    }

    /**
     * 过期判断：{@code null} 表示还新鲜（时间不知道时不按过期处理）。
     *
     * The staleness check: {@code null} means still fresh (an unknown time is not treated as stale).
     */
    private static Verdict freshnessVerdict(long messageDateMs, long nowMs) {
        if (messageDateMs > 0 && nowMs - messageDateMs > MAX_AGE_MS) {
            return Verdict.STALE;
        }
        return null;
    }
}
