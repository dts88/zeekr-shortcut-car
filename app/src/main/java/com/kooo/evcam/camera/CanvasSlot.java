/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.camera;

import android.graphics.SurfaceTexture;
import android.view.TextureView;

import com.kooo.evcam.AppLog;

/**
 * 交给相机管家的一块画布（相机管家 3c 第二步，项目所有者 2026-11-10）：主界面一格预览、超级镜子（环视、后座舱）、
 * 盲区画面（中控屏两块、HUD）各一个。那一方建一次（{@link CameraKeeper#canvas}）、一直拿着，走的时候关掉（{@link #close}）。
 *
 * <p>那一方只做四件事：画布就绪了交上来（{@link #offer}），不用了撤回（{@link #withdraw}），画布要被系统收走时交给管线留着
 * （{@link #keep}），新视图接回留着的那一块（{@link #takeKept}）。声明在哪一路相机上（当下那一份管线）、管线换了重新交
 * （{@link CameraKeeper} 看着）、相机层这一回不带它（会话配不上、共享预览流挂不下）时记下、撤回、告诉那一方，都在这里。</p>
 *
 * <p>让了之后什么时候再试，时机照旧：那一方到点说（{@link #retry}：超级镜子重新打开、亮屏、放回来、点击恢复；
 * 盲区画面这一段结束）；换了一份管线时管家自己再试哪几样，照 {@link ChannelRules#retryOnNewPipeline}。</p>
 *
 * <p>换了一份管线（{@link CameraKeeper} 看着）：管家先把交着的画布声明到新的一份上，再告诉每一方（{@link Listener#onRebuilt}），
 * 要相机的照自己的规矩再登记 —— 两件在同一下做完，新的一份第一轮就都带着（以前超级镜子、盲区画面各自挂着做这一下）。</p>
 *
 * <p>只在主线程上用。</p>
 */
public final class CanvasSlot {

    private static final String TAG = "CanvasSlot";

    /** 交的结果（{@link #offer}）。 */
    public enum Offer {
        /** 这一路此刻没有相机（管线还没建好、这一份里没有这一路）：没声明，交上来的记着；那一方照旧过一会儿再交。 */
        NO_CAMERA,
        /** 这一回不带它（{@link #refused}）：没声明，等再试。 */
        REFUSED,
        /** 声明了，声明变了（已经叫醒调度）。 */
        DECLARED,
        /** 早就声明着，什么都没变。 */
        UNCHANGED
    }

    /** 那一方要知道的两件事（主线程上叫）。 */
    public interface Listener {
        /** 声明到了这一路相机上（第一次、换了一块画布、管线换了）：照它摆画面（拨回缓冲区尺寸、画面尺寸）。 */
        void onBound(SingleCamera camera);

        /** 相机层这一回不带它：已经记下了，接着就撤回；照这一方的规矩收拾（盲区画面移走窗口），再试之前不再交。 */
        void onRefused();

        /**
         * 管线换了一份、建好了，交着的画布已经声明上去（{@link CameraKeeper} 看着）：照这一方的规矩再对一次 —— 看得见、亮着屏就再交、
         * 再登记（交的时候这一路还没有相机，那时没登记上）。和声明在同一下（主线程），新的一份第一轮就带着它。
         */
        void onRebuilt();
    }

    final ChannelRules.Kind kind;
    final String lane;
    private final CameraKeeper keeper;
    private final Listener listener;
    /**
     * 那一方交上来的画布（和预览那一格的视图）：撤回、交给管线留着时清。声明不上（这一路没有相机、这一回不带它）时也记着 ——
     * 管线换了，管家照它重新交（{@link #onNewPipeline}）。
     */
    private SurfaceTexture offered;
    private TextureView offeredView;
    /** 声明出去的那块画布、声明在哪一路相机上、那一路属于哪一份管线；没声明着是 null。 */
    private SurfaceTexture declared;
    private SingleCamera camera;
    private MultiCameraManager manager;
    /** 这一回不带它：相机层丢了它（{@link Listener#onRefused}），或者那一方自己判这一段不挂（{@link #refuse}）。再试之前不声明。 */
    private boolean refused;
    private boolean closed;

    CanvasSlot(CameraKeeper keeper, ChannelRules.Kind kind, String lane, Listener listener) {
        this.keeper = keeper;
        this.kind = kind;
        this.lane = lane;
        this.listener = listener;
    }

    /**
     * 画布就绪了，交给相机。同一块画布再交一次什么都不变、不叫醒调度 —— 看门狗每两秒、亮屏、放回来都会再交一次。
     * 声明在当下那一份管线的这一路上（还没有管线就建）；换了一路相机（管线换了）或者换了一块画布，先从原来那里撤回。
     *
     * @param texture 画布（输出的身份）
     * @param view    预览那一格的视图（只有预览要：摆位、鱼眼校正画回它）；别的传 null
     */
    public Offer offer(SurfaceTexture texture, TextureView view) {
        if (texture == null || texture.isReleased()) {
            return Offer.NO_CAMERA;
        }
        offered = texture;
        offeredView = view;
        CameraManagerHolder holder = CameraManagerHolder.getInstance();
        MultiCameraManager current = holder.getCameraManager();
        if (current == null) {
            current = holder.getOrInit(keeper.appContext());
        }
        SingleCamera to = current != null ? current.getCamera(lane) : null;
        if (to == null) {
            return Offer.NO_CAMERA;
        }
        if (refused) {
            return Offer.REFUSED;
        }
        return declareOn(to, current, texture, view) ? Offer.DECLARED : Offer.UNCHANGED;
    }

    /** 不用了（窗口移走、服务退出、换出这一档）：撤回声明，交上来的也不再记着。撤回不碰会话 —— 画布还活着就是「留着的输出」。 */
    public void withdraw() {
        offered = null;
        offeredView = null;
        withdrawDeclared();
    }

    /**
     * 画布要被系统收走了（onSurfaceTextureDestroyed，主线程）：撤回，交给管线留着（大纲 §7.6、§7.7）—— 会话里还带着它的那一份留着它，
     * 新视图接回同一块（{@link #takeKept}），没人接回的等它离开会话由调度放（{@link MultiCameraManager#keep}）。关掉了也照样能交。
     *
     * @return true：画布留下了，回调返回 false（TextureView 不放）；false：照常让 TextureView 放
     */
    public boolean keep(SurfaceTexture texture) {
        if (texture == null) {
            return false;
        }
        MultiCameraManager current = manager;
        if (texture == offered) {
            offered = null;
            offeredView = null;
        }
        if (texture == declared) {
            withdrawDeclared();
        }
        if (current == null || current.isReleased()) {
            current = CameraManagerHolder.getInstance().getCameraManager();
        }
        return MultiCameraManager.keep(current, kind, lane, texture);
    }

    /**
     * 新视图要接回上一个视图在这里留下的画布（画布挂上窗口、第一次画之前）：从管线那里拿回来，归调用方
     * （{@code TextureView.setSurfaceTexture}；接不上就再 {@link #keep}，交不出去就放掉）。拿回来之后接着 {@link #offer}。
     *
     * @return 留着、还活着的那一块；没有（或者没有管线）是 null
     */
    public SurfaceTexture takeKept() {
        MultiCameraManager pipeline = CameraManagerHolder.getInstance().getCameraManager();
        if (pipeline == null || pipeline.isReleased()) {
            return null;
        }
        return pipeline.takeKeptCanvas(kind, lane);
    }

    /** 那一方说到再试的时候了（时机见类说明）：不再记着「这一回不带它」。之后照常交（{@link #offer}）。 */
    public void retry() {
        refused = false;
    }

    /** 那一方自己判这一段不挂它（盲区画面窗口挂不上）：同「相机层这一回不带它」，到再试之前不声明。 */
    public void refuse() {
        refused = true;
    }

    /** 这一回不带它（{@link #refuse}、{@link Listener#onRefused}），还没到再试。 */
    public boolean refused() {
        return refused;
    }

    /** 那一方走了（服务退出、界面销毁）：管线换了不再替它交，相机层再报的不再转给它。画布照样能交给管线留着（{@link #keep}）。 */
    public void close() {
        closed = true;
        keeper.closed(this);
    }

    // ------------------------------------------------------------------ 事实（读）

    /** 声明在哪一路相机上（读它的尺寸、在不在出画面）；没声明着是 null。 */
    public SingleCamera camera() {
        return camera;
    }

    /** 那一路属于哪一份管线（读通道调度的判断）；没声明着是 null。 */
    public MultiCameraManager manager() {
        return manager;
    }

    /** 声明出去的那块画布；没声明着是 null。 */
    public SurfaceTexture texture() {
        return declared;
    }

    /** 这块画布此刻在那一路的会话里（按会话配好时记下的快照）。从监测线程读时是近似值。 */
    public boolean inSession() {
        SingleCamera bound = camera;
        SurfaceTexture texture = declared;
        return bound != null && texture != null
                && bound.sessionOutputs().carries(new ChannelRules.Out(kind, texture));
    }

    // ------------------------------------------------------------------ 管家用

    /**
     * 管线换了一份、建好了（{@link CameraKeeper} 看着）：交上来的画布声明到新的一份上 —— 不等那一方的看门狗那两秒，新的一份开相机时
     * 会话里就带着它。这一回不带它的，照 {@link ChannelRules#retryOnNewPipeline} 定再不再试 —— 撤回了的也一样（超级镜子换出
     * 「后视 + 后座舱」再换回来，后座舱照常再交）。新的一份里没有这一路的不动。之后管家叫 {@link #rebuilt}。
     */
    void onNewPipeline(MultiCameraManager pipeline) {
        if (closed) {
            return;
        }
        if (refused) {
            if (!ChannelRules.retryOnNewPipeline(kind)) {
                return;
            }
            refused = false;
        }
        SurfaceTexture texture = offered;
        if (texture == null || texture.isReleased() || pipeline == null || pipeline.isReleased()) {
            return;
        }
        SingleCamera to = pipeline.getCamera(lane);
        if (to != null) {
            declareOn(to, pipeline, texture, offeredView);
        }
    }

    /** 管线换好了、各块画布都声明过了（{@link #onNewPipeline}）：告诉那一方（{@link Listener#onRebuilt}）。关掉了的不叫。 */
    void rebuilt() {
        if (!closed) {
            listener.onRebuilt();
        }
    }

    // ------------------------------------------------------------------ 声明、撤回

    private boolean declareOn(SingleCamera to, MultiCameraManager pipeline, SurfaceTexture texture, TextureView view) {
        if (to != camera || texture != declared) {
            // 换了一路相机（管线换了）或者换了一块画布：先从原来那里撤回，再在这里声明
            withdrawDeclared();
            camera = to;
            manager = pipeline;
            declared = texture;
            listener.onBound(to);
        }
        if (kind == ChannelRules.Kind.PREVIEW) {
            return pipeline.declarePreview(lane, view);   // 声明变了它叫醒调度
        }
        SingleCamera.RecordDropped dropped = why -> onDropped(to, texture, why);
        boolean changed = kind == ChannelRules.Kind.MIRROR ? to.declareMirror(texture, dropped)
                : to.declareSide(kind, texture, dropped);
        if (changed) {
            pipeline.channelScheduler().wake();
        }
        return changed;
    }

    /**
     * 相机层丢了这块画布（调度在主线程上派发）：会话配不上、共享预览流挂不下（超级镜子录像中才让、盲区画面最先让，
     * {@link ChannelRules#onConfigFailed}）—— 记下这一回不带它、告诉那一方、撤回，不然调度会一直想把它加回去、一次次重建那一路的会话。
     */
    private void onDropped(SingleCamera from, SurfaceTexture texture, SingleCamera.Drop why) {
        if (closed || from != camera || texture != declared) {
            return;
        }
        if (why != SingleCamera.Drop.CONFIG_FAILED) {
            AppLog.w(TAG, kind + " canvas on " + lane + " left the session (" + why + ")");
            return;
        }
        refused = true;
        listener.onRefused();
        withdrawDeclared();
    }

    private void withdrawDeclared() {
        SingleCamera from = camera;
        MultiCameraManager owner = manager;
        SurfaceTexture texture = declared;
        camera = null;
        manager = null;
        declared = null;
        if (from == null || texture == null || !MultiCameraManager.withdrawCanvas(from, kind, texture)) {
            return;
        }
        AppLog.i(TAG, kind + " canvas on " + lane + " withdrawn from camera " + from.getCameraId()
                + ": the scheduler takes it out of the session if it is still there");
        if (owner != null) {
            owner.channelScheduler().wake();
        }
    }
}
