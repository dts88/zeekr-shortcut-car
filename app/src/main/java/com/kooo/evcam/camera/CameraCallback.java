package com.kooo.evcam.camera;

import android.util.Size;

/**
 * 摄像头回调接口
 *
 * <p>下半截（2.11 起）是给通道调度（ChannelScheduler）的：都有空的默认实现，没接上调度的实现不用管。
 * 它们都在这一路自己的相机线程上发，由 {@link MultiCameraManager} 转到主线程交给调度 —— 调度只在主线程上跑。
 * 每一步只报一次结果：一步里的重试（会话配不上隔 200 ms 再试、Surface 被弃后摘掉再试）不往外报。</p>
 */
public interface CameraCallback {
    /**
     * 摄像头打开成功
     */
    void onCameraOpened(String cameraId);

    /**
     * 摄像头配置完成，开始预览
     */
    void onCameraConfigured(String cameraId);

    /**
     * 摄像头关闭
     */
    void onCameraClosed(String cameraId);

    /**
     * 摄像头错误
     */
    void onCameraError(String cameraId, int errorCode);

    /**
     * 预览尺寸已确定
     * @param cameraId 摄像头ID
     * @param previewSize 预览尺寸
     */
    void onPreviewSizeChosen(String cameraId, Size previewSize);

    // ===================================================================== 2.11 通道调度

    /**
     * 这一份会话出了第一帧（每配好一份会话最多一次）。开、改输出、救那一步做完没有，看的就是它
     * （{@link SingleCamera#firstFrameOfSessionAt()} 是同一件事的时刻）。
     */
    default void onFirstFrame(String cameraId) {
    }

    /**
     * 一路开着的相机丢了（被相机服务断开、设备报错），在那一次阻塞的关<b>之前</b>发：调度当场把这一路算成在途（LOSING），
     * 别的路这几秒里一步都不动。不论错误码都发 —— 是不是被别的程序拿走，由调度等它关完再判。
     *
     * <p>每一次之后都跟着恰好一次 {@link #onLossClosed}，哪怕关的途中调度又关了它（那时 onLossClosed 先到，
     * 关相机那一次的 {@link #onCameraClosed} 后到）。</p>
     *
     * @param code 被断开是 {@link CameraTaken.Reason#DISCONNECTED_CODE}，其余是 {@code onError} 的码；
     *             用 {@link CameraTaken.Reason#ofLoss} 换成原因
     */
    default void onLossStarted(String cameraId, int code) {
    }

    /**
     * 丢了的那一路，我们这边关完了（{@link #onLossStarted} 的另一半）。
     *
     * @param lostAt  丢的那一刻，{@code SystemClock.elapsedRealtime()} —— {@link CameraContention} 记「别的程序拿走」用的钟，
     *                不是调度的钟；查嫌疑也要按这一刻摆窗口
     * @param closeMs 从丢到关完用了多久（车机拿走后座舱时这一次关要 5–8 秒）
     */
    default void onLossClosed(String cameraId, int code, long lostAt, long closeMs) {
    }

    /**
     * 开不起来：这一路不在相机列表里、查参数或打开抛异常、打开途中 {@code onError} / {@code onDisconnected}、
     * 重开时不在列表里。原因带着来源 —— 几种来源的码是重叠的（见 {@link CameraTaken.Reason}）。
     *
     * <p>设备交到过相机服务手上又出错的，等设备关完才发；根本没开起来的当场发。打开途中被关掉的（调度自己关的）不发。</p>
     */
    default void onOpenFailed(String cameraId, CameraTaken.Reason reason) {
    }

    /**
     * 会话里的一样输出被相机层自己丢了：消费者已被系统收走（建会话前查出来，或者建会话时报 Surface abandoned 查出来），
     * 或者会话配不上、按 JPEG → 后视镜 → 录像的次序丢到了它。调度改输出、关相机摘掉的不走这里（那是调度自己做的）。
     * 丢的是录像输出时，调度要当场叫录像那一侧退出这一路（{@link SingleCamera#dropListener}）。
     */
    default void onOutputDropped(String cameraId, ChannelRules.Out out, SingleCamera.Drop why) {
    }
}
