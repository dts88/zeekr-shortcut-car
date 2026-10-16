package com.kooo.evcam.zeekr;

import android.graphics.SurfaceTexture;

/** Main-screen containers receive source progression without replacing the native camera consumer. */
public interface LivePreview {
    void noteFrame(SurfaceTexture source);
    void resetFrames();
}
