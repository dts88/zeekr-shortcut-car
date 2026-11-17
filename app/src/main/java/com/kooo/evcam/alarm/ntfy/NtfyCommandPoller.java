/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.alarm.ntfy;

import com.kooo.evcam.AppConfig;
import com.kooo.evcam.AppLog;
import com.kooo.evcam.alarm.telegram.TelegramCommand;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 警报发出去之后的五分钟里，回头看一眼同一个话题：车主有没有在后面补一句话。
 *
 * <h3>为什么是「再看一眼」而不是等推送</h3>
 *
 * <p>ntfy 的路只有一条：车发得出去，别人进不来（车在运营商 NAT 后面）。所以车不能订阅等推送，
 * 只能在警报发出去之后，用 {@code GET /{topic}/json?since=…&poll=1} 问几趟。车主在同一话题上
 * 发一句 {@code pic}，下一趟就问回来了。</p>
 *
 * <h3>一条命令要同时满足三件事</h3>
 *
 * <ol>
 *   <li><b>是这辆车的窗口之内的</b>：消息时刻（ntfy 只给到整秒）不早于窗口开始的那一秒。
 *       更早的消息不是对这次警报的回答。</li>
 *   <li><b>不是车自己发的</b>：车自己的警报也在同一个话题上，靠标签
 *       {@link NtfySender#MARKER_TAG} 认出来，别把自己的警报当成命令执行。</li>
 *   <li><b>认得出来</b>：直接借 {@link TelegramCommand#parseText}，所以词汇表和 Telegram 一模一样
 *       （{@code pic}、{@code pic front}、{@code status}、{@code guard …}）。
 *       认不出来的就不动 —— ntfy 上没有「回一句怎么用」的地方，所以只有不发。</li>
 * </ol>
 *
 * <h3>安全上的实话：ntfy 没有身份</h3>
 *
 * <p><b>这一条必须写在明面上：</b>ntfy 不告诉你消息是谁发的。任何人都能往这个话题上发一句话，
 * 车会把它当成车主。所以这里只有两道门 —— <b>话题名本身就是密码</b>（{@link NtfyRequest#generateTopic(long, long, int, String)}
 * 那 238 bit），以及<b>每次警报之后五分钟的窗口</b>。话题名漏了，就等于有人能在这五分钟里
 * 指挥这辆车；过了窗口他再发什么都没人听。Telegram 那条路有会话号，这条路没有，不要以为有。</p>
 *
 * <h3>规矩</h3>
 *
 * <ul>
 *   <li><b>自己的线程，自己的窗口</b>：{@code ntfy-poll}，守护线程，每 30 秒问一趟，
 *       窗口到点自己收手；{@link #stop()} 不等人。</li>
 *   <li><b>不重放</b>：见过的最大消息 id 记在 {@link #nextSince}，下一趟从它之后问，重启也不倒回去。</li>
 *   <li><b>不抛出去</b>：一趟问坏了只记一行（令牌先过 {@link NtfyRequest#redact}），线程接着问。</li>
 * </ul>
 *
 * <p>窗口算术、消息新旧、自己消息的认法、{@code since} 的推进，全是纯函数，{@code NtfyCommandPollerTest}
 * 在 JVM 上钉住。<b>真正那一趟 HTTP 没有 JVM 测</b>：它要一台服务器和一个真的进程，这里测不了。</p>
 *
 * <h3>Why "look again" rather than wait for a push</h3>
 *
 * <p>ntfy has one direction: the car can publish, nothing can reach in (the car sits behind the carrier's
 * NAT). So the car cannot subscribe; it asks the same topic a few times after an alert went out, with
 * {@code GET /{topic}/json?since=…&poll=1}. The owner publishes {@code pic} on that topic from the phone,
 * and the next poll brings it back.</p>
 *
 * <h3>A command has to pass three things at once</h3>
 *
 * <ol>
 *   <li><b>Inside this car's window</b>: the message time (ntfy gives whole seconds only) is not earlier
 *       than the second the window started. Anything older is not an answer to this alert.</li>
 *   <li><b>Not the car's own</b>: the car's alerts live on the same topic, recognized by the tag
 *       {@link NtfySender#MARKER_TAG}; an own alert must never run as a command.</li>
 *   <li><b>Recognizable</b>: {@link TelegramCommand#parseText} is reused, so the vocabulary is exactly
 *       Telegram's ({@code pic}, {@code pic front}, {@code status}, {@code guard …}). Anything else is
 *       left alone — there is nowhere on ntfy to say "here is how to use it", so doing nothing is all
 *       that is left.</li>
 * </ol>
 *
 * <h3>The honest security statement: ntfy has no identity</h3>
 *
 * <p><b>This must be said out loud:</b> ntfy does not tell you who published a message. Anyone can put a
 * line on this topic and the car treats it as the owner. So there are exactly two gates — <b>the topic
 * name is the password</b> (the 238 bits from {@link NtfyRequest#generateTopic(long, long, int, String)}) and <b>the five-minute
 * window after each alert</b>. If the topic name leaks, someone can command this car for those five
 * minutes; outside the window nothing they send is heard. The Telegram path has a chat id; this one does
 * not, and no code here pretends otherwise.</p>
 *
 * <h3>Rules</h3>
 *
 * <ul>
 *   <li><b>Own thread, own window</b>: {@code ntfy-poll}, a daemon thread, one poll every 30 seconds, and
 *       it stops by itself when the window ends; {@link #stop()} does not wait for it.</li>
 *   <li><b>No replay</b>: the newest message id seen is carried forward by {@link #nextSince}, so the next
 *       poll starts after it and a restart never walks backwards.</li>
 *   <li><b>Nothing escapes the thread</b>: a failed poll writes one line (token redacted through
 *       {@link NtfyRequest#redact}) and the thread carries on.</li>
 * </ul>
 *
 * <p>The window arithmetic, message freshness, own-message test and {@code since} advance are pure
 * functions, pinned on the JVM by {@code NtfyCommandPollerTest}. <b>The HTTP poll itself has no JVM
 * test</b>: it needs a server and a live process.</p>
 */
public final class NtfyCommandPoller {

    private static final String TAG = "NtfyCommandPoller";

    /** 问一趟隔多久。How long between two polls. */
    public static final long POLL_INTERVAL_MS = 30_000L;

    private static final int CONNECT_TIMEOUT_MS = 15_000;
    private static final int READ_TIMEOUT_MS = 30_000;

    /**
     * ntfy 的时间只到整秒：判断新旧时按秒比。墙钟毫秒和它对不上，硬比毫秒会把同一秒里的命令丢掉。
     *
     * ntfy times have whole-second resolution, so freshness is compared in seconds. Comparing raw
     * milliseconds would drop a command published inside the window's first second.
     */
    static final long TIME_GRANULARITY_MS = 1000L;

    /**
     * 收到一条认得的命令之后交给谁。
     *
     * <p>已经解析过、已经过了三道门；这里只管做事。抛异常只会让这一条作废，线程接着问。</p>
     *
     * Who to hand a recognized command to. It has already been parsed and has passed the three gates;
     * this side only acts. A throw discards this one command and the thread keeps polling.
     */
    public interface Handler {
        void onCommand(TelegramCommand command);
    }

    private final String server;
    private final String topic;
    private final String token;
    private final Handler handler;

    private final Object lifecycleLock = new Object();
    private volatile boolean running;
    /**
     * 每次 {@link #start} 加一：{@link #stop()} 不等线程，所以旧线程可能在一次读里多待几十秒；
     * 它醒来一看代数变了，就把手上的结果丢掉。
     *
     * Bumped on every {@link #start}: {@link #stop()} does not wait, so an old thread can sit in a read
     * for tens of seconds; when it wakes and sees the generation moved, it drops what it read.
     */
    private volatile int generation;
    private Thread thread;
    private volatile Window window = Window.startingAt(0L, 0L);
    /**
     * 见过的最大消息 id（空串 = 还没见过）。跨窗口保留：重开一次不倒回去问。
     *
     * The newest message id seen (empty = none yet). Kept across windows: opening one again does not
     * walk backwards.
     */
    private volatile String since = "";

    public NtfyCommandPoller(String server, String topic, String token, Handler handler) {
        this.server = server == null ? "" : server;
        this.topic = topic == null ? "" : topic.trim();
        this.token = token == null ? "" : token.trim();
        this.handler = handler;
    }

    /** 从设置里那一份建出来。Build one from the stored settings. */
    public static NtfyCommandPoller of(AppConfig config, Handler handler) {
        return new NtfyCommandPoller(config.getAlertsNtfyServer(), config.getAlertsNtfyTopic(),
                config.getAlertsNtfyToken(), handler);
    }

    /**
     * 现在开一个窗口。已经在问就什么都不做 —— 第一个警报开的那扇门还在，第二个警报不另开一扇。
     *
     * Open a window now. If one is already polling, do nothing: the door the first alert opened is still
     * open and a second alert does not open another.
     *
     * @param windowMs 窗口多长（调用方给五分钟）；0 或负数等于「不问」
     */
    public void start(long windowMs) {
        synchronized (lifecycleLock) {
            if (running) {
                return;
            }
            running = true;
            int gen = ++generation;
            window = Window.startingAt(System.currentTimeMillis(), windowMs);
            Thread started = new Thread(() -> loop(gen), "ntfy-poll");
            started.setDaemon(true);
            thread = started;
            started.start();
        }
    }

    /**
     * 收手。不等那条线程：它可能正卡在一次读里，等它就把调用方（发警报那条线程）也拖住。
     *
     * Stop. Does not wait for the thread: it may be parked in a read, and waiting would drag the caller
     * (the alert-sending thread) along with it.
     */
    public void stop() {
        running = false;
        generation++;
        Thread current;
        synchronized (lifecycleLock) {
            current = thread;
            thread = null;
        }
        if (current != null) {
            current.interrupt();
        }
    }

    public boolean isRunning() {
        return running;
    }

    /** 当前窗口（只读）。 The current window, read-only. */
    public Window window() {
        return window;
    }

    // ---------------------------------------------------------------- 线程

    private void loop(int gen) {
        try {
            Window current = window;
            while (running && gen == generation && current.openAt(System.currentTimeMillis())) {
                pollOnce(current, gen);
                long left = current.remainingMs(System.currentTimeMillis());
                if (left <= 0L) {
                    break;
                }
                sleep(Math.min(POLL_INTERVAL_MS, left));
            }
        } catch (RuntimeException | OutOfMemoryError e) {
            // 一条线程的意外不能带走整辆车。日志里不带令牌。
            // An accident on this thread must not take the car down. The line carries no token.
            noteFailure(String.valueOf(e));
        } finally {
            if (gen == generation) {
                running = false;
            }
        }
    }

    /**
     * 问一趟。坏一趟只记一行，下一趟接着问。
     *
     * One poll. A bad one writes one line and the next one still runs.
     */
    private void pollOnce(Window current, int gen) {
        String url = pollUrl(server, topic, sinceParam(since, current.lengthMs()));
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setDoInput(true);
            connection.setRequestProperty("Accept", "application/json");
            if (!token.isEmpty()) {
                // 付费话题的令牌：和发送时同一个头，别处不认。
                // A paid topic's token rides in the same header as on publish; nowhere else.
                connection.setRequestProperty("Authorization", "Bearer " + token);
            }
            int code = connection.getResponseCode();
            if (code != 200) {
                noteFailure("HTTP " + code);
                return;
            }
            handleBody(read(connection.getInputStream()), current, gen);
        } catch (IOException e) {
            noteFailure(String.valueOf(e));
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    /**
     * 一行一条 JSON（ntfy 的形状）。先把 id 往前推，再看它是不是命令 —— 认不认都算处理过，
     * 否则一条认不出来的消息会让每一趟都从它身上重来。
     *
     * One JSON object per line, ntfy's shape. The id moves forward first, then the line is judged as a
     * command: handled is handled, or one unrecognized message would make every poll restart on it.
     */
    private void handleBody(String body, Window current, int gen) {
        if (body == null || body.isEmpty()) {
            return;
        }
        for (String line : body.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            JSONObject json;
            try {
                json = new JSONObject(trimmed);
            } catch (JSONException e) {
                // 一行读不懂不算灾难：下一行，下一趟。
                // One unreadable line is not an incident: the next line, the next poll.
                continue;
            }
            String id = json.optString("id", "");
            since = nextSince(since, id);
            String event = json.optString("event", "");
            if (!"message".equals(event)) {
                continue;
            }
            long messageAtMs = json.optLong("time", 0L) * TIME_GRANULARITY_MS;
            if (!arrivesAfterStart(messageAtMs, current.startMs)) {
                continue;
            }
            if (isOwnMessage(tagsOf(json.optJSONArray("tags")))) {
                continue;
            }
            TelegramCommand command = TelegramCommand.parseText(json.optString("message", ""));
            if (command.verb == TelegramCommand.Verb.UNKNOWN) {
                continue;
            }
            if (!running || gen != generation) {
                return;
            }
            dispatch(command, gen);
        }
    }

    private void dispatch(TelegramCommand command, int gen) {
        if (handler == null) {
            return;
        }
        try {
            AppLog.i(TAG, "command " + command);
            handler.onCommand(command);
        } catch (RuntimeException | OutOfMemoryError e) {
            // 一条命令出错不能把整辆车带走：记下来，线程接着问下一条。
            // One bad command must not take the car down: log it and the thread keeps polling.
            noteFailure("command failed: " + String.valueOf(e));
        }
    }

    /** 一行，擦过令牌。 One line, token redacted. */
    private void noteFailure(String detail) {
        AppLog.w(TAG, NtfyRequest.redact(detail, token));
    }

    /** 可被打断的等待：{@link #stop()} 之后一秒内就能退出。 An interruptible wait: exits within a second of {@link #stop()}. */
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

    private static String read(InputStream stream) throws IOException {
        if (stream == null) {
            return "";
        }
        try (InputStream input = stream) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                out.write(buffer, 0, read);
            }
            return out.toString("UTF-8");
        }
    }

    private static List<String> tagsOf(JSONArray tags) {
        List<String> out = new ArrayList<>();
        if (tags == null) {
            return out;
        }
        for (int i = 0; i < tags.length(); i++) {
            String tag = tags.optString(i, "");
            if (!tag.isEmpty()) {
                out.add(tag);
            }
        }
        return out;
    }

    // ---------------------------------------------------------------- 纯函数（JVM 上测过）

    /**
     * 一次警报开的那扇门：从哪一秒开、到哪一秒关。
     *
     * <p>起点是开窗的那一刻，终点是起点加窗口长度。<b>终点那一刻已经关了</b> —— 边界取半边，
     * 免得「正好五分钟」这种案子两边都能说。</p>
     *
     * The door one alert opens: the millisecond it opens and the millisecond it closes. The end instant
     * is already closed — the boundary is half-open so "exactly five minutes" cannot be argued both ways.
     */
    public static final class Window {

        public final long startMs;
        public final long endMs;

        private Window(long startMs, long endMs) {
            this.startMs = startMs;
            this.endMs = endMs;
        }

        /**
         * @param windowMs 窗口长度；0 或负数得到一扇从来不开的门（{@code startMs == endMs}）
         *
         * @param windowMs window length; 0 or negative gives a door that never opens
         *                 ({@code startMs == endMs})
         */
        public static Window startingAt(long nowMs, long windowMs) {
            long span = Math.max(0L, windowMs);
            return new Window(nowMs, nowMs + span);
        }

        /** 这一刻还开着没有。 Whether it is still open at this instant. */
        public boolean openAt(long nowMs) {
            return nowMs >= startMs && nowMs < endMs;
        }

        /** 还剩多久（到点就是 0）。 How much is left, 0 once it is over. */
        public long remainingMs(long nowMs) {
            return Math.max(0L, endMs - nowMs);
        }

        /** 窗口长度。 The window's length. */
        public long lengthMs() {
            return endMs - startMs;
        }

        @Override
        public String toString() {
            return "Window[" + startMs + ".." + endMs + ")";
        }
    }

    /**
     * 这条消息是不是在这次警报之后到的。
     *
     * <p>ntfy 的 {@code time} 只到整秒，所以按秒比：{@code 0} 表示不知道时刻，那就不算 ——
     * 不知道什么时候来的消息不能当命令。<b>代价写在这里：</b>窗口开始那一秒里发的消息一律收下，
     * 哪怕它其实早了半秒。</p>
     *
     * Whether this message arrived after this alert. ntfy's {@code time} has whole-second resolution, so
     * the comparison is in seconds; {@code 0} means the time is unknown and is refused — a message of
     * unknown age is not a command. <b>The cost, stated:</b> anything published inside the window's first
     * second is accepted even if it was really half a second early.
     */
    public static boolean arrivesAfterStart(long messageAtMs, long windowStartMs) {
        if (messageAtMs <= 0L) {
            return false;
        }
        return (messageAtMs / TIME_GRANULARITY_MS) >= (windowStartMs / TIME_GRANULARITY_MS);
    }

    /**
     * 这条消息是不是车自己发的：标签里有 {@link NtfySender#MARKER_TAG}（配图那条路还带 {@code warning}）。
     *
     * <p><b>为什么必须有这一道：</b>车自己的警报也在同一个话题上。没有它，一句恰好念起来像命令的
     * 警报文字会被自己执行掉。</p>
     *
     * Whether the car itself sent this: the tags carry {@link NtfySender#MARKER_TAG} (a picture also
     * carries {@code warning}). <b>Why this gate must exist:</b> the car's own alerts live on the same
     * topic, and without it an alert whose wording happened to read like a command would run itself.
     */
    public static boolean isOwnMessage(List<String> tags) {
        if (tags == null || tags.isEmpty()) {
            return false;
        }
        for (String tag : tags) {
            if (tag == null) {
                continue;
            }
            String value = tag.trim().toLowerCase(Locale.US);
            if (NtfySender.MARKER_TAG.equals(value) || "warning".equals(value)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 把见过的消息 id 往前推：新的非空就换成它，空/空白就留着原来那个。绝不往回走。
     *
     * Move the newest-seen id forward: a non-empty new one replaces it, an empty one keeps the old value.
     * Never walks backwards.
     */
    public static String nextSince(String currentSince, String messageId) {
        if (messageId == null || messageId.trim().isEmpty()) {
            return currentSince == null ? "" : currentSince.trim();
        }
        return messageId.trim();
    }

    /**
     * {@code since} 这一格填什么：有 id 就用 id；还没有（第一趟）就用窗口长度，服务器只回这么久之内的。
     *
     * <p>用时长而不是「全部」：整段缓存问回来再一条条丢掉，白花一趟流量。</p>
     *
     * What to put in {@code since}: the id when there is one, otherwise the window's length so the server
     * only returns that far back. A duration rather than "all": pulling the whole cache to throw it away
     * is a wasted round trip.
     */
    public static String sinceParam(String sinceId, long windowMs) {
        if (sinceId != null && !sinceId.trim().isEmpty()) {
            return sinceId.trim();
        }
        long minutes = Math.max(1L, (Math.max(0L, windowMs) + 59_999L) / 60_000L);
        return minutes + "m";
    }

    /**
     * 问话的地址：{@code {server}/{topic}/json?since=…&poll=1}。
     *
     * <p>形状是 ntfy 的轮询端点（{@code poll=1} 表示问完就断，不留长连接）。地址和令牌的处理
     * 沿用 {@link NtfyRequest#server}，和发送那条路同一个规矩。</p>
     *
     * The poll address. That is ntfy's polling endpoint ({@code poll=1} answers and closes rather than
     * streaming). Address handling follows {@link NtfyRequest#server}, the same rule as the publish path.
     */
    public static String pollUrl(String server, String topic, String since) {
        String base = NtfyRequest.server(server);
        String name = topic == null ? "" : topic.trim();
        return base + "/" + name + "/json?since=" + since + "&poll=1";
    }
}
