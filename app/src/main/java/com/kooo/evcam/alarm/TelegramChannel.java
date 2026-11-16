/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.alarm;

import com.kooo.evcam.alarm.ntfy.NtfyGate;
import com.kooo.evcam.alarm.telegram.TelegramRequest;
import com.kooo.evcam.alarm.telegram.TelegramSender;

/**
 * Telegram 这条出口：把 {@link TelegramSender} 包一层，行为一点没变。
 *
 * <p>包一层是为了让 {@link SentryAlerts} 只认 {@link AlertChannel}，不必在每个发送点判断
 * 「现在是哪条路」。这一层不许长出自己的规矩 —— 它只转发，然后翻译失败原因。</p>
 *
 * The Telegram exit: a wrapper over {@link TelegramSender} with unchanged behaviour. It exists so
 * {@link SentryAlerts} only knows {@link AlertChannel} and does not branch at every send site. This
 * class grows no rules of its own — it forwards and translates.
 */
public final class TelegramChannel implements AlertChannel {

    private final String token;
    private final String chatId;

    public TelegramChannel(String token, String chatId) {
        this.token = token == null ? "" : token.trim();
        this.chatId = chatId == null ? "" : chatId.trim();
    }

    @Override
    public String name() {
        return NtfyGate.TELEGRAM;
    }

    @Override
    public boolean ready() {
        return TelegramRequest.looksLikeToken(token) && TelegramRequest.looksLikeChatId(chatId);
    }

    @Override
    public Kind blockedReason() {
        return Kind.NOT_CONFIGURED;
    }

    @Override
    public boolean supportsClips() {
        return true;
    }

    @Override
    public Result sendPhoto(byte[] jpeg, String caption) {
        if (!ready()) {
            return Result.fail(Kind.NOT_CONFIGURED, 0, "telegram not configured");
        }
        return translate(TelegramSender.sendPhoto(token, chatId, jpeg, caption,
                TelegramRequest.keyboardMarkup()));
    }

    @Override
    public Result sendText(String text) {
        if (!ready()) {
            return Result.fail(Kind.NOT_CONFIGURED, 0, "telegram not configured");
        }
        return translate(TelegramSender.sendMessage(token, chatId, text,
                TelegramRequest.keyboardMarkup()));
    }

    /** Telegram 的失败只有「不认」和「别的」两种分得出来，照实翻。 */
    private static Result translate(TelegramSender.Result result) {
        if (result.ok) {
            return Result.ok();
        }
        return Result.fail(result.rejected ? Kind.REJECTED : Kind.NETWORK, result.httpCode,
                result.display());
    }
}
