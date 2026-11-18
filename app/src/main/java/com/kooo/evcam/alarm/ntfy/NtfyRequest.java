/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * Portions Copyright (C) 2026 Fab (Fabbbrrr) - https://github.com/Fabbbrrr
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.alarm.ntfy;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.regex.Pattern;

/**
 * 发往 ntfy 的那一次请求 —— 拼地址、算话题名、认服务器。纯字符串，便于单测。
 *
 * <p>文字走 POST 的请求体，图片走 PUT 的请求体；标题、配图的说明、优先级、标签都放在查询串里：HTTP 头只能装 ASCII，
 * 中文标题放进头里会变成问号。</p>
 *
 * <p>The request side of ntfy: build URLs, mint topic names, vet the server. Pure strings, so it can
 * be unit-tested. Text goes in the POST body and a picture in the PUT body; the title, the caption,
 * the priority and the tags ride in the query string, because an HTTP header only carries ASCII and a
 * Chinese title would arrive as question marks.</p>
 *
 * <h3>三条规矩</h3>
 *
 * <ol>
 *   <li><b>话题名就是密码。</b>不登录的话题没有别的门，谁知道名字谁就能读。所以只收
 *       {@link #generateTopic(long, long, int, String)} 生成的那种，形状和长度都卡住。</li>
 *   <li><b>只走 https。</b>填错的服务器不存、不悄悄改发默认的那一个：提醒和画面不能去车主没选的地方。</li>
 *   <li><b>不确定就说不确定。</b>服务器认不认，看 HTTP 码，不猜。</li>
 * </ol>
 *
 * <h3>Three rules</h3>
 *
 * <ol>
 *   <li><b>The topic name is the password.</b> A topic without login has no other door: whoever knows
 *       the name can read it. So only {@link #generateTopic(long, long, int, String)} output is accepted,
 *       with the shape and length pinned.</li>
 *   <li><b>https only.</b> A server that does not parse is not stored, and nothing silently falls back to
 *       the default server: alerts and pictures never go somewhere the owner did not pick.</li>
 *   <li><b>When unsure, say unsure.</b> Whether the server accepted something is read off the HTTP code,
 *       never guessed.</li>
 * </ol>
 */
public final class NtfyRequest {

    /** 默认服务器。Default server. */
    public static final String DEFAULT_SERVER = "https://ntfy.sh";

    /** 每条提醒都带的标签：手机上显示成车和警示两个图标。Tags on every alert: a car and a warning sign on the phone. */
    public static final String TAGS = "car,warning";

    /** 高优先级：手机锁着也弹出来。High priority: it pops up even with the phone locked. */
    public static final int PRIORITY = 4;

    /** ntfy 允许的字符：字母、数字、下划线、横线。The characters ntfy allows in a topic. */
    private static final String ALPHABET =
            "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";

    /** 话题名最长 64（服务器限制）。Topic names stop at 64 characters, server-side. */
    private static final int MAX_TOPIC_LENGTH = 64;

    /** 生成名固定前缀：一眼看出这是谁发的。Fixed prefix, so the sender is obvious at a glance. */
    private static final String PREFIX = "zeekrsc_";

    /**
     * 随机尾巴的长度。40 位 base62 最多装得下约 238 bit，但真正不可猜的只有混进摘要的那 16 字节（128 bit），
     * 尾巴只是把它们摊开写。
     *
     * Length of the random tail. Forty base62 characters could hold about 238 bits, but only the 16 bytes
     * mixed into the digest (128 bits) are unguessable; the tail just spells them out.
     */
    private static final int SUFFIX_LENGTH = 40;

    /** 混进摘要的随机字节数：128 bit，尾巴的保密性全在这里。Entropy mixed into the digest: 128 bits. */
    private static final int SEED_RANDOM_BYTES = 16;

    /** 生成名至少要这么长才算「猜不出来」。A generated name is only accepted this long. */
    private static final int MIN_GENERATED_LENGTH = 32;

    /**
     * 服务器的主机名（可以带端口）：字母、数字、横线、点，不带用户名、路径、查询串。
     *
     * The server's host name, optionally with a port: letters, digits, hyphens and dots; no user info,
     * path or query.
     */
    private static final Pattern HOST = Pattern.compile(
            "[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?(?:\\.[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?)*(?::[0-9]{1,5})?");

    private static final SecureRandom RANDOM = new SecureRandom();

    private NtfyRequest() {
    }

    /**
     * 生成一个话题名：固定前缀 + 40 位，从摘要里按 6 bit 一段切出来。
     *
     * <h3>几个输入，各管一件事</h3>
     *
     * <ul>
     *   <li><b>16 字节 {@link SecureRandom}</b> —— 保密性全在这里（128 bit）；</li>
     *   <li><b>安装时刻</b> —— 同一天装的两台车，即使哪一家的随机数坏了，名字也不会撞；</li>
     *   <li><b>当前时刻</b> —— 同一毫秒里连换两次，第二次也不会等于第一次；</li>
     *   <li><b>第几次换</b> —— 这台车第 N 次和第 N-1 次不可能相同；</li>
     *   <li><b>机型 / 编译指纹</b> —— 同车型、同一秒装的两台车仍然不同。</li>
     * </ul>
     *
     * <p>时刻和计数<b>不是</b>保密性，只是防撞：挡住猜的是那 128 bit；挡住两台车撞到同一个名字、互相读到对方提醒的，
     * 是时钟和计数。</p>
     *
     * <p>不编译进 APK、不共用、不写死 —— 每台车自己一个，装上才有，换一次旧的就作废。</p>
     *
     * Generate a topic name: fixed prefix plus 40 characters streamed out of a digest six bits at a
     * time. 16 {@link SecureRandom} bytes carry the secrecy; the install moment, the current moment, the
     * rotation count and the build fingerprint only keep two cars from colliding.
     *
     * @param nowMillis     现在（{@code System.currentTimeMillis()}）
     * @param installMillis 这份安装装上的时刻
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
     * and the stream continues.
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
     * 车主填的服务器地址统一成一个样子：去掉首尾空白和末尾斜杠，没写协议补 https。
     *
     * <p>只收 https：写了 http:// 的、带路径或查询串的、主机名里有别的字符的，都不认。</p>
     *
     * @return {@code https://主机名[:端口]}；不认的返回空串
     *
     * Normalise what the owner typed: trim, drop a trailing slash, default the scheme to https. https
     * only: an http:// address, a path, a query or any other character in the host is refused.
     *
     * @return {@code https://host[:port]}, or an empty string when it is refused
     */
    public static String normalizeServer(String server) {
        if (server == null) {
            return "";
        }
        String value = server.trim();
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        String lower = value.toLowerCase(java.util.Locale.ROOT);
        if (lower.startsWith("https://")) {
            value = value.substring("https://".length());
        } else if (lower.contains("://")) {
            return "";
        }
        if (value.isEmpty() || !HOST.matcher(value).matches()) {
            return "";
        }
        return "https://" + value.toLowerCase(java.util.Locale.ROOT);
    }

    /**
     * 存着的服务器：空的是默认的那一个；存着的不认（不会有：存之前认过一次）就是空串，发不出去，不改发默认的。
     *
     * The stored server: empty means the default one. One that does not parse (it cannot happen: it was
     * vetted before it was stored) is an empty string, so nothing is sent, rather than falling back to the
     * default server.
     */
    public static String server(String stored) {
        if (stored == null || stored.trim().isEmpty()) {
            return DEFAULT_SERVER;
        }
        return normalizeServer(stored);
    }

    /** 服务器在界面上怎么写：只写主机名（「ntfy.sh」）。How the server reads on screen: just the host. */
    public static String host(String stored) {
        String base = server(stored);
        return base.isEmpty() ? "" : base.substring("https://".length());
    }

    /**
     * 深链接：手机上的 ntfy 应用认这个，扫一下就订阅了，不用登录。
     *
     * <p>格式是 {@code ntfy://<host>/<topic>?display=<名字>}（官方文档）。服务器不认、话题不像话题时返回空串。</p>
     *
     * Deep link the phone app understands — scan it and the topic is subscribed, no login. Format is
     * {@code ntfy://<host>/<topic>?display=<name>} per the ntfy docs. Empty when the server or the topic
     * does not parse.
     */
    public static String deepLink(String server, String topic, String display) {
        String host = host(server);
        if (host.isEmpty() || !looksLikeTopic(topic)) {
            return "";
        }
        StringBuilder out = new StringBuilder("ntfy://").append(host).append('/').append(topic.trim());
        if (display != null && !display.trim().isEmpty()) {
            out.append("?display=").append(encode(display.trim()));
        }
        return out.toString();
    }

    /**
     * 发文字：POST 到话题地址，文字就是请求体，标题、优先级、标签在查询串里。服务器、话题不认时返回空串。
     *
     * Send text: POST to the topic with the text as the body; title, priority and tags in the query.
     * Empty when the server or the topic does not parse.
     */
    public static String textUrl(String server, String topic, String title) {
        String base = topicUrl(server, topic);
        if (base.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder(base).append('?');
        appendCommon(out, title);
        return out.toString();
    }

    /**
     * 发图片：PUT 到话题地址，图片就是请求体，说明、标题、文件名、优先级、标签都在查询串里。服务器、话题不认时返回空串。
     *
     * Send a picture: PUT to the topic with the picture as the body; caption, title, file name, priority
     * and tags in the query. Empty when the server or the topic does not parse.
     */
    public static String photoUrl(String server, String topic, String title, String caption,
                                  String filename) {
        String base = topicUrl(server, topic);
        if (base.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder(base).append('?');
        out.append("message=").append(encode(caption == null ? "" : caption)).append('&');
        if (filename != null && !filename.isEmpty()) {
            out.append("filename=").append(encode(filename)).append('&');
        }
        appendCommon(out, title);
        return out.toString();
    }

    private static String topicUrl(String server, String topic) {
        String base = server(server);
        if (base.isEmpty() || !looksLikeTopic(topic)) {
            return "";
        }
        return base + "/" + topic.trim();
    }

    private static void appendCommon(StringBuilder out, String title) {
        if (title != null && !title.isEmpty()) {
            out.append("title=").append(encode(title)).append('&');
        }
        out.append("priority=").append(PRIORITY).append("&tags=").append(encode(TAGS));
    }

    /**
     * 查询串里的值。空格编成 {@code +}、换行编成 {@code %0A} —— 服务器按表单规则解回来。
     *
     * A value in the query string. Space becomes {@code +} and a newline {@code %0A}; the server
     * decodes both by form rules.
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
