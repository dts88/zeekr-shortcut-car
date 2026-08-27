package com.kooo.evcam.stream;

import com.kooo.evcam.AppLog;

import java.io.IOException;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 裸 TCP 流服务器。
 * <p>
 * 帧格式：{@code [4 字节小端 uint32 length][JPEG bytes]}，和 Python 脚本的 TCP 协议一致。
 * 每个客户端独立线程，从 {@link MjpegFrameHolder} 取最新帧直写 socket，零中间拷贝。
 * 断线自动清理，不重连（由客户端负责重连）。
 */
public class RawTcpStreamServer implements FrameStreamServer {
    private static final String TAG = "RawTcpStreamServer";

    private final int port;
    private final MjpegFrameHolder frameHolder;
    private ServerSocket serverSocket;
    private Thread acceptThread;
    private final List<ClientHandler> clients = new CopyOnWriteArrayList<>();
    private final AtomicInteger clientCount = new AtomicInteger(0);
    private volatile boolean stopped = false;

    public RawTcpStreamServer(int port, MjpegFrameHolder frameHolder) {
        this.port = port;
        this.frameHolder = frameHolder;
    }

    @Override
    public void startServer() throws IOException {
        serverSocket = new ServerSocket(port);
        stopped = false;
        acceptThread = new Thread(this::acceptLoop, "RawTcp-Accept");
        acceptThread.setDaemon(true);
        acceptThread.start();
        AppLog.i(TAG, "Raw TCP server started on port " + port);
    }

    private void acceptLoop() {
        while (!stopped) {
            try {
                Socket s = serverSocket.accept();
                s.setTcpNoDelay(true);
                ClientHandler h = new ClientHandler(s);
                clients.add(h);
                clientCount.incrementAndGet();
                h.start();
                AppLog.d(TAG, "TCP client connected: " + s.getRemoteSocketAddress()
                        + ", total=" + clientCount.get());
            } catch (IOException e) {
                if (!stopped) AppLog.w(TAG, "accept error: " + e.getMessage());
            }
        }
    }

    @Override
    public void stopServer() {
        stopped = true;
        if (serverSocket != null) {
            try { serverSocket.close(); } catch (IOException ignored) {}
        }
        for (ClientHandler h : clients) {
            h.cancel();
        }
        clients.clear();
        clientCount.set(0);
        if (acceptThread != null) acceptThread.interrupt();
    }

    @Override
    public int getClientCount() {
        return clientCount.get();
    }

    /** 单客户端推送线程。 */
    private class ClientHandler extends Thread {
        private final Socket socket;
        private volatile boolean active = true;

        ClientHandler(Socket s) {
            super("RawTcp-Client-" + s.getRemoteSocketAddress());
            setDaemon(true);
            this.socket = s;
        }

        void cancel() {
            active = false;
            try { socket.close(); } catch (IOException ignored) {}
        }

        @Override
        public void run() {
            byte[] lastSent = null;
            try {
                OutputStream out = socket.getOutputStream();
                while (active && !stopped && !socket.isClosed()) {
                    byte[] jpg = frameHolder.get();
                    if (jpg == null || jpg == lastSent) {
                        try { Thread.sleep(5); } catch (InterruptedException ie) { break; }
                        continue;
                    }
                    lastSent = jpg;
                    // [4B 小端 uint32 length][JPEG]
                    byte[] header = ByteBuffer.allocate(4).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                            .putInt(jpg.length).array();
                    out.write(header);
                    out.write(jpg);
                    out.flush();
                }
            } catch (IOException e) {
                AppLog.d(TAG, "TCP client disconnected: " + socket.getRemoteSocketAddress()
                        + " - " + e.getMessage());
            } finally {
                clientCount.decrementAndGet();
                clients.remove(this);
                try { socket.close(); } catch (IOException ignored) {}
            }
        }
    }
}
