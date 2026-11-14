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

    public TelegramPoller(AppConfig config, Handler handler) {
        this.config = config;
        this.handler = handler;
    }

    public boolean isRunning() {
        return running;
    }

    /** 开始问。已经在问就什么都不做。 */
    public void start() {
        if (running) {
            return;
        }
        running = true;
        thread = new Thread(this::loop, "telegram-poll");
        thread.start();
        BlackBox.note("停车警报：开始回应 Telegram 命令");
    }

    /** 停下。下一次网络请求回来之前就会退出，最多等一条长轮询的时间。 */
    public void stop() {
        running = false;
        thread = null;
        BlackBox.note("停车警报：不再回应 Telegram 命令");
    }

    private void loop() {
        long retryMs = 0;
        while (running) {
            String token = config.getAlertsTelegramToken();
            String chatId = config.getAlertsTelegramChatId();
            if (!TelegramRequest.looksLikeToken(token) || !TelegramRequest.looksLikeChatId(chatId)) {
                return;
            }
            try {
                JSONObject reply = TelegramSender.getUpdates(token,
                        config.getAlertsCommandOffset(), POLL_SECONDS);
                retryMs = 0;
                dispatch(reply, token, chatId);
            } catch (TelegramSender.ChatListException e) {
                String message = e.getMessage() == null ? "" : e.getMessage();
                boolean conflict = message.contains("409") || message.contains("Conflict");
                retryMs = conflict ? CONFLICT_PAUSE_MS
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
    private void dispatch(JSONObject reply, String token, String chatId) {
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
            String from = chat == null ? "" : chat.optString("id", "");
            if (!TelegramCommand.fromOwner(from, chatId)) {
                BlackBox.note("停车警报：忽略一个来自别的会话的命令（" + from + "）");
                offset = Math.max(offset, updateId + 1);
                continue;
            }
            TelegramCommand command = TelegramCommand.parseText(message.optString("text", ""));
            // 先把这一条记成「处理过了」再动手：命令半路把进程弄崩了，重启后也不会把同一张照片再发一遍。
            offset = Math.max(offset, updateId + 1);
            config.setAlertsCommandOffset(offset);
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
                BlackBox.noteImportant("停车警报：处理命令时出错（" + e + "）");
            }
        }
        config.setAlertsCommandOffset(offset);
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
