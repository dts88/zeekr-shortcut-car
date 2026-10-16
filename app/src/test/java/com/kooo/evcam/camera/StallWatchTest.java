package com.kooo.evcam.camera;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

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

    /**
     * 诊断报告带最近几份现场，每一份都带上（2.11.5 第 D4 项）：以前只带最新的 32 KB，10-13 23:59 那一份被 10-14 21:52 那一份挤掉了。
     * 一份 = 一次卡住，连同它后面的恢复那一份；放不下的每一份掐掉中间，开头（标题、计数）和结尾留着。
     */
    @Test
    public void everyRecentReportIsKeptAndTrimmedInTheMiddle() {
        String saved = report("STALL recorder 2: a", 3) + report("RECOVER recorder 2 after 1ms", 1)
                + report("STALL mirror: b", 2);
        assertEquals("放得下就原样", saved, StallWatch.fitReports(saved, 5, 100_000));

        StringBuilder seven = new StringBuilder();
        for (int i = 0; i < 7; i++) {
            seven.append(report("STALL r" + i + ": x", 1));
        }
        String kept = StallWatch.fitReports(seven.toString(), StallWatch.KEEP_REPORTS, 100_000);
        assertTrue(kept, kept.startsWith("(2 older reports not shown; the newest 5 follow)\n===== STALL r2: x"));
        assertFalse(kept.contains("STALL r1:"));
        assertTrue(kept.endsWith(report("STALL r6: x", 1)));

        String small = report("STALL small: y", 1);
        String fitted = StallWatch.fitReports(report("STALL big: x", 200) + small, 5, 600);
        assertTrue("大的那一份开头（标题、计数）还在", fitted.startsWith("===== STALL big: x | t =====\nline 0\n"));
        assertTrue(fitted, fitted.contains("line 21\n(... 1442 characters cut from the middle of this report ...)\nline 191\n"));
        assertTrue("小的那一份整份带上", fitted.endsWith(small));
    }

    private static String report(String head, int lines) {
        StringBuilder sb = new StringBuilder("===== ").append(head).append(" | t =====\n");
        for (int i = 0; i < lines; i++) {
            sb.append("line ").append(i).append('\n');
        }
        return sb.append("===== end =====\n\n").toString();
    }

    private static StackTraceElement frame(String cls, String method) {
        return new StackTraceElement(cls, method, null, -1);
    }
}
