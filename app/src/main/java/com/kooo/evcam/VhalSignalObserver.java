package com.kooo.evcam;

/**
 * 车辆信号观察者——**极氪构建中的空实现**。
 *
 * <h3>为什么是空实现</h3>
 *
 * <p>上游 EVCam 的这个类通过 gRPC 连接吉利车机的 VHAL 服务，读取转向灯、车门、
 * 车速和定制键信号，用于「补盲」功能。这套接口是<b>吉利银河车型专有的</b>：
 * 极氪车机上根本不存在该 gRPC 服务，原实现在这里只会不断重连失败。</p>
 *
 * <p>而它拖进来的 gRPC 依赖（grpc-okhttp / grpc-stub 及其传递依赖）
 * 是 APK 里最大的一块第三方代码之一。App Lab 明确提示过体积过大的应用可能无法启动，
 * 因此这里把实现换成空壳：</p>
 *
 * <ul>
 *   <li>公开 API 与原类<b>完全一致</b>，所有调用点（BlindSpotService、
 *       BlindSpotSettingsFragment）无需改动即可编译；</li>
 *   <li>所有方法为无操作，连接状态恒为「未连接」；</li>
 *   <li>依赖 VHAL 的触发模式因此自动降级——补盲的 logcat / CarSignalManager
 *       触发模式不受影响。</li>
 * </ul>
 *
 * <p>原始实现保留在 git 历史中（见 EVCam 基座提交）。若将来要在吉利车型上复用，
 * 取回该文件并重新加入 gRPC 依赖与 <code>jniLibs/arm64-v8a/libvhal_decoder.so</code> 即可。</p>
 */
public class VhalSignalObserver {

    private static final String TAG = "VhalSignalObserver";

    /** 转向灯信号回调接口。 */
    public interface TurnSignalListener {
        /** 转向灯状态变化 */
        void onTurnSignal(String direction, boolean on);

        /** 连接状态变化 */
        void onConnectionStateChanged(boolean connected);
    }

    /** 车门信号回调接口（与 DoorSignalObserver.DoorSignalListener 方法签名一致）。 */
    public interface DoorSignalListener {
        void onDoorOpen(String side);

        void onDoorClose(String side);

        void onConnectionStateChanged(boolean connected);
    }

    /** 定制键唤醒回调接口。 */
    public interface CustomKeyListener {
        /** 按钮触发（值变为1）且速度条件满足 */
        void onCustomKeyTriggered();
    }

    private final TurnSignalListener listener;

    public VhalSignalObserver(TurnSignalListener listener) {
        this.listener = listener;
    }

    /** 空实现：不监听车门信号。 */
    public void setDoorSignalListener(DoorSignalListener listener) {
        // no-op
    }

    /** 空实现：不监听定制键。 */
    public void setCustomKeyListener(CustomKeyListener listener) {
        // no-op
    }

    /** 空实现：无车速数据，恒为 0。 */
    public float getCurrentSpeed() {
        return 0f;
    }

    /** 空实现：定制键唤醒依赖 VHAL，本构建不支持。 */
    public void configureCustomKey(int speedPropId, int buttonPropId, float speedThreshold) {
        // no-op
    }

    /**
     * 空实现：立即回报「未连接」，让上层走降级逻辑，而不是静默假装在工作。
     */
    public void start() {
        AppLog.i(TAG, "极氪构建不包含 VHAL gRPC 支持，车辆信号监听未启动");
        if (listener != null) {
            listener.onConnectionStateChanged(false);
        }
    }

    /** 空实现。 */
    public void stop() {
        // no-op
    }

    /** 恒为 false：本构建从不连接 VHAL。 */
    public boolean isConnected() {
        return false;
    }

    /**
     * 恒为 true：告诉看门狗「不需要重建」。
     *
     * <p>返回 false 会让 BlindSpotService 的 ensureSignalObserversAlive() 反复尝试
     * 重新初始化一个永远连不上的观察者，白白消耗 CPU。</p>
     */
    public boolean isAlive() {
        return true;
    }

    /** 恒为 false：设置页会如实显示「服务不可达」。 */
    public static boolean testConnection() {
        return false;
    }
}
