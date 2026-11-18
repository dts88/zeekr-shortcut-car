/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Portions Copyright (C) 2026 Fab (Fabbbrrr) - https://github.com/Fabbbrrr
 * Copyright (C) 2026 dts88
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.alarm.ntfy;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;

import com.kooo.evcam.alarm.telegram.TelegramRequest;

/**
 * 发往 ntfy 的那一次请求 —— 拼地址、算话题名、擦掉令牌。纯字符串，便于单测。
 *
 * <p>和 {@link TelegramRequest} 同一个位置、同一套规矩，但 ntfy 的形状不一样：
 * 令牌放在 {@code Authorization} 头里而不是路径里，文字走请求体，图片走 PUT 本体
 * —— 所以配图的说明只能放在查询串里（HTTP 头装不下真换行）。这些形都是量过的，
 * 出处是 {@code docs/ntfy/FINDINGS.md}。</p>
 *
 * <p>The request side of ntfy: build URLs, mint topic names, redact the token. Pure strings, so it can
 * be unit-tested. Same role as {@link TelegramRequest}, but the shape differs: the token rides in an
 * {@code Authorization} header instead of the path, text goes in the request body, and a picture goes
 * in the PUT body — which leaves the query string as the only place a caption can live (an HTTP header
 * cannot carry a real newline). Every shape here was measured; see {@code docs/ntfy/FINDINGS.md}.</p>
 *
 * <h3>三条规矩</h3>
 *
 * <ol>
 *   <li><b>话题名就是密码。</b>免费话题没有别的门，谁猜得到谁就能读写。所以只有
 *       {@link #generateTopic(long, long, int, String)} 生成的才收，形状和长度都卡住。</li>
 *   <li><b>令牌不进日志。</b>要写下来的字符串一律先过 {@link #redact(String, String)}。</li>
 *   <li><b>不确定就说不确定。</b>服务器认不认，看 HTTP 码，不猜。</li>
 * </ol>
 *
 * <h3>Three rules</h3>
 *
 * <ol>
 *   <li><b>The topic name is the password.</b> A free topic has no other door: whoever guesses the name
 *       can read and write. So only {@link #generateTopic(long, long, int, String)} output is accepted, with the shape and
 *       length pinned.</li>
 *   <li><b>Token never in a log.</b> Anything to be written down goes through
 *       {@link #redact(String, String)} first.</li>
 *   <li><b>When unsure, say unsure.</b> Whether the server accepted something is read off the HTTP code,
 *       never guessed.</li>
 * </ol>
 */
public final class NtfyRequest {

    /** 令牌 / 话题名不该出现在日志里，擦成这个。Token or topic must not reach a log line: this replaces it. */
    public static final String REDACTED = "<token>";

    /** 默认服务器。Default server. */
    public static final String DEFAULT_SERVER = "https://ntfy.sh";

    /** ntfy 允许的字符：字母、数字、下划线、横线。The characters ntfy allows in a topic. */
    private static final String ALPHABET =
            "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";

    /** 话题名最长 64（服务器限制）。Topic names stop at 64 characters, server-side. */
    private static final int MAX_TOPIC_LENGTH = 64;

    /** 生成名固定前缀：一眼看出这是谁发的，也方便在黑匣子里认。 */
    private static final String PREFIX = "zeekrsc_";

    /** 随机尾巴的长度：40 × log2(62) ≈ 238 bit。40 characters over 62 symbols ≈ 238 bits. */
    private static final int SUFFIX_LENGTH = 40;

    /** 混进摘要的随机字节数：128 bit，尾巴的保密性全在这里。Entropy mixed into the digest: 128 bits. */
    private static final int SEED_RANDOM_BYTES = 16;

    /** 生成名至少要这么长才算「猜不出来」。A generated name is only accepted this long. */
    private static final int MIN_GENERATED_LENGTH = 32;

    private static final SecureRandom RANDOM = new SecureRandom();

    private NtfyRequest() {
    }

    /**
     * 生成一个话题名：固定前缀 + 40 位，从摘要里按 6 bit 一段切出来。
     *
     * <h3>四个输入，各管一件事</h3>
     *
     * <ul>
     *   <li><b>16 字节 {@link SecureRandom}</b> —— 保密性全在这里（128 bit 进摘要，尾巴 ≈ 238 bit）；</li>
     *   <li><b>安装时刻</b> —— 同一天装的两台车，即使哪一家的随机数坏了，名字也不会撞；</li>
     *   <li><b>当前时刻</b> —— 同一毫秒里连换两次，第二次也不会等于第一次；</li>
     *   <li><b>第几次换</b> —— 这台车第 N 次和第 N-1 次不可能相同；</li>
     *   <li><b>机型 / 编译指纹</b> —— 同车型、同一秒装的两台车仍然不同。</li>
     * </ul>
     *
     * <p>时刻和计数<b>不是</b>保密性，只是防撞。这句话得说清楚：挡住猜的是那 128 bit，挡住两台车
     * 撞到同一个名字、互相读到对方警报的，是时钟和计数。两台车在 ntfy.sh 上撞名的概率约
     * 2^-238，所以「换名字」这个安全动作不会反过来把两台车连到一起。</p>
     *
     * <p>不编译进 APK、不共用、不写死 —— 每台车自己一个，装上才有，换一次旧的就作废。</p>
     *
     * Generate a topic name: fixed prefix plus 40 characters streamed out of a digest six bits at a
     * time. Four inputs, four jobs: 16 {@link SecureRandom} bytes carry the secrecy; the install
     * moment, the current moment, the rotation count and the build fingerprint carry anti-collision.
     * The clocks are <b>not</b> secrecy — 128 random bits are — and the sentence says so, because a
     * rotation that could collide would join two cars instead of separating them.
     *
     * @param nowMillis     现在（{@code System.currentTimeMillis()}）
     * @param installMillis 这份安装第一次换话题的时刻（{@code AppConfig} 记下来）
     * @param round         这台车第几次换（从 1 起）
     * @param buildSalt     机型 / 编译指纹，可以为 null
     */
    public static String generateTopic(long nowMillis, long installMillis, int round, String buildSalt) {
        StringBuilder seed = new StringBuilder(64);
        seed.append(nowMillis).append('|').append(installMillis).append('|').append(round)
                .append('|').append(buildSalt == null ? "" : buildSalt).append('|');
        byte[] random = new byte[SEED_RANDOM_BYTES];
        RANDOM.nextBytes(random);
        seed.append(hex(random));
        return PREFIX + tail(sha256(seed.toString()));
    }

    /**
     * 从摘要里取 40 个字符：每次 6 bit，落在 62 之外的丢掉重取（拒绝采样），所以每一位都是均匀的。
     *
     * <p>摘要用完就再哈希一次接着取 —— 一段 32 字节的摘要偶尔不够 40 个字符（62 和 63 要丢掉）。</p>
     *
     * Take 40 characters out of the digest, six bits at a time, dropping anything in 62..63
     * (rejection sampling), so every character is uniform. When the digest runs dry it is hashed again
     * and the stream continues — 32 bytes do not always cover 40 characters once the two extra symbols
     * are rejected.
     */
    private static String tail(byte[] digest) {
        StringBuilder out = new StringBuilder(SUFFIX_LENGTH);
        long acc = 0;
        int bits = 0;
        int at = 0;
        byte[] block = digest;
        while (out.length() < SUFFIX_LENGTH) {
            while (bits < 6) {
                if (at >= block.length) {
                    block = sha256(block);
                    at = 0;
                }
                acc = (acc << 8) | (block[at++] & 0xFFL);
                bits += 8;
            }
            int six = (int) ((acc >>> (bits - 6)) & 0x3FL);
            bits -= 6;
            if (six < ALPHABET.length()) {
                out.append(ALPHABET.charAt(six));
            }
        }
        return out.toString();
    }

    /** SHA-256。Android 从 API 1 就有，JVM 也有，单测直接跑。SHA-256, on both Android and the JVM. */
    private static byte[] sha256(String text) {
        return sha256(text.getBytes(StandardCharsets.UTF_8));
    }

    /** 摘要用完再哈希一次接着取字节（{@link #tail}）。Hash the digest again when the stream runs dry. */
    private static byte[] sha256(byte[] bytes) {
        try {
            return java.security.MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (java.security.NoSuchAlgorithmException e) {
            // 不可能：SHA-256 是 Java 的必备算法。若真没有，宁可不生成话题也不能生成可预测的名字。
            // Impossible: SHA-256 is mandatory in Java. If it were missing, refusing beats emitting a
            // predictable name.
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** 小写十六进制。Lower-case hex. */
    private static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            out.append(Character.forDigit((b >> 4) & 0xF, 16));
            out.append(Character.forDigit(b & 0xF, 16));
        }
        return out.toString();
    }

    /**
     * 这个字符串能不能当话题名用：字符集对、长度对。
     *
     * <p>只认 ntfy 允许的字符；长度上界 64 是服务器的规矩，下界 32 是我们自己的规矩 ——
     * 短名字在公网上等于没有门。</p>
     *
     * Whether this string can be a topic name: right alphabet, right length. Only ntfy's allowed
     * characters; 64 is the server's ceiling, and 32 is ours — a short name is no door at all on a
     * public server.
     */
    public static boolean looksLikeTopic(String topic) {
        if (topic == null) {
            return false;
        }
        String value = topic.trim();
        if (value.length() < MIN_GENERATED_LENGTH || value.length() > MAX_TOPIC_LENGTH) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            if (ALPHABET.indexOf(value.charAt(i)) < 0 && value.charAt(i) != '_' && value.charAt(i) != '-') {
                return false;
            }
        }
        return true;
    }

    /**
     * 服务器地址统一成一个样子：去掉首尾空白和末尾斜杠，缺协议补 https。
     *
     * @return 规范化后的地址；空串表示「用默认的」；不像地址的返回空串
     *
     * Normalise the server address: trim, drop a trailing slash, default the scheme to https.
     *
     * @return the normalised address, an empty string for "use the default", or an empty string when
     *     it does not look like an address at all
     */
    public static String normalizeServer(String server) {
        if (server == null) {
            return "";
        }
        String value = server.trim();
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        if (value.isEmpty()) {
            return "";
        }
        if (!value.startsWith("http://") && !value.startsWith("https://")) {
            value = "https://" + value;
        }
        String rest = value.substring(value.indexOf("://") + 3);
        if (rest.isEmpty() || rest.indexOf('/') >= 0) {
            return "";
        }
        return value;
    }

    /** 存着的地址，空的按默认算。The stored address, with empty meaning the default. */
    public static String server(String stored) {
        String normalised = normalizeServer(stored);
        return normalised.isEmpty() ? DEFAULT_SERVER : normalised;
    }

    /**
     * 深链接：手机上的 ntfy app 认这个，扫一下或点一下就订阅了。
     *
     * <p>格式是 {@code ntfy://<host>/<topic>?display=<名字>}（官方文档）。app 里显示的名字
     * 用 {@code +} 表示空格。</p>
     *
     * Deep link the phone app understands — scan or tap it and the topic is subscribed. Format is
     * {@code ntfy://<host>/<topic>?display=<name>} per the ntfy docs; spaces in the display name are
     * plus signs.
     */
    public static String deepLink(String server, String topic, String display) {
        String base = server(server);
        String host = base.substring(base.indexOf("://") + 3);
        StringBuilder out = new StringBuilder("ntfy://").append(host).append('/').append(topic);
        if (display != null && !display.trim().isEmpty()) {
            out.append("?display=").append(encode(display.trim()));
        }
        return out.toString();
    }

    /** 发文字：POST 到话题地址，文字就是请求体。Send text: POST to the topic, the body is the text. */
    public static String textUrl(String server, String topic) {
        return server(server) + "/" + encodePath(topic);
    }

    /**
     * 发图片：PUT 到话题地址，图片就是请求体，说明走查询串。
     *
     * <p>为什么说明不在头上：HTTP 头装不下真换行，而警报的说明是三行。量过 ——
     * 头里的 {@code \n} 会被原样存成两个字符（{@code docs/ntfy/FINDINGS.md} §1.9）。</p>
     *
     * Send a picture: PUT to the topic, the picture is the body, the caption rides in the query
     * string. Not in a header: an HTTP header cannot hold a real newline and alert captions are three
     * lines. Measured — a backslash-n in a header is stored as two literal characters.
     */
    public static String photoUrl(String server, String topic, String caption, String title,
                                  String filename) {
        StringBuilder out = new StringBuilder(textUrl(server, topic)).append('?');
        out.append("message=").append(encode(caption == null ? "" : caption));
        if (title != null && !title.isEmpty()) {
            out.append("&title=").append(encode(title));
        }
        if (filename != null && !filename.isEmpty()) {
            out.append("&filename=").append(encode(filename));
        }
        // 高优先级：手机锁着也能弹出来。High priority: it pops up even with the phone locked.
        out.append("&priority=4&tags=").append(encode("car,warning"));
        return out.toString();
    }

    /**
     * 查这条话题是否已被保护（付费方案的话题预约）。匿名试发一次，403 就是保护上了。
     *
     * <p>这是「保护好了」唯一的证据 —— 不靠服务器回一句成功，靠别人发不进来。</p>
     *
     * Probe whether the topic is protected (a paid-plan reservation): fire one anonymous publish and
     * read 403. That is the only evidence of protection worth having — not a success message, but an
     * outsider being refused.
     */
    public static String probePublishUrl(String server, String topic) {
        return server(server) + "/" + encodePath(topic) + "/publish?message=probe&cache=no";
    }

    /** 账号与限额：付费档位的额度从这一条读。Account and limits: the paid tier's quota comes from here. */
    public static String accountUrl(String server) {
        return server(server) + "/v1/account";
    }

    /**
     * 擦掉令牌：日志、故障说明、黑匣子都只能用擦过的那一份。
     *
     * <p>ntfy 的令牌放在头里，比 Telegram 好一点；但异常信息、URL、用户敲错的原文都可能带着它，
     * 所以规则一样：写下来之前先过这里。</p>
     *
     * Redact the token. ntfy keeps it in a header rather than the path, which is better than Telegram,
     * but exception text, URLs and whatever the owner pasted can all carry it — so the rule is the
     * same: go through here before writing anything down.
     */
    public static String redact(String text, String token) {
        if (text == null) {
            return "";
        }
        if (token == null || token.trim().isEmpty()) {
            return text;
        }
        return text.replace(token.trim(), REDACTED);
    }

    /**
     * 这一档最多能发多大的图。免费档 2 MiB 是量出来的硬边界（多一个字节就是 413）。
     *
     * How big a picture this tier can take. The free tier's 2 MiB is a measured hard boundary — one
     * byte more is a 413.
     */
    public static int maxPhotoBytes(boolean paid) {
        return paid ? 25 * 1024 * 1024 : 2 * 1024 * 1024;
    }

    /** 路径里的那一段：话题名只含安全字符，仍然过一遍。The path segment: safe characters, still encoded. */
    private static String encodePath(String topic) {
        return topic == null ? "" : topic.trim();
    }

    /**
     * 查询串里的值。空格编成 {@code +}、换行编成 {@code %0A} —— 服务器按表单规则解回来，
     * 两件事都量过。
     *
     * A value in the query string. Space becomes {@code +} and a newline {@code %0A}; the server
     * decodes both by form rules. Measured.
     */
    static String encode(String value) {
        try {
            return URLEncoder.encode(value, StandardCharsets.UTF_8.name());
        } catch (java.io.UnsupportedEncodingException e) {
            // UTF-8 一定存在；真没有就把原样给出去，让服务器去拒绝。
            // UTF-8 always exists; if it somehow does not, hand the value over unchanged and let the
            // server refuse it.
            return value;
        }
    }
}
