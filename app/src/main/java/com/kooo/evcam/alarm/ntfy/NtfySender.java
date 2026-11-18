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
 *   <li><b>话题名、服务器不进日志。</b>话题名就是密码（{@link NtfyRequest}）：本类不打印地址；出错的说明只留异常的类名
 *       （{@link #detail}）—— 异常的文字里常带着主机名、IP、证书上的名字 —— 响应体也不留。</li>
 *   <li><b>只在后台线程调。</b>全是阻塞的网络调用；调用方起线程（{@code alarm.SentryAlerts} 那条发送线程）。</li>
 *   <li><b>失败要能说清楚。</b>401 / 403 是服务器不认，404 是地址不对，413 是图太大，429 是发太勤，超时单独一种，
 *       其余归成连不上 —— 每一种都在 {@link AlertChannel.Kind} 里有个名字，翻译成人话是界面的事。</li>
 * </ol>
 *
 * <h3>Three rules</h3>
 *
 * <ol>
 *   <li><b>Topic and server never in a log.</b> The topic name is the password ({@link NtfyRequest}):
 *       this class prints no URLs, and an error detail is only the exception's class name
 *       ({@link #detail}) -- exception texts often carry the host, its IP or certificate names -- with no
 *       response body either.</li>
 *   <li><b>Call only on a background thread.</b> Every call blocks; the caller owns the thread
 *       ({@code alarm.SentryAlerts}' sender).</li>
 *   <li><b>Failures must be explainable.</b> 401 / 403 means the server refused, 404 a wrong address,
 *       413 a picture too large, 429 too frequent, a timeout is its own kind, anything else could not
 *       connect — each has a name in {@link AlertChannel.Kind}, and turning that into a sentence is the
 *       UI's job.</li>
 * </ol>
 */
public final class NtfySender {

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
        return publish("POST", url, body, "text/plain; charset=utf-8");
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
        return publish("PUT", url, jpeg, "image/jpeg");
    }

    private static AlertChannel.Result publish(String method, String url, byte[] body, String type) {
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
            // 响应体不留：自建服务器、反向代理的出错页里可能写着主机名。The body is not kept: it may name the host.
            return AlertChannel.Result.fail(kind(reply.code), reply.code, "");
        } catch (SocketTimeoutException e) {
            return AlertChannel.Result.fail(AlertChannel.Kind.TIMEOUT, 0, detail(e));
        } catch (IOException | RuntimeException e) {
            // 网络那一层抛出来的别的异常（比如 SecurityException）也算连不上，不往外抛
            // Anything else the network stack throws counts as unreachable too, and is never thrown on.
            return AlertChannel.Result.fail(AlertChannel.Kind.NETWORK, 0, detail(e));
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

    /**
     * 出错说明：只留异常的类名（{@code UnknownHostException}、{@code SSLHandshakeException}……）。
     * 异常的文字里常带着主机名、解析出的 IP、证书上的名字，一律不留。
     *
     * An error detail: only the exception's class name. Exception texts often carry the host name, the
     * resolved IP or the certificate's names, so none of that text is kept.
     */
    static String detail(Throwable e) {
        return e.getClass().getSimpleName();
    }
}
