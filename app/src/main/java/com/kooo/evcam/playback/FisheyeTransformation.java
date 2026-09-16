package com.kooo.evcam.playback;

import android.graphics.Bitmap;

import androidx.annotation.NonNull;

import com.bumptech.glide.load.Key;
import com.bumptech.glide.load.engine.bitmap_recycle.BitmapPool;
import com.bumptech.glide.load.resource.bitmap.BitmapTransformation;
import com.kooo.evcam.zeekr.FisheyeCorrector;
import com.kooo.evcam.zeekr.FisheyeProjection;

import java.nio.ByteBuffer;
import java.security.MessageDigest;

/**
 * 回看界面上的鱼眼校正，做成 Glide 的一个变换。
 *
 * <h3>为什么走 Glide 而不是自己在 ImageView 上画</h3>
 *
 * <p>校正后的图和原图是两张不同的图，而 Glide 的缓存键里带着变换的参数 ——
 * 开关一拨就是换一个键，两份各自缓存，来回切不用重算。自己画的话，
 * 这套缓存要重写一遍，而且开关状态和已经贴在 ImageView 上的图很容易对不上。</p>
 *
 * <p>只影响显示：U 盘里的原图一个字节都不动。</p>
 */
public class FisheyeTransformation extends BitmapTransformation {

    /** 改了校正的算法就改这个版本号，否则磁盘上的旧图会被当成新的用。 */
    private static final String ID = "com.kooo.evcam.playback.FisheyeTransformation.4";
    private static final byte[] ID_BYTES = ID.getBytes(Key.CHARSET);

    private final int columns;
    private final int rows;
    private final float fovDegrees;
    private final String projection;

    /**
     * @param columns 这张图横向排了几路（环视合成图是 2）
     * @param rows    纵向几路（环视合成图是 2）
     */
    public FisheyeTransformation(int columns, int rows) {
        this(columns, rows, FisheyeProjection.PHOTO_FOV_DEGREES,
                FisheyeProjection.PROJECTION_RECTILINEAR);
    }

    /** 视野角度的含义和后视镜那一项完全一样；投影方式决定直线掰得多直、画面留下多少。 */
    public FisheyeTransformation(int columns, int rows, float fovDegrees, String projection) {
        this.columns = columns;
        this.rows = rows;
        this.projection = FisheyeProjection.PROJECTION_CYLINDRICAL.equals(projection)
                ? FisheyeProjection.PROJECTION_CYLINDRICAL
                : FisheyeProjection.PROJECTION_RECTILINEAR;
        this.fovDegrees = FisheyeProjection.clampFov(fovDegrees, this.projection);
    }

    @Override
    protected Bitmap transform(@NonNull BitmapPool pool, @NonNull Bitmap toTransform,
                               int outWidth, int outHeight) {
        return FisheyeCorrector.correctGrid(toTransform, columns, rows, fovDegrees,
                projection);
    }

    @Override
    public void updateDiskCacheKey(@NonNull MessageDigest messageDigest) {
        messageDigest.update(ID_BYTES);
        messageDigest.update(ByteBuffer.allocate(12)
                .putInt(columns).putInt(rows).putFloat(fovDegrees).array());
        messageDigest.update(projection.getBytes(Key.CHARSET));
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof FisheyeTransformation)) {
            return false;
        }
        FisheyeTransformation that = (FisheyeTransformation) other;
        return columns == that.columns && rows == that.rows
                && Float.compare(fovDegrees, that.fovDegrees) == 0
                && projection.equals(that.projection);
    }

    @Override
    public int hashCode() {
        return (((ID.hashCode() * 31 + columns) * 31 + rows) * 31
                + Float.floatToIntBits(fovDegrees)) * 31 + projection.hashCode();
    }
}
