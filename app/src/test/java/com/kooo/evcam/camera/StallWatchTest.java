package com.kooo.evcam.camera;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * 线程卡住那一行写卡在哪（2.11.4 第 1a 项）：栈顶三帧，加上下面每一帧我们自己的代码 —— 10-13 那一次相机线程卡在
 * {@code configureSession} 里，是改输出、救援重建、还是开好之后建会话，只看第一帧我们的代码分不出。
 */
public class StallWatchTest {

    @Test
    public void whereStuckShowsTheTopFramesAndEveryOneOfOurs() {
        StackTraceElement[] stack = {
                frame("android.os.BinderProxy", "transactNative"),
                frame("android.os.BinderProxy", "transact"),
                frame("android.hardware.camera2.ICameraDeviceUser$Stub$Proxy", "waitUntilIdle"),
                frame("android.hardware.camera2.impl.CameraDeviceImpl", "configureStreamsChecked"),
                frame("com.kooo.evcam.camera.SingleCamera", "configureSession"),
                frame("com.kooo.evcam.camera.SingleCamera", "createCameraPreviewSession"),
                frame("android.os.Handler", "handleCallback"),
                frame("com.kooo.evcam.camera.SingleCamera", "lambda$reconfigure$5"),
                frame("android.os.Looper", "loop")};
        assertEquals("BinderProxy.transactNative <- BinderProxy.transact <- ICameraDeviceUser$Stub$Proxy.waitUntilIdle"
                        + " ... SingleCamera.configureSession <- SingleCamera.createCameraPreviewSession"
                        + " ... SingleCamera.lambda$reconfigure$5",
                StallWatch.whereStuck(stack));
    }

    @Test
    public void ourCodeRightAtTheTopIsNotRepeated() {
        StackTraceElement[] stack = {
                frame("android.os.BinderProxy", "transact"),
                frame("com.kooo.evcam.camera.SingleCamera", "closeDeviceTimed"),
                frame("com.kooo.evcam.camera.SingleCamera", "lambda$reopenWith$9"),
                frame("com.kooo.evcam.camera.SingleCamera", "run")};
        assertEquals("BinderProxy.transact <- SingleCamera.closeDeviceTimed <- SingleCamera.lambda$reopenWith$9"
                + " <- SingleCamera.run", StallWatch.whereStuck(stack));
        assertEquals("?", StallWatch.whereStuck(new StackTraceElement[0]));
    }

    private static StackTraceElement frame(String cls, String method) {
        return new StackTraceElement(cls, method, null, -1);
    }
}
