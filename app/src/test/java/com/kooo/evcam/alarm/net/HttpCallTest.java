/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Portions Copyright (C) 2026 Fab (Fabbbrrr) - https://github.com/Fabbbrrr
 * Copyright (C) 2026 dts88
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.alarm.net;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 两条出口共用的那一层：发出去的是什么，读回来的又是什么。
 *
 * <h3>测的是什么</h3>
 *
 * <p>这一层原来是两份代码，两份的差别没有一处是故意的。所以这里钉的不是「能连上」——
 * 那件事网络自己会证明 —— 而是两份代码合起来之后必须一致的三条：</p>
 *
 * <ol>
 *   <li>请求体、方法、头，真的发出去了（对面收到了才算）</li>
 *   <li>4xx 的说明从 {@code errorStream} 读回来，不是空的</li>
 *   <li>回复只读一次、最多 4 MiB、按 UTF-8 解；{@code null} 流当空回复</li>
 * </ol>
 *
 * <p>第三条尤其重要：{@code errorStream} 在对面没写错误体的时候就是 {@code null}，
 * 原来 ntfy 那一份读 {@code null} 得空串，Telegram 那一份会抛 —— 合起来之后只有一种行为。</p>
 *
 * <p>对面是一个本地的一次性 socket：不连任何外部服务，也不连 ntfy.sh。车机上的测试不该出门。</p>
 *
 * <h3>What is tested</h3>
 *
 * <p>This layer was two copies of the same code, and nothing about where they differed was deliberate.
 * So the tests pin what has to be one behaviour now, not "it connects" (the network proves that): the
 * body, method and headers really leave; a 4xx explanation comes back from {@code errorStream}; the reply
 * is read once, capped at 4 MiB, decoded as UTF-8, and a null stream counts as empty.</p>
 *
 * <p>The last one matters most: {@code errorStream} is null when the other side wrote no error body. One
 * copy read that as an empty string, the other threw. There is one behaviour now.</p>
 *
 * <p>The other side is a one-shot local socket: no outside service, and never ntfy.sh — a test on the
 * head unit has no business leaving the car.</p>
 */
public class HttpCallTest {

    private ServerSocket socket;
    private Thread serverThread;
    private volatile int replyCode = 200;
    private volatile String replyBody = "";

    private final AtomicReference<String> seenMethod = new AtomicReference<>();
    private final AtomicReference<String> seenBody = new AtomicReference<>();
    private final AtomicReference<String> seenContentType = new AtomicReference<>();
    private final AtomicReference<String> seenAuthorization = new AtomicReference<>();

    @Before
    public void startServer() throws IOException {
        socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
        serverThread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    serveOne();
                } catch (IOException ignored) {
                    // 测试自己失败会说出原因，这里不抢话
                    // A failing test says why; this thread does not compete for the last word
                }
            }
        });
        serverThread.setDaemon(true);
        serverThread.start();
    }

    @After
    public void stopServer() throws IOException {
        if (socket != null && !socket.isClosed()) {
            socket.close();
        }
    }

    private String url() {
        return "http://127.0.0.1:" + socket.getLocalPort() + "/send";
    }

    @Test
    public void aTextBodyReachesTheOtherSideWithItsHeaders() throws IOException {
        replyBody = "ok";

        HttpCall.Reply reply = HttpCall.request("PUT", url())
                .header("Content-Type", "text/plain; charset=utf-8")
                .header("Authorization", "Bearer tk_example")
                .body("车回来了".getBytes(StandardCharsets.UTF_8))
                .send();

        assertTrue(reply.ok());
        assertEquals("PUT", seenMethod.get());
        assertEquals("车回来了", seenBody.get());
        assertEquals("text/plain; charset=utf-8", seenContentType.get());
        assertEquals("令牌唯一的落点就是这个头", "Bearer tk_example", seenAuthorization.get());
    }

    @Test
    public void aBufferedBodyArrivesWholeAndUncounted() throws IOException {
        // Telegram 的 multipart 走这条：不报长度，让实现自己缓冲好再发
        // Telegram's multipart takes this path: no declared length, the implementation buffers first
        replyBody = "ok";
        byte[] body = new byte[] {'-', '-', 'z', 'e', 'e', 'k', 'r', 0, 1, 2, 3, '-', '-'};

        HttpCall.Reply reply = HttpCall.request("POST", url())
                .header("Content-Type", "multipart/form-data; boundary=zeekr")
                .header("Connection", "close")
                .bufferedBody(body)
                .send();

        assertTrue(reply.ok());
        assertEquals("POST", seenMethod.get());
        assertEquals("multipart/form-data; boundary=zeekr", seenContentType.get());
        assertEquals("请求体一个字节都不能少", new String(body, StandardCharsets.ISO_8859_1),
                seenBody.get());
    }

    @Test
    public void theErrorBodyComesBackOnAFourHundred() throws IOException {
        replyCode = 413;
        replyBody = "attachment too large";

        HttpCall.Reply reply = HttpCall.request("PUT", url())
                .header("Content-Type", "image/jpeg")
                .body(new byte[] {1, 2, 3})
                .send();

        assertFalse(reply.ok());
        assertEquals(413, reply.code);
        assertEquals("说明在 errorStream 里，不能是空的", "attachment too large", reply.body);
    }

    @Test
    public void aGetReadsTheReply() throws IOException {
        replyBody = "{\"ok\":true}";

        HttpCall.Reply reply = HttpCall.request("GET", url()).send();

        assertTrue(reply.ok());
        assertEquals("GET", seenMethod.get());
        assertEquals("{\"ok\":true}", reply.body);
    }

    @Test
    public void aReplyWithNoBodyReadsAsEmpty() throws IOException {
        replyCode = 204;
        replyBody = "";

        HttpCall.Reply reply = HttpCall.request("GET", url()).send();

        assertEquals(204, reply.code);
        assertEquals("", reply.body);
        assertFalse("只有 200 才算成", reply.ok());
    }

    @Test
    public void aNullStreamReadsAsEmptyInsteadOfThrowing() throws IOException {
        // 对面没写错误体的时候 errorStream 就是 null：两份旧代码在这里一个得空串、一个抛
        // errorStream is null when the other side wrote no error body: one old copy returned "", one threw
        assertEquals("", HttpCall.read(null));
    }

    @Test
    public void nonAsciiSurvivesTheRead() throws IOException {
        String text = "图片太大（2 MiB 上限）";
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        assertEquals(text, HttpCall.read(new ByteArrayInputStream(bytes)));
    }

    @Test
    public void aHugeReplyStopsAtTheCap() throws IOException {
        // 5 MiB 的回复：读到底会把内存吃掉，所以最多读到上限附近就停
        // A 5 MiB reply: reading to the end would eat memory, so it stops around the cap
        int size = 5 * 1024 * 1024;
        byte[] huge = new byte[size];
        java.util.Arrays.fill(huge, (byte) 'x');

        String body = HttpCall.read(new ByteArrayInputStream(huge));

        assertTrue("读到上限就该停：" + body.length(),
                body.length() <= HttpCall.MAX_BODY_BYTES + 4096);
        assertTrue("不该读到 5 MiB：" + body.length(), body.length() < size);
    }

    /** 收一次请求，记下来，回一句，然后关掉。Take one request, record it, answer, close. */
    private void serveOne() throws IOException {
        try (Socket client = socket.accept()) {
            client.setSoTimeout(10_000);
            InputStream in = new BufferedInputStream(client.getInputStream());

            String requestLine = readLine(in);
            seenMethod.set(requestLine.isEmpty() ? "" : requestLine.split(" ")[0]);

            int contentLength = 0;
            String line;
            while (!(line = readLine(in)).isEmpty()) {
                int colon = line.indexOf(':');
                if (colon <= 0) {
                    continue;
                }
                String name = line.substring(0, colon).trim().toLowerCase(Locale.ROOT);
                String value = line.substring(colon + 1).trim();
                if ("content-type".equals(name)) {
                    seenContentType.set(value);
                } else if ("authorization".equals(name)) {
                    seenAuthorization.set(value);
                } else if ("content-length".equals(name)) {
                    contentLength = Integer.parseInt(value);
                }
            }

            byte[] body = new byte[contentLength];
            int offset = 0;
            while (offset < contentLength) {
                int read = in.read(body, offset, contentLength - offset);
                if (read < 0) {
                    break;
                }
                offset += read;
            }
            seenBody.set(new String(body, 0, offset, StandardCharsets.UTF_8));

            byte[] reply = replyBody.getBytes(StandardCharsets.UTF_8);
            String head = "HTTP/1.1 " + replyCode + " " + reason(replyCode) + "\r\n"
                    + "Content-Length: " + reply.length + "\r\n"
                    + "Content-Type: text/plain; charset=utf-8\r\n"
                    + "Connection: close\r\n"
                    + "\r\n";
            OutputStream out = client.getOutputStream();
            out.write(head.getBytes(StandardCharsets.US_ASCII));
            out.write(reply);
            out.flush();
        }
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        int read;
        while ((read = in.read()) >= 0) {
            if (read == '\n') {
                break;
            }
            if (read != '\r') {
                line.write(read);
            }
        }
        return new String(line.toByteArray(), StandardCharsets.ISO_8859_1);
    }

    private static String reason(int code) {
        switch (code) {
            case 200:
                return "OK";
            case 204:
                return "No Content";
            case 413:
                return "Payload Too Large";
            case 429:
                return "Too Many Requests";
            default:
                return "Status";
        }
    }
}
