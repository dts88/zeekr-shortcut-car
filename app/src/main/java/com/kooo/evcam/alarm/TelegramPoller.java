/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.alarm;

import com.kooo.evcam.AppConfig;
import com.kooo.evcam.AppLog;
import com.kooo.evcam.blackbox.BlackBox;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 去 Telegram 问「有人找我吗」的那条线程。
 *
 * <h3>为什么是车去问</h3>
 *
 * <p>车在运营商 NAT 后面，接不进来；但发得出去。{@code getUpdates} 是长轮询：
 * 车把请求挂在那儿最长 25 秒，车主的消息就塞在这条请求的回复里回来。</p>
 *
 * <h3>几条硬规矩</h3>
 *
 * <ul>
 *   <li><b>只认配好的那个会话</b>（{@link TelegramCommand#fromOwner}）。别人发什么都不回，
 *       只在黑匣子里记一笔 —— 回了就是一个谁都能要照片的相机。</li>
 *   <li><b>处理过就不再处理</b>：每条更新的 {@code update_id} 存进设置（运行状态那一档），
 *       重启后用 {@code offset} 从下一条开始，不会把昨天的命令再执行一遍。</li>
 *   <li><b>一个机器人只有一个轮询者</b>：Telegram 对同一个机器人并发轮询会回 409。
 *       所以车上只有这一条线程，别处（脚本、Home Assistant）不能再轮询同一个机器人。</li>
 *   <li><b>不拦着睡觉</b>：车机睡了，进程没了，这条线程自然停；不为它拿唤醒锁。</li>
 * </ul>
 */
public final class TelegramPoller {

    private static final String TAG = "TelegramPoller";

    /** 一次长轮询挂多久。Telegram 允许到 50，25 足够灵敏又不浪费。 */
    private static final int POLL_SECONDS = 25;

    /** 网络出问题时的重试间隔：从 5 秒翻倍，最多 60 秒。 */
    private static final long RETRY_MIN_MS = 5_000L;
    private static final long RETRY_MAX_MS = 60_000L;

    /** 409（别处也在轮询）时歇久一点，别把 409 刷成日志海。 */
    private static final long CONFLICT_PAUSE_MS = 60_000L;

    /** 超过这么久的命令不再执行：车睡了一夜醒来，不该突然拍一张几小时前要的照片。 */
    private static final long MAX_COMMAND_AGE_MS = 10 * 60_000L;

    /** 陌生会话的记事先去重：同一个会话一小时只记一行，免得刷爆黑匣子。 */
    private static final long FOREIGN_NOTE_MIN_MS = 60 * 60_000L;

    /** 收到一条命令之后交给谁。 */
    public interface Handler {
        /** @param lane 0 前、1 后、2 左、3 右；{@link TelegramCommand#ANY_LANE} 表示按设置 */
        void onPicture(int lane);

        void onStatus();
    }

    private final AppConfig config;
    private final Handler handler;
    private Thread thread;
    private volatile boolean running;
    private String lastForeignChat;
    private long lastForeignAt;

    public TelegramPoller(AppConfig config, Handler handler) {
        this.config = config;
        this.handler = handler;
    }

    public boolean isRunning() {
        return running;
    }

    /** 开始问。已经在问就什么都不做（旧线程还活着也一样：同一个机器人不能有两个轮询者）。 */
    public void start() {
        if (running || (thread != null && thread.isAlive())) {
            return;
        }
        running = true;
        thread = new Thread(this::loop, "telegram-poll");
        thread.start();
        BlackBox.note("停车警报：开始回应 Telegram 命令");
    }

    /**
     * 停下，并等那条线程真的收手。
     *
     * <p>只把 {@code running} 置假是不够的：线程还挂在一条 25 秒的长轮询上，
     * 这时候重新打开开关就会起第二条 —— 同一个机器人同时两个 {@code getUpdates}，
     * Telegram 回 409，两条都拿不到消息。所以这里打断它、等它退出，最多等 3 秒。</p>
     */
    public void stop() {
        running = false;
        Thread stopping = thread;
        thread = null;
        if (stopping != null) {
            stopping.interrupt();
            try {
                stopping.join(3_000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        BlackBox.note("停车警报：不再回应 Telegram 命令");
    }

    private void loop() {
        long retryMs = 0;
        while (running) {
            String token = config.getAlertsTelegramToken();
            String chatId = config.getAlertsTelegramChatId();
            String ownerId = config.getAlertsOwnerId();
            if (!TelegramRequest.looksLikeToken(token) || !TelegramRequest.looksLikeChatId(chatId)) {
                // 令牌填得不对时不能就这么退出：running 会一直是真的，改好令牌也不会再起来。
                // 什么都不问，隔一会儿再看一眼设置。
                sleep(RETRY_MAX_MS);
                continue;
            }
            try {
                JSONObject reply = TelegramSender.getUpdates(token,
                        config.getAlertsCommandOffset(), POLL_SECONDS);
                retryMs = 0;
                int before = (int) config.getAlertsCommandOffset();
                dispatch(reply, token, chatId, ownerId);
                int after = (int) config.getAlertsCommandOffset();
                if (after != before) {
                    BlackBox.note("停车警报：问到了新消息（处理到 " + after + "）");
                }
            } catch (TelegramSender.ChatListException e) {
                String message = e.getMessage() == null ? "" : e.getMessage();
                boolean conflict = message.contains("409") || message.contains("Conflict");
                long suggested = e.retryAfterMs;
                retryMs = suggested > 0 ? suggested
                        : conflict ? CONFLICT_PAUSE_MS
                        : (retryMs == 0 ? RETRY_MIN_MS : Math.min(RETRY_MAX_MS, retryMs * 2));
                BlackBox.note("停车警报：问 Telegram 失败，"
                        + (conflict ? "别处也在轮询同一个机器人" : message) + "；" + (retryMs / 1000) + " 秒后再问");
                sleep(retryMs);
            } catch (RuntimeException e) {
                retryMs = retryMs == 0 ? RETRY_MIN_MS : Math.min(RETRY_MAX_MS, retryMs * 2);
                AppLog.w(TAG, "poll failed: " + TelegramRequest.redact(String.valueOf(e)));
                sleep(retryMs);
            }
        }
    }

    /** 一条一批更新：先推 offset（不管认不认，处理过就算过），再按命令干活。 */
    private void dispatch(JSONObject reply, String token, String chatId, String ownerId) {
        if (!running) {
            return;
        }
        JSONArray updates = reply.optJSONArray("result");
        if (updates == null || updates.length() == 0) {
            return;
        }
        long offset = config.getAlertsCommandOffset();
        for (int i = 0; i < updates.length(); i++) {
            JSONObject update = updates.optJSONObject(i);
            if (update == null) {
                continue;
            }
            long updateId = update.optLong("update_id", 0L);
            // 只认普通消息：编辑过的、频道帖子、回调查询都不算
            JSONObject message = update.optJSONObject("message");
            if (message == null) {
                offset = Math.max(offset, updateId + 1);
                continue;
            }
            JSONObject chat = message.optJSONObject("chat");
            String fromChat = chat == null ? "" : chat.optString("id", "");
            String chatType = chat == null ? "" : chat.optString("type", "");
            JSONObject fromUser = message.optJSONObject("from");
            String fromUserId = fromUser == null ? "" : fromUser.optString("id", "");
            TelegramCommand command = TelegramCommand.parseText(message.optString("text", ""));
            long sentAt = message.optLong("date", 0L) * 1000L;
            PollerGate.Verdict verdict = PollerGate.verdict(chatType, fromChat, fromUserId,
                    chatId, ownerId, sentAt, System.currentTimeMillis(), command);
            // 先把这一条记成「处理过了」再动手：命令半路把进程弄崩了，重启后也不会把同一张照片再发一遍。
            offset = Math.max(offset, updateId + 1);
            config.setAlertsCommandOffset(offset);
            if (verdict == PollerGate.Verdict.NOT_PRIVATE
                    || verdict == PollerGate.Verdict.NOT_OWNER) {
                noteForeign(fromChat, chatType);
                continue;
            }
            if (verdict == PollerGate.Verdict.STALE) {
                BlackBox.note("停车警报：跳过一条过期的命令（发在 " + (sentAt / 1000L) + "）");
                continue;
            }
            if (verdict == PollerGate.Verdict.NOT_A_COMMAND) {
                continue;
            }
            try {
                switch (command.verb) {
                    case PICTURE:
                        handler.onPicture(command.lane);
                        break;
                    case STATUS:
                        handler.onStatus();
                        break;
                    default:
                        // 不是命令：不回，免得把聊天变成回声
                        break;
                }
            } catch (RuntimeException | OutOfMemoryError e) {
                // 一条命令出错不能把整辆车带走：记下来，线程接着问下一条。
                AppLog.w(TAG, "command failed: " + TelegramRequest.redact(String.valueOf(e)));
                BlackBox.noteImportant("停车警报：处理命令时出错（"
                        + TelegramRequest.redact(String.valueOf(e)) + "）");
            }
        }
        config.setAlertsCommandOffset(offset);
    }

    /** 陌生会话只记一行：同一小时同一个会话不重复记。 */
    private void noteForeign(String chatId, String type) {
        long now = System.currentTimeMillis();
        String masked = mask(chatId);
        if (masked.equals(lastForeignChat) && now - lastForeignAt < FOREIGN_NOTE_MIN_MS) {
            return;
        }
        lastForeignChat = masked;
        lastForeignAt = now;
        BlackBox.note("停车警报：忽略一个不是你的命令（会话 " + masked
                + (type.isEmpty() ? "" : "，" + type) + "）");
    }

    /** 会话号只留末四位：黑匣子会随诊断报告出门，没必要带着完整的 id。 */
    private static String mask(String chatId) {
        if (chatId == null || chatId.length() <= 4) {
            return "…";
        }
        return "…" + chatId.substring(chatId.length() - 4);
    }

    /** 可被打断的等待：{@link #stop()} 之后最迟一秒内就能退出。 */
    private void sleep(long millis) {
        long until = System.currentTimeMillis() + millis;
        while (running && System.currentTimeMillis() < until) {
            try {
                Thread.sleep(Math.min(500L, Math.max(1L, until - System.currentTimeMillis())));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }
}
