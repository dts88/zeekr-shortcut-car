/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.alarm;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;

import com.kooo.evcam.AppLog;
import com.kooo.evcam.StorageHelper;
import com.kooo.evcam.camera.CameraKeeper;
import com.kooo.evcam.camera.CameraSlots;
import com.kooo.evcam.camera.MultiCameraManager;

import java.io.ByteArrayOutputStream;
import java.io.File;

/**
 * 拍一张四个摄像头的照片，压成能发出去的 JPEG。
 *
 * <h3>为什么借拍照那条路</h3>
 *
 * <p>环视是四条画面拼成的一条长条，重排成 2×2 是在存盘那一步做的
 * （{@code CompositeBitmapComposer}）。拍照这条路本来就走完了一整套：按快门、切分、重排、盖角标、
 * 写盘，而且<b>不碰正在跑的录像</b> —— 相机不重开，编码不重启。自己另起一条取帧的路，
 * 等于把这一整套再写一遍，还要和录像抢同一批相机。</p>
 *
 * <h3>文件是猜出来的，不是拿到的</h3>
 *
 * <p>{@link MultiCameraManager.PhotoCallback#onPhotoResult} 只回报「存了几路」，不给文件名。
 * 文件名是 {@code <时间戳>_<后缀>.jpg}、后缀固定（{@link CameraSlots#suffixFor(String)}），
 * 所以存完在照片目录里找<b>这一趟新写下的</b>那张环视图 —— 时间戳由拍照那一刻决定，
 * 比按修改时间宽泛地「找最新的」靠谱。</p>
 */
public final class SnapshotTaker {

    private static final String TAG = "SnapshotTaker";

    /** 发出去的照片长边上限：1280 够看清人和车，也稳在 Telegram 的尺寸里。 */
    private static final int MAX_WIDTH = 1280;

    private static final int JPEG_QUALITY = 80;

    /** 拍照写盘和我们的回调之间允许的时间差（毫秒）：找文件时往前多看一点。 */
    private static final long FILE_SLACK_MS = 5_000L;

    /** 结果。拿到的字节已经能直接发。 */
    public interface Callback {
        /** @param jpeg 压好的 JPEG；不会为空 */
        void onPicture(byte[] jpeg);

        /** @param reason 一句短话，可以显示给车主 */
        void onFailed(String reason);
    }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private SnapshotTaker() {
    }

    /**
     * 拍一张。可以在任何线程调；结果回在主线程。
     *
     * <p>拍照本身要主线程（{@link CameraKeeper#takePhoto} 会自己转过去），
     * 找文件和压缩挪到后台线程 —— 一张 2×2 的解码加压缩是几十毫秒的事，
     * 但没必要占着主线程。</p>
     */
    public static void take(Context context, Callback callback) {
        if (context == null || callback == null) {
            return;
        }
        Context app = context.getApplicationContext();
        final long startedAt = System.currentTimeMillis();
        final File photoDir = StorageHelper.getPhotoDir(app);
        if (photoDir == null) {
            callback.onFailed("no storage");
            return;
        }
        MAIN.post(() -> {
            CameraKeeper.get(app).takePhoto(new MultiCameraManager.PhotoCallback() {
                @Override
                public void onPhotoResult(int saved, int pressed, int expected) {
                    if (saved <= 0) {
                        callback.onFailed("no picture (0 of " + expected + " saved)");
                        return;
                    }
                    new Thread(() -> finish(photoDir, startedAt, callback), "alert-snapshot").start();
                }

                @Override
                public void onNoStorage() {
                    callback.onFailed("no storage");
                }

                @Override
                public void onNoCameraEnabled() {
                    callback.onFailed("no camera enabled");
                }
            });
        });
    }

    private static void finish(File photoDir, long startedAt, Callback callback) {
        File file = newestSurroundPhoto(photoDir, startedAt);
        if (file == null) {
            callback.onFailed("picture not found");
            return;
        }
        byte[] jpeg = compress(file);
        if (jpeg == null || jpeg.length == 0) {
            callback.onFailed("picture unreadable");
            return;
        }
        MAIN.post(() -> callback.onPicture(jpeg));
    }

    /** 这一趟新写下的环视图。名字里带时间戳，按名字取最大的那张就是最新的。 */
    private static File newestSurroundPhoto(File dir, long startedAt) {
        String suffix = "_" + CameraSlots.suffixFor(CameraSlots.KEY_SURROUND) + ".jpg";
        File[] files = dir.listFiles();
        if (files == null) {
            return null;
        }
        File newest = null;
        for (File file : files) {
            if (file == null || !file.isFile() || !file.getName().endsWith(suffix)) {
                continue;
            }
            if (file.lastModified() < startedAt - FILE_SLACK_MS) {
                continue;
            }
            if (newest == null || file.getName().compareTo(newest.getName()) > 0) {
                newest = file;
            }
        }
        return newest;
    }

    /** 解码到合适大小再压：先按 2 的幂降采样，避免整张大图进内存。 */
    private static byte[] compress(File file) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            return null;
        }
        int sample = 1;
        while (bounds.outWidth / (sample * 2) >= MAX_WIDTH) {
            sample *= 2;
        }
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sample;
        Bitmap bitmap = null;
        Bitmap scaled = null;
        try {
            bitmap = BitmapFactory.decodeFile(file.getAbsolutePath(), options);
            if (bitmap == null) {
                return null;
            }
            int width = bitmap.getWidth();
            int height = bitmap.getHeight();
            if (width > MAX_WIDTH) {
                int targetHeight = Math.max(1, Math.round(height * (MAX_WIDTH / (float) width)));
                scaled = Bitmap.createScaledBitmap(bitmap, MAX_WIDTH, targetHeight, true);
            }
            Bitmap out = scaled != null ? scaled : bitmap;
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            out.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, bytes);
            return bytes.toByteArray();
        } catch (RuntimeException | OutOfMemoryError e) {
            AppLog.w(TAG, "compress failed: " + e);
            return null;
        } finally {
            if (scaled != null) {
                scaled.recycle();
            }
            if (bitmap != null) {
                bitmap.recycle();
            }
        }
    }
}
