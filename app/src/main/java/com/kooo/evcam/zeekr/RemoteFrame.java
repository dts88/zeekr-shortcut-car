package com.kooo.evcam.zeekr;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;

/**
 * Phone picture of the surround stream: four 16:9 cells in a 2×2 grid.
 *
 * <p>From scratch (2026-10-03): split the vertical strip with plain canvas crops.
 * No mesh unless fisheye correction is on. When lane windows are missing, fall back
 * to four equal vertical bands — never push the raw strip to the phone.</p>
 */
public final class RemoteFrame {

    /** One cell. 480×270 is 16:9, so the 2×2 frame is 960×540. */
    public static final int CELL_WIDTH = 480;
    public static final int CELL_HEIGHT = 270;

    /** Bitmap size for a surround grab. The short side has to cover one cell. */
    public static int[] grabSize(int bufferW, int bufferH) {
        int longest = Math.max(bufferW, bufferH);
        if (longest < 2 || bufferW < 2 || bufferH < 2) {
            return new int[]{0, 0};
        }
        float scale = 960f / longest;
        int w = Math.max(2, ((int) (bufferW * scale)) / 2 * 2);
        int h = Math.max(2, ((int) (bufferH * scale)) / 2 * 2);
        return new int[]{w, h};
    }

    private static final int DIVISIONS = 8;

    private final FisheyeMesh mesh = new FisheyeMesh();
    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Rect srcRect = new Rect();
    private final RectF dstRect = new RectF();
    private final float[] window = new float[4];
    private final float[] fallbackLanes = new float[CompositeStreamGeometry.LANE_COUNT * 4];
    private Bitmap frame;

    /**
     * Four equal vertical bands in stream order: front, rear, left, right.
     * Matches {@link StreamLayoutTable} (composite is always a vertical stack).
     */
    public static void equalVerticalLanes(float[] out) {
        if (out == null || out.length < CompositeStreamGeometry.LANE_COUNT * 4) {
            return;
        }
        for (int i = 0; i < CompositeStreamGeometry.LANE_COUNT; i++) {
            int at = i * 4;
            out[at] = 0f;
            out[at + 1] = i / 4f;
            out[at + 2] = 1f;
            out[at + 3] = (i + 1) / 4f;
        }
    }

    /** True when {@code lanes} has four non-empty windows. */
    public static boolean lanesUsable(float[] lanes) {
        if (lanes == null || lanes.length < CompositeStreamGeometry.LANE_COUNT * 4) {
            return false;
        }
        for (int i = 0; i < CompositeStreamGeometry.LANE_COUNT; i++) {
            int at = i * 4;
            if (lanes[at + 2] - lanes[at] < 0.01f || lanes[at + 3] - lanes[at + 1] < 0.01f) {
                return false;
            }
        }
        return true;
    }

    /**
     * Where a 16:9 cell samples the corrected square. Full width, a shorter
     * vertical span, so the fisheye is not stretched sideways.
     */
    public static void correctedWindow(float cellAspect, float[] out) {
        float aspect = cellAspect > 0.01f ? cellAspect : 1f;
        if (aspect >= 1f) {
            float span = 1f / aspect;
            out[0] = 0f;
            out[1] = (1f - span) / 2f;
            out[2] = 1f;
            out[3] = span;
        } else {
            out[0] = (1f - aspect) / 2f;
            out[1] = 0f;
            out[2] = aspect;
            out[3] = 1f;
        }
    }

    /** Phone source ch1–ch4 → stream lane 0–3. Anything else is the 2×2. */
    public static int laneForSource(String source) {
        if (source == null || source.length() != 3 || !source.startsWith("ch")) {
            return -1;
        }
        int n = source.charAt(2) - '1';
        return n >= 0 && n < CompositeStreamGeometry.LANE_COUNT ? n : -1;
    }

    /**
     * Draw into the reusable frame. {@code lanes} is 16 floats, stream order,
     * each lane {@code u0,v0,u1,v1}; null or unusable → equal vertical bands.
     * {@code order[cell]} is the stream lane for that cell. {@code onlyLane} 0–3
     * fills the whole frame with that one camera; anything else is the 2×2.
     * Does not recycle {@code source}.
     */
    public Bitmap compose(Bitmap source, float[] lanes, int[] order, int onlyLane,
                          boolean straighten, float fovDegrees, String projection, float strength) {
        int width = CELL_WIDTH * 2;
        int height = CELL_HEIGHT * 2;
        if (frame == null || frame.getWidth() != width || frame.getHeight() != height) {
            if (frame != null) {
                frame.recycle();
            }
            frame = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        }
        Canvas canvas = new Canvas(frame);
        canvas.drawColor(Color.BLACK);

        float[] use = lanes;
        if (!lanesUsable(use)) {
            equalVerticalLanes(fallbackLanes);
            use = fallbackLanes;
        }
        int[] cellOrder = order;
        if (cellOrder == null || cellOrder.length < CompositeStreamGeometry.LANE_COUNT) {
            cellOrder = new int[]{0, 1, 2, 3};
        }

        int sw = source.getWidth();
        int sh = source.getHeight();
        if (straighten) {
            mesh.setCorrection(fovDegrees, projection, strength);
            correctedWindow((float) CELL_WIDTH / CELL_HEIGHT, window);
            FisheyeMesh.Painter painter = c -> c.drawBitmap(source, 0f, 0f, paint);
            if (onlyLane >= 0 && onlyLane < CompositeStreamGeometry.LANE_COUNT) {
                drawLaneMesh(canvas, use, onlyLane, sw, sh, 0f, 0f, width, height, 12, painter);
                return frame;
            }
            for (int cell = 0; cell < CompositeStreamGeometry.LANE_COUNT; cell++) {
                int lane = cellOrder[cell];
                float dx = (cell % 2) * CELL_WIDTH;
                float dy = (cell / 2) * CELL_HEIGHT;
                drawLaneMesh(canvas, use, lane, sw, sh, dx, dy, CELL_WIDTH, CELL_HEIGHT, DIVISIONS, painter);
            }
            return frame;
        }

        if (onlyLane >= 0 && onlyLane < CompositeStreamGeometry.LANE_COUNT) {
            drawLaneCrop(canvas, source, use, onlyLane, 0f, 0f, width, height);
            return frame;
        }
        for (int cell = 0; cell < CompositeStreamGeometry.LANE_COUNT; cell++) {
            int lane = cellOrder[cell];
            float dx = (cell % 2) * CELL_WIDTH;
            float dy = (cell / 2) * CELL_HEIGHT;
            drawLaneCrop(canvas, source, use, lane, dx, dy, CELL_WIDTH, CELL_HEIGHT);
        }
        return frame;
    }

    /** Center-crop one lane band into a 16:9 cell. */
    private void drawLaneCrop(Canvas canvas, Bitmap source, float[] lanes, int lane,
                              float dx, float dy, float dw, float dh) {
        if (lane < 0 || lane >= CompositeStreamGeometry.LANE_COUNT) {
            return;
        }
        int sw = source.getWidth();
        int sh = source.getHeight();
        int at = lane * 4;
        float laneL = lanes[at] * sw;
        float laneT = lanes[at + 1] * sh;
        float laneW = (lanes[at + 2] - lanes[at]) * sw;
        float laneH = (lanes[at + 3] - lanes[at + 1]) * sh;
        if (laneW < 2f || laneH < 2f) {
            return;
        }
        float srcAspect = laneW / laneH;
        float dstAspect = dw / dh;
        float left;
        float top;
        float right;
        float bottom;
        if (srcAspect > dstAspect) {
            float useW = laneH * dstAspect;
            left = laneL + (laneW - useW) / 2f;
            top = laneT;
            right = left + useW;
            bottom = laneT + laneH;
        } else {
            float useH = laneW / dstAspect;
            left = laneL;
            top = laneT + (laneH - useH) / 2f;
            right = laneL + laneW;
            bottom = top + useH;
        }
        srcRect.set(Math.round(left), Math.round(top), Math.round(right), Math.round(bottom));
        dstRect.set(dx, dy, dx + dw, dy + dh);
        canvas.drawBitmap(source, srcRect, dstRect, paint);
    }

    private void drawLaneMesh(Canvas canvas, float[] lanes, int lane, int sw, int sh,
                              float dx, float dy, float dw, float dh, int divisions,
                              FisheyeMesh.Painter painter) {
        if (lane < 0 || lane >= CompositeStreamGeometry.LANE_COUNT) {
            return;
        }
        int at = lane * 4;
        float left = lanes[at] * sw;
        float top = lanes[at + 1] * sh;
        float laneWidth = (lanes[at + 2] - lanes[at]) * sw;
        float laneHeight = (lanes[at + 3] - lanes[at + 1]) * sh;
        if (laneWidth < 2f || laneHeight < 2f) {
            return;
        }
        mesh.prepare(divisions, left, top, laneWidth, laneHeight,
                window[0], window[1], window[2], window[3]);
        mesh.draw(canvas, dx, dy, dw, dh, painter);
    }
}
