/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * Portions Copyright (C) 2026 Fab (Fabbbrrr) - https://github.com/Fabbbrrr
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.alarm.ntfy;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;

import com.kooo.evcam.alarm.AlertChannel;
import com.kooo.evcam.alarm.net.HttpCall;

/**
 * 和 ntfy 说话的那一头：发一句话、发一张图。只发，不读任何东西回来（不收命令）。
 *
 * <h3>三条规矩</h3>
 *
 * <ol>
 *   <li><b>话题名、服务器不进日志。</b>话题名就是密码（{@link NtfyRequest}）：本类不打印地址，出错的说明里带着话题名、
 *       服务器主机名的先擦掉（{@link #scrub}），也不把响应体原样交给界面。</li>
 *   <li><b>只在后台线程调。</b>全是阻塞的网络调用；调用方起线程（{@code alarm.SentryAlerts} 那条发送线程）。</li>
 *   <li><b>失败要能说清楚。</b>401 / 403 是服务器不认，404 是地址不对，413 是图太大，429 是发太勤，超时单独一种，
 *       其余归成连不上 —— 每一种都在 {@link AlertChannel.Kind} 里有个名字，翻译成人话是界面的事。</li>
 * </ol>
 *
 * <h3>Three rules</h3>
 *
 * <ol>
 *   <li><b>Topic and server never in a log.</b> The topic name is the password ({@link NtfyRequest}):
 *       this class prints no URLs, scrubs the topic and the server host out of error details
 *       ({@link #scrub}) and never hands a raw response body to the UI.</li>
 *   <li><b>Call only on a background thread.</b> Every call blocks; the caller owns the thread
 *       ({@code alarm.SentryAlerts}' sender).</li>
 *   <li><b>Failures must be explainable.</b> 401 / 403 means the server refused, 404 a wrong address,
 *       413 a picture too large, 429 too frequent, a timeout is its own kind, anything else could not
 *       connect — each has a name in {@link AlertChannel.Kind}, and turning that into a sentence is the
 *       UI's job.</li>
 * </ol>
 */
public final class NtfySender {

    /** 出错说明里话题名换成这个。What the topic becomes in an error detail. */
    static final String TOPIC_SCRUBBED = "<topic>";
    /** 出错说明里服务器主机名换成这个。What the server host becomes in an error detail. */
    static final String SERVER_SCRUBBED = "<server>";

    private NtfySender() {
    }

    /**
     * 发一句话：文字走请求体，标题在查询串里。
     *
     * Send a line of text: the body is the text, the title rides in the query string.
     */
    public static AlertChannel.Result sendText(String server, String topic, String title, String text) {
        String url = NtfyRequest.textUrl(server, topic, title);
        if (url.isEmpty()) {
            return AlertChannel.Result.fail(AlertChannel.Kind.NOT_CONFIGURED, 0, "server or topic not usable");
        }
        byte[] body = (text == null ? "" : text).getBytes(StandardCharsets.UTF_8);
        return publish("POST", url, body, "text/plain; charset=utf-8", topic, NtfyRequest.host(server));
    }

    /**
     * 发一张图：图片走 PUT 的请求体，说明和标题在查询串里。
     *
     * Send a picture: the JPEG is the PUT body, the caption and the title ride in the query string.
     */
    public static AlertChannel.Result sendPhoto(String server, String topic, String title, String caption,
                                                byte[] jpeg) {
        if (jpeg == null || jpeg.length == 0) {
            return AlertChannel.Result.fail(AlertChannel.Kind.NOT_CONFIGURED, 0, "no picture");
        }
        String url = NtfyRequest.photoUrl(server, topic, title, caption,
                "alert_" + System.currentTimeMillis() + ".jpg");
        if (url.isEmpty()) {
            return AlertChannel.Result.fail(AlertChannel.Kind.NOT_CONFIGURED, 0, "server or topic not usable");
        }
        return publish("PUT", url, jpeg, "image/jpeg", topic, NtfyRequest.host(server));
    }

    private static AlertChannel.Result publish(String method, String url, byte[] body, String type, String topic,
                                               String host) {
        try {
            // 不跟跳转：只发到车主选的那个服务器，跳去别处算失败。No redirects: only the server the owner picked.
            HttpCall.Reply reply = HttpCall.request(method, url)
                    .header("Accept", "application/json")
                    .header("Content-Type", type)
                    .body(body)
                    .noRedirects()
                    .noCaches()
                    .send();
            if (reply.code == 200) {
                return AlertChannel.Result.ok();
            }
            return AlertChannel.Result.fail(kind(reply.code), reply.code, scrub(snippet(reply.body), topic, host));
        } catch (SocketTimeoutException e) {
            return AlertChannel.Result.fail(AlertChannel.Kind.TIMEOUT, 0, scrub(String.valueOf(e), topic, host));
        } catch (IOException | IllegalArgumentException e) {
            return AlertChannel.Result.fail(AlertChannel.Kind.NETWORK, 0, scrub(String.valueOf(e), topic, host));
        }
    }

    /**
     * HTTP 码归成哪一种失败。
     *
     * Which kind of failure an HTTP code is.
     */
    static AlertChannel.Kind kind(int code) {
        if (code == 200) {
            return AlertChannel.Kind.OK;
        }
        if (code == 401 || code == 403) {
            return AlertChannel.Kind.REJECTED;
        }
        if (code == 404) {
            return AlertChannel.Kind.NOT_FOUND;
        }
        if (code == 413) {
            return AlertChannel.Kind.TOO_LARGE;
        }
        if (code == 429) {
            return AlertChannel.Kind.RATE_LIMITED;
        }
        return AlertChannel.Kind.REFUSED;
    }

    /** 出错说明里不留话题名、服务器主机名。Keep the topic and the server host out of an error detail. */
    static String scrub(String text, String topic, String host) {
        if (text == null) {
            return "";
        }
        String out = text;
        String secret = topic == null ? "" : topic.trim();
        if (!secret.isEmpty()) {
            out = out.replace(secret, TOPIC_SCRUBBED);
        }
        String server = host == null ? "" : host.trim();
        if (!server.isEmpty()) {
            out = out.replace(server, SERVER_SCRUBBED);
        }
        return out;
    }

    /** 响应体的头一截，一行。The start of a response body, on one line. */
    private static String snippet(String body) {
        if (body == null) {
            return "";
        }
        String oneLine = body.replace('\n', ' ').replace('\r', ' ').trim();
        return oneLine.length() <= 160 ? oneLine : oneLine.substring(0, 160);
    }
}
