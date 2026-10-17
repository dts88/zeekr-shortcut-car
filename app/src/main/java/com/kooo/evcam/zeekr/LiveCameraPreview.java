package com.kooo.evcam.zeekr;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.SurfaceTexture;
import android.util.AttributeSet;
import android.widget.FrameLayout;

/** Cabin preview; black cover is drawn after its native TextureView and labels. */
public final class LiveCameraPreview extends FrameLayout implements LivePreview {
    private final PreviewFrameGuard guard;
    public LiveCameraPreview(Context context) { this(context, null); }
    public LiveCameraPreview(Context context, AttributeSet attrs) {
        super(context, attrs); guard = new PreviewFrameGuard(this);
    }
    @Override public void noteFrame(SurfaceTexture source) { guard.observe(source); }
    @Override public void resetFrames() { guard.reset(); }
    @Override protected void onAttachedToWindow() { super.onAttachedToWindow(); guard.attach(); }
    @Override protected void onDetachedFromWindow() { guard.detach(); super.onDetachedFromWindow(); }
    @Override protected void dispatchDraw(Canvas canvas) {
        super.dispatchDraw(canvas); guard.cover(canvas, false);
    }
}
