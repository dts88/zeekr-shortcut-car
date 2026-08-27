package com.kooo.evcam.stream;

/**
 * 统一流服务器接口，MJPEG HTTP / 裸 TCP / UDP 三种实现。
 * <p>
 * 端口固定（默认 29543），三选一，由 {@link com.kooo.evcam.AppConfig#getMjpegStreamProtocol()} 决定。
 * 所有实现都从 {@link MjpegFrameHolder} 取最新 JPEG 帧推送给客户端。
 */
interface FrameStreamServer {
    /** 启动服务器（监听端口）。 */
    void startServer() throws java.io.IOException;

    /** 完全停止服务器（关闭监听 + 断开所有客户端）。 */
    void stopServer();

    /** 当前连接的客户端数（用于 UI 显示 + 决定是否暂停编码）。 */
    int getClientCount();
}
