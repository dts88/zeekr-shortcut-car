/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * Portions Copyright (C) 2026 Fab (Fabbbrrr) - https://github.com/Fabbbrrr
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.alarm;

import com.kooo.evcam.AppConfig;
import com.kooo.evcam.AppLog;
import com.kooo.evcam.blackbox.BlackBox;

import java.util.concurrent.ConcurrentLinkedQueue;

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
 *   <li><b>按钮也走同一道门</b>：{@code callback_query} 和文字命令一样只认那个人，
 *       先回一声停掉手机上的转圈，再交给上层（{@link #dispatchCallback}）。</li>
 * </ul>
 *
 * The thread that asks Telegram "did anyone message me?".
 *
 * <h3>Why the car asks</h3>
 *
 * <p>The car sits behind the carrier's NAT, so nothing can reach it; but it can reach out.
 * {@code getUpdates} is long polling: the car holds the request for up to 25 seconds, and the owner's
 * message rides back in that request's reply.</p>
 *
 * <h3>A few hard rules</h3>
 *
 * <ul>
 *   <li><b>Only the configured chat</b> ({@link TelegramCommand#fromOwner}). Anyone else gets no reply,
 *       only a line in the black box; replying would turn the car into a camera anyone could ask for
 *       photos.</li>
 *   <li><b>Handled once, never again</b>: each update's {@code update_id} is saved to settings (the
 *       run-state tier), so after a restart {@code offset} resumes from the next one; yesterday's
 *       commands are not run again.</li>
 *   <li><b>One poller per bot</b>: Telegram returns 409 for concurrent polling of the same bot. So the
 *       car runs only this one thread; nothing else (scripts, Home Assistant) may poll the same bot.</li>
 *   <li><b>Never blocks sleep</b>: when the head unit sleeps the process goes away and this thread stops
 *       with it; no wake lock is taken for it.</li>
 *   <li><b>Buttons pass the same gate</b>: a {@code callback_query} accepts only that same person, and
 *       answers the spinner before handing it up ({@link #dispatchCallback}).</li>
 * </ul>
 */
public final class TelegramPoller {

    private static final String TAG = "TelegramPoller";

    /**
     * 一次长轮询挂多久。Telegram 允许到 50，25 足够灵敏又不浪费。
     *
     * How long one long poll hangs. Telegram allows up to 50; 25 is responsive without waste.
     */
    private static final int POLL_SECONDS = 25;

    /**
     * 网络出问题时的重试间隔：从 5 秒翻倍，最多 60 秒。
     *
     * Retry interval when the network fails: doubles from 5 seconds up to 60 seconds.
     */
    private static final long RETRY_MIN_MS = 5_000L;
    private static final long RETRY_MAX_MS = 60_000L;

    /**
     * 409（别处也在轮询）时歇久一点，别把 409 刷成日志海。
     *
     * On 409 (something else is polling too) pause longer, so 409 does not flood the log.
     */
    private static final long CONFLICT_PAUSE_MS = 60_000L;

    /**
     * 超过这么久的命令不再执行：车睡了一夜醒来，不该突然拍一张几小时前要的照片。
     *
     * Commands older than this are not run: a car that slept overnight should not suddenly take a
     * photo requested hours ago.
     */
    private static final long MAX_COMMAND_AGE_MS = 10 * 60_000L;

    /**
     * 陌生会话的记事先去重：同一个会话一小时只记一行，免得刷爆黑匣子。
     *
     * Notes about foreign chats are deduplicated: one line per chat per hour, so the black box is not
     * flooded.
     */
    private static final long FOREIGN_NOTE_MIN_MS = 60 * 60_000L;

    /**
     * 收到一条命令之后交给谁。
     *
     * Who to hand a command to after it is received.
     */
    public interface Handler {
        /**
         * @param lane        0 前、1 后、2 左、3 右；{@link TelegramCommand#ANY_LANE} 表示按设置
         * @param requestedAt 车主发命令的时刻（毫秒）；0 表示不知道
         *
         * @param lane        0 front, 1 rear, 2 left, 3 right; {@link TelegramCommand#ANY_LANE} means per
         *                    the settings
         * @param requestedAt when the owner sent the command (milliseconds); 0 means unknown
         */
        void onPicture(int lane, long requestedAt);

        void onStatus(long requestedAt);

        /**
         * 认不出来的话（但确实是车主发的）：回一句怎么用。
         *
         * On a message that is not recognized (but really is from the owner): reply with how to use it.
         */
        void onHelp();

        /**
         * 一颗按钮按下了。已经回过了（手机上不转了），剩下的事在这里做。
         *
         * @param data      按钮上的 {@code callback_data}，布局见 {@code GuardToken}
         * @param pressedAt 那条带按钮的消息的时刻（毫秒）；0 表示不知道
         *
         * A button was pressed. It has already been answered (the phone stopped spinning); what is left
         * happens here.
         *
         * @param data      the button's {@code callback_data}, layout in {@code GuardToken}
         * @param pressedAt the time of the message carrying the button (milliseconds); 0 means unknown
         */
        void onButton(String data, long pressedAt);

        /**
         * {@code /guard …}。
         *
         * @param action      后面那个词；{@code null} 不会出现在这里（光写 {@code guard} 是 {@code REPORT}）
         * @param requestedAt 车主发命令的时刻（毫秒）；0 表示不知道
         *
         * {@code /guard …}.
         *
         * @param action      the word after it; {@code null} never arrives here (a bare {@code guard} is
         *                    {@code REPORT})
         * @param requestedAt when the owner sent the command (milliseconds); 0 means unknown
         */
        void onGuard(TelegramCommand.GuardAction action, long requestedAt);
    }

    private final AppConfig config;
    private final Handler handler;
    /** 同一个机器人只许这一条线程：它没真的断气之前，手里这份不放。 */
    private Thread thread;
    private final Object lifecycleLock = new Object();
    /** 断气之前被重新打开：那条线程一落地就接着跑，不起第二条。 */
    private volatile boolean restartWanted;
    /** 排在两轮之间跑的活（那时手上没有挂着的 getUpdates）。 */
    private final ConcurrentLinkedQueue<Runnable> betweenPolls = new ConcurrentLinkedQueue<>();
    private volatile boolean running;
    /**
     * 最近一次问成了的时刻（壁钟，0 = 这轮还没成功过）。
     *
     * Time of the last successful poll (wall clock, 0 = not yet succeeded this run).
     */
    private volatile long lastOkAt;
    /**
     * 最近一次失败的原因（已擦令牌），空串表示没失败过。
     *
     * Reason for the last failure (token already redacted); empty string means no failure yet.
     */
    private volatile String lastError = "";
    private volatile long lastErrorAt;
    private String lastForeignChat;
    private long lastForeignAt;

    public TelegramPoller(AppConfig config, Handler handler) {
        this.config = config;
        this.handler = handler;
    }

    public boolean isRunning() {
        return running;
    }

    /**
     * 最近一次问成的时刻；0 表示这轮还没成功过。
     *
     * Time of the last successful poll; 0 means not yet succeeded this run.
     */
    public long lastOkAt() {
        return lastOkAt;
    }

    /**
     * 最近一次失败的一句话（已擦令牌）；空串表示没失败过。
     *
     * One line describing the last failure (token already redacted); empty string means no failure yet.
     */
    public String lastError() {
        return lastError;
    }

    public long lastErrorAt() {
        return lastErrorAt;
    }

    /**
     * 开始问。已经在问就什么都不做（旧线程还活着也一样：同一个机器人不能有两个轮询者）。
     *
     * Start polling. If already polling, do nothing (same if the old thread is still alive: one bot must
     * not have two pollers).
     */
    public void start() {
        synchronized (lifecycleLock) {
            if (running) {
                return;
            }
            Thread current = thread;
            if (current != null && current.isAlive()) {
                // 它只是被那条 25 秒的读拖住了（interrupt 打不断它）：等它落地接着跑，不再起一条。
                restartWanted = true;
                return;
            }
            // 到这里手里那条（如果有）已经断气了，才可以真的起一条
            thread = new Thread(this::loop, "telegram-poll");
            running = true;
            thread.start();
            BlackBox.note("alerts: command poller started");
        }
    }

    /**
     * 排到两轮之间跑：那时手上没有挂着的 {@code getUpdates}，才许第二次问话（D-14）。
     *
     * <p>可能要等一轮（最长 {@link #POLL_SECONDS} 秒）：读被打断不了。设置里那个
     * 「找会话号」不赶这几秒，但它自己再开一条 {@code getUpdates} 就是把这条规矩撞了。</p>
     */
    public void runBetweenPolls(Runnable task) {
        if (task == null) {
            return;
        }
        betweenPolls.add(task);
    }

    /**
     * 停下，并等那条线程真的收手。
     *
     * <p>只把 {@code running} 置假是不够的：线程还挂在一条 25 秒的长轮询上，
     * 这时候重新打开开关就会起第二条 —— 同一个机器人同时两个 {@code getUpdates}，
     * Telegram 回 409，两条都拿不到消息。所以这里打断它、等它退出，最多等 3 秒。</p>
     *
     * <p>3 秒不够（D-13）：那时候引用留着、这份不换手，下一次 {@link #start()} 复用同一条线程，
     * 它一落地就接着问。一个机器人从头到尾就这一个轮询者。</p>
     *
     * Stop and wait until that thread really lets go.
     *
     * <p>Just setting {@code running} to false is not enough: the thread is still parked on a 25-second
     * long poll, so flipping the switch back on would start a second one; two concurrent
     * {@code getUpdates} on the same bot, Telegram answers 409, and neither gets messages. So here it
     * interrupts it and waits for it to exit, at most 3 seconds.</p>
     *
     * <p>Three seconds is not always enough (D-13): then the reference is kept, this copy does not change
     * hands, and the next {@link #start()} reuses the same thread, which carries on asking the moment it
     * lands. One bot, one poller, from beginning to end.</p>
     */
    public void stop() {
        synchronized (lifecycleLock) {
            running = false;
            restartWanted = false;
            Thread stopping = thread;
            if (stopping == null) {
                return;
            }
            stopping.interrupt();
            try {
                stopping.join(3_000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            if (!stopping.isAlive()) {
                thread = null;
                BlackBox.note("alerts: command poller stopped");
            } else {
                // 还没断气：拿着它等，别拿这条重新起一条
                BlackBox.note("alerts: 轮询线程还挂在上一条问话上，等它落地");
            }
        }
    }

    private void loop() {
        long retryMs = 0;
        while (running || restartWanted) {
            drainBetweenPolls();
            if (!running) {
                restartWanted = false;
                return;
            }
            String token = config.getAlertsTelegramToken();
            String chatId = config.getAlertsTelegramChatId();
            String ownerId = config.getAlertsOwnerId();
            if (!TelegramRequest.looksLikeToken(token) || !TelegramRequest.looksLikeChatId(chatId)) {
                // 令牌填得不对时不能就这么退出：running 会一直是真的，改好令牌也不会再起来。
                // When the token is wrong we must not just exit: running would stay true and a token fixed
                // later would never restart the poller.
                // 什么都不问，隔一会儿再看一眼设置。
                // Ask nothing, and check the settings again after a while.
                sleep(RETRY_MAX_MS);
                continue;
            }
            try {
                JSONObject reply = TelegramSender.getUpdates(token,
                        config.getAlertsCommandOffset(), POLL_SECONDS);
                retryMs = 0;
                lastOkAt = System.currentTimeMillis();
                if (!lastError.isEmpty()) {
                    // 恢复了：这一行是线索 —— “远处不回应、回家就好”就是这样看出来的
                    // Recovered: this line is the clue; "no answer while away, fine once home" is read
                    // from exactly this.
                    BlackBox.note("alerts: Telegram polling recovered");
                    lastError = "";
                }
                int before = (int) config.getAlertsCommandOffset();
                dispatch(reply, token, chatId, ownerId);
                int after = (int) config.getAlertsCommandOffset();
                if (after != before) {
                    BlackBox.note("alerts: new messages, offset now " + after);
                }
            } catch (TelegramSender.ChatListException e) {
                String message = e.getMessage() == null ? "" : e.getMessage();
                boolean conflict = message.contains("409") || message.contains("Conflict");
                long suggested = e.retryAfterMs;
                retryMs = suggested > 0 ? suggested
                        : conflict ? CONFLICT_PAUSE_MS
                        : (retryMs == 0 ? RETRY_MIN_MS : Math.min(RETRY_MAX_MS, retryMs * 2));
                lastError = conflict ? "409 conflict" : message;
                boolean first = lastErrorAt == 0 || System.currentTimeMillis() - lastErrorAt > 60_000L;
                lastErrorAt = System.currentTimeMillis();
                if (first) {
                    // 只记头一次：没网的时候每半分钟一条会把黑匣子刷满
                    // Log only the first one: without network, one line every half minute would fill the
                    // black box.
                    BlackBox.note("alerts: Telegram poll failed, "
                            + (conflict ? "别处也在轮询同一个机器人" : lastError)
                            + "；" + (retryMs / 1000) + " 秒后再问");
                }
                sleep(retryMs);
            } catch (RuntimeException e) {
                retryMs = retryMs == 0 ? RETRY_MIN_MS : Math.min(RETRY_MAX_MS, retryMs * 2);
                lastError = TelegramRequest.redact(String.valueOf(e));
                lastErrorAt = System.currentTimeMillis();
                AppLog.w(TAG, "poll failed: " + lastError);
                sleep(retryMs);
            }
        }
    }

    /**
     * 两轮之间：手上没有挂着的问话，排队的活在这一格跑掉，一件一件来。
     *
     * Between two polls: nothing is parked on a {@code getUpdates} here, so the queued work runs, one
     * task at a time.
     */
    private void drainBetweenPolls() {
        Runnable task;
        while ((task = betweenPolls.poll()) != null) {
            try {
                task.run();
            } catch (RuntimeException | OutOfMemoryError e) {
                AppLog.w(TAG, "between-poll task failed: " + TelegramRequest.redact(String.valueOf(e)));
            }
        }
    }

    /**
     * 一条一批更新：先推 offset（不管认不认，处理过就算过），再按命令干活。
     *
     * One batch of updates: push the offset first (recognized or not, handled is handled), then act on
     * commands.
     */
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
            // 按钮按下：单独一条路。先推 offset 再动手 —— 崩了也不能把同一颗按钮再按一次。
            // A button press: its own path. Push the offset before acting; a crash must not press the
            // same button again.
            JSONObject callback = update.optJSONObject("callback_query");
            if (callback != null) {
                offset = Math.max(offset, updateId + 1);
                config.setAlertsCommandOffset(offset);
                dispatchCallback(callback, token, chatId, ownerId);
                continue;
            }
            // 只认普通消息：编辑过的、频道帖子都不算
            // Only plain messages count: edits and channel posts do not.
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
            // Mark this one as handled before acting: if the command crashes the process midway, after a
            // restart the same photo is not sent again.
            offset = Math.max(offset, updateId + 1);
            config.setAlertsCommandOffset(offset);
            if (verdict == PollerGate.Verdict.NOT_PRIVATE
                    || verdict == PollerGate.Verdict.NOT_OWNER) {
                noteForeign(fromChat, chatType);
                continue;
            }
            if (verdict == PollerGate.Verdict.STALE) {
                BlackBox.note("alerts: skipped a command older than 6 h (" + (sentAt / 1000L) + ")");
                continue;
            }
            if (verdict == PollerGate.Verdict.NOT_A_COMMAND) {
                continue;
            }
            BlackBox.note("alerts: command " + command);
            try {
                switch (command.verb) {
                    case PICTURE:
                        handler.onPicture(command.lane, sentAt);
                        break;
                    case STATUS:
                        handler.onStatus(sentAt);
                        break;
                    case GUARD:
                        handler.onGuard(command.guard, sentAt);
                        break;
                    default:
                        // 车主发的、但认不出来：回一句怎么用。不回的话，
                        // From the owner but not recognized: reply with how to use it. Without a reply,
                        // “发了没反应”和“发错了”就分不出来。
                        // "sent but nothing happened" and "sent it wrong" cannot be told apart.
                        handler.onHelp();
                        break;
                }
            } catch (RuntimeException | OutOfMemoryError e) {
                // 一条命令出错不能把整辆车带走：记下来，线程接着问下一条。
                // One bad command must not take down the whole car: log it, and the thread keeps polling
                // the next one.
                AppLog.w(TAG, "command failed: " + TelegramRequest.redact(String.valueOf(e)));
                BlackBox.noteImportant("alerts: command failed ("
                        + TelegramRequest.redact(String.valueOf(e)) + "）");
            }
        }
        config.setAlertsCommandOffset(offset);
    }

    /**
     * 一颗按钮（{@code callback_query}）：先过门、再回一声、最后才交给上面。
     *
     * <p>顺序是故意的（{@code DECISIONS.md} D10）：回一声只是把手机上的转圈停掉，不是结果；
     * 结果另发一条消息。不先回，车主会以为没按上，再按两下。</p>
     *
     * <p>陌生会话照旧什么都不回，只记一行 —— 回了，任何人拿一个旧按钮就能探这辆车。</p>
     *
     * One button ({@code callback_query}): through the gate, then one acknowledgement, and only then hand
     * it up.
     *
     * <p>The order is deliberate ({@code DECISIONS.md} D10): the acknowledgement only stops the spinner on
     * the phone, it is not the outcome; the outcome is a separate message. Without it the owner thinks
     * the tap missed and taps twice more.</p>
     *
     * <p>A foreign chat still gets no answer at all, only one line in the black box; answering would let
     * anyone with an old button probe this car.</p>
     */
    private void dispatchCallback(JSONObject callback, String token, String chatId, String ownerId) {
        if (!running) {
            return;
        }
        JSONObject fromUser = callback.optJSONObject("from");
        String fromUserId = fromUser == null ? "" : fromUser.optString("id", "");
        JSONObject message = callback.optJSONObject("message");
        JSONObject chat = message == null ? null : message.optJSONObject("chat");
        String fromChat = chat == null ? "" : chat.optString("id", "");
        String chatType = chat == null ? "" : chat.optString("type", "");
        long pressedAt = message == null ? 0L : message.optLong("date", 0L) * 1000L;
        PollerGate.Verdict verdict = PollerGate.verdictForCallback(chatType, fromChat, fromUserId,
                chatId, ownerId, pressedAt, System.currentTimeMillis());
        if (verdict == PollerGate.Verdict.NOT_PRIVATE
                || verdict == PollerGate.Verdict.NOT_OWNER) {
            noteForeign(fromChat, chatType);
            return;
        }
        if (verdict == PollerGate.Verdict.STALE) {
            BlackBox.note("alerts: skipped a button older than 6 h (" + (pressedAt / 1000L) + ")");
            return;
        }
        if (verdict != PollerGate.Verdict.ACCEPT) {
            return;
        }
        // 先让它别转了：一条没有 data 的按钮也算处理过（offset 在上面推过了），只是没动作。
        // Stop the spinner first: a button with no data counts as handled too (the offset moved above),
        // it just has no action.
        TelegramSender.Result acked = TelegramSender.answerCallbackQuery(token,
                callback.optString("id", ""), null);
        if (!acked.ok) {
            // 转圈没停不算事故：动作接着做，结果照样发出去。
            // A spinner that did not stop is not an incident: the action still runs and the outcome still
            // goes out.
            AppLog.w(TAG, "callback answer failed: " + acked.error);
        }
        String data = callback.optString("data", "");
        if (data.isEmpty()) {
            return;
        }
        try {
            handler.onButton(data, pressedAt);
        } catch (RuntimeException | OutOfMemoryError e) {
            // 一颗按钮出错不能把整辆车带走：记下来，线程接着问下一条。
            // One bad button must not take down the whole car: log it, and the thread keeps polling the
            // next one.
            AppLog.w(TAG, "button failed: " + TelegramRequest.redact(String.valueOf(e)));
            BlackBox.noteImportant("alerts: button failed ("
                    + TelegramRequest.redact(String.valueOf(e)) + "）");
        }
    }

    /**
     * 陌生会话只记一行：同一小时同一个会话不重复记。
     *
     * Foreign chats get one line: the same chat is not logged twice within one hour.
     */
    private void noteForeign(String chatId, String type) {
        long now = System.currentTimeMillis();
        String masked = mask(chatId);
        if (masked.equals(lastForeignChat) && now - lastForeignAt < FOREIGN_NOTE_MIN_MS) {
            return;
        }
        lastForeignChat = masked;
        lastForeignAt = now;
        BlackBox.note("alerts: ignored a command from another chat (" + masked
                + (type.isEmpty() ? "" : "，" + type) + "）");
    }

    /**
     * 会话号只留末四位：黑匣子会随诊断报告出门，没必要带着完整的 id。
     *
     * Keep only the last four digits of the chat id: the black box travels with the diagnostic report, so
     * the full id need not go along.
     */
    private static String mask(String chatId) {
        if (chatId == null || chatId.length() <= 4) {
            return "…";
        }
        return "…" + chatId.substring(chatId.length() - 4);
    }

    /**
     * 可被打断的等待：{@link #stop()} 之后最迟一秒内就能退出。
     *
     * An interruptible wait: after {@link #stop()} it exits within one second at the latest.
     */
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
