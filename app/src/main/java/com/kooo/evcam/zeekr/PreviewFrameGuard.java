package com.kooo.evcam.zeekr;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.SurfaceTexture;
import android.os.SystemClock;
import android.view.View;
import com.kooo.evcam.R;

/** Draw the native texture first, then cover missing/stalled pictures with opaque black. */
final class PreviewFrameGuard {
    private final View owner;
    private final CameraFrameFreshness frames = new CameraFrameFreshness();
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final String waiting;
    private boolean attached;
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!attached) return;
            if (!owner.isShown()) frames.reset();
            else if (!frames.fresh(SystemClock.elapsedRealtime())) owner.invalidate();
            owner.postDelayed(this, 100);
        }
    };
    PreviewFrameGuard(View owner) {
        this.owner = owner;
        waiting = owner.getContext().getString(R.string.camera_waiting_live);
        text.setColor(Color.WHITE);
        text.setTextAlign(Paint.Align.CENTER);
        text.setTextSize(owner.getResources().getDimension(R.dimen.text_caption));
    }
    void attach() { attached = true; reset(); owner.removeCallbacks(tick); owner.post(tick); }
    void detach() { attached = false; owner.removeCallbacks(tick); reset(); }
    void reset() { frames.reset(); owner.invalidate(); }
    void observe(SurfaceTexture source) {
        if (!attached || !owner.isShown()) { reset(); return; }
        try { frames.observe(source, source.getTimestamp(), SystemClock.elapsedRealtime()); }
        catch (RuntimeException unavailable) { frames.reset(); }
        owner.invalidate();
    }
    void cover(Canvas canvas, boolean force) {
        if (!force && frames.fresh(SystemClock.elapsedRealtime())) return;
        canvas.drawColor(Color.BLACK);
        float baseline = owner.getHeight() / 2f - (text.ascent() + text.descent()) / 2f;
        canvas.drawText(waiting, owner.getWidth() / 2f, baseline, text);
    }
}
