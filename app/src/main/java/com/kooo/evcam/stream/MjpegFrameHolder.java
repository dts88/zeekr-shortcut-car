package com.kooo.evcam.stream;

import java.util.concurrent.atomic.AtomicReference;

/**
 * 持有最新一帧 JPEG 数据。
 * <p>
 * 编码线程每产出一张 JPEG 就调用 {@link #update(byte[])} 覆盖；HTTP 推流线程各自从这里取最新帧。
 * 这样多个客户端互不阻塞，慢客户端自然丢帧。无客户端时上层可跳过编码以节省 CPU。
 */
public final class MjpegFrameHolder {

    private final AtomicReference<byte[]> latest = new AtomicReference<>();

    /** 由编码线程调用，写入最新一帧 JPEG（含完整 JPEG 字节）。 */
    public void update(byte[] jpeg) {
        latest.set(jpeg);
    }

    /** 取最新帧的引用（不要修改）。无帧时返回 null。 */
    public byte[] get() {
        return latest.get();
    }

    /** 清空持有帧。停止推流时调用。 */
    public void clear() {
        latest.set(null);
    }
}
