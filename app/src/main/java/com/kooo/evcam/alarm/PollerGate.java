/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
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
 */
public final class PollerGate {

    /**
     * 超过这么久的命令就不算了。
     *
     * <p>为什么不是几分钟：车停在外面、车机睡着时消息只能堆着，等车醒过来
     * 才能取；那时拍一张现在的照片仍然有用，只要配文里写清楚是几点要的。
     * 超过 6 小时就太旧了（车已经换了地方），只确认、不执行。</p>
     */
    public static final long MAX_AGE_MS = 6 * 60 * 60_000L;

    /** 超过这么久的命令，照片配文里要写上“要求于几点”。 */
    public static final long NOTE_AGE_MS = 2 * 60_000L;

    public enum Verdict {
        /** 认，执行。 */
        ACCEPT,
        /** 不是私聊（群、频道…）。 */
        NOT_PRIVATE,
        /** 私聊，但不是配好的那个会话 / 那个人。 */
        NOT_OWNER,
        /** 太旧了：车刚醒，这条命令已经过期。 */
        STALE,
        /** 会话对了，但这句话不是命令。 */
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
     */
    public static Verdict verdict(String chatType, String chatId, String fromUserId,
                                  String configuredChatId, String ownerUserId,
                                  long messageDateMs, long nowMs,
                                  TelegramCommand command) {
        if (!TelegramCommand.fromOwner(chatId, configuredChatId)) {
            return Verdict.NOT_OWNER;
        }
        boolean privateChat = "private".equals(chatType);
        if (ownerUserId != null && !ownerUserId.isEmpty()) {
            // 记下了车主的用户号：私聊、群聊都行，但只有他本人算数
            if (!TelegramCommand.fromOwner(fromUserId, ownerUserId)) {
                return Verdict.NOT_OWNER;
            }
        } else if (!privateChat || !TelegramCommand.fromOwner(fromUserId, configuredChatId)) {
            // 还不知道车主是谁时，只认私聊里的本人（那时会话号 == 用户号）
            return Verdict.NOT_OWNER;
        }
        if (privateChat && !TelegramCommand.fromOwner(fromUserId, configuredChatId)
                && (ownerUserId == null || ownerUserId.isEmpty())) {
            return Verdict.NOT_OWNER;
        }
        if (messageDateMs > 0 && nowMs - messageDateMs > MAX_AGE_MS) {
            return Verdict.STALE;
        }
        if (command == null || command.verb == TelegramCommand.Verb.UNKNOWN) {
            return Verdict.NOT_A_COMMAND;
        }
        return Verdict.ACCEPT;
    }
}
