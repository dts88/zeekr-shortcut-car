package com.kooo.evcam.heartbeat;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.util.Size;
import android.view.TextureView;

import com.kooo.evcam.AppLog;
import com.kooo.evcam.camera.SingleCamera;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 心跳推图图片处理器
 * 负责从摄像头获取图片、拼接和压缩
 */
public class HeartbeatImageProcessor {
    private static final String TAG = "HeartbeatImageProcessor";
    
    /**
     * 从多个相机获取实时画面并拼接
     * 
     * @param cameras SingleCamera 列表
     * @return 拼接后的 Bitmap（调用方负责回收），失败返回 null
     */
    public Bitmap captureAndMerge(List<SingleCamera> cameras) {
        if (cameras == null || cameras.isEmpty()) {
            AppLog.w(TAG, "相机列表为空");
            return null;
        }
        
        List<Bitmap> bitmaps = new ArrayList<>();
        
        for (SingleCamera camera : cameras) {
            if (camera == null) {
                continue;
            }
            
            try {
                Bitmap bitmap = captureSingleCamera(camera);
                if (bitmap != null) {
                    bitmaps.add(bitmap);
                }
            } catch (Exception e) {
                AppLog.e(TAG, "获取相机画面失败: " + e.getMessage());
            }
        }
        
        if (bitmaps.isEmpty()) {
            AppLog.w(TAG, "未能获取任何相机画面");
            return null;
        }
        
        AppLog.d(TAG, "成功获取 " + bitmaps.size() + " 个相机画面");
        
        // 拼接图片
        Bitmap merged = mergeBitmaps(bitmaps);
        
        // 回收原始 bitmap（拼接后不再需要）
        for (Bitmap bitmap : bitmaps) {
            if (bitmap != null && !bitmap.isRecycled()) {
                bitmap.recycle();
            }
        }
        
        return merged;
    }
    
    /**
     * 从单个相机获取画面
     */
    private Bitmap captureSingleCamera(SingleCamera camera) {
        // 获取 TextureView（通过反射或公开方法）
        // 由于 SingleCamera 类的设计，我们需要在主线程获取 Bitmap
        // 这里假设 camera 有提供获取 Bitmap 的方法
        
        Size previewSize = camera.getPreviewSize();
        if (previewSize == null) {
            AppLog.w(TAG, "相机 " + camera.getCameraId() + " 预览尺寸未知");
            return null;
        }
        
        // 使用 SingleCamera 内部的方法获取 Bitmap
        // 注意：这需要在 SingleCamera 中添加一个公开方法
        return camera.captureBitmap();
    }
    
    /**
     * 拼接 Bitmap
     * - 1张：原图
     * - 2张：横向拼接 (W*2, H)
     * - 3-4张：四宫格 (W*2, H*2)
     * 
     * @param bitmaps Bitmap 列表
     * @return 拼接后的 Bitmap
     */
    private Bitmap mergeBitmaps(List<Bitmap> bitmaps) {
        int count = bitmaps.size();
        Bitmap first = bitmaps.get(0);
        int w = first.getWidth();
        int h = first.getHeight();
        
        AppLog.d(TAG, "拼接 " + count + " 张图片，单张尺寸: " + w + "x" + h);
        
        if (count == 1) {
            // 单张图片，直接复制返回
            return first.copy(Bitmap.Config.ARGB_8888, false);
        }
        
        if (count == 2) {
            // 横向拼接
            Bitmap result = Bitmap.createBitmap(w * 2, h, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(result);
            canvas.drawBitmap(bitmaps.get(0), 0, 0, null);
            canvas.drawBitmap(bitmaps.get(1), w, 0, null);
            AppLog.d(TAG, "横向拼接完成，尺寸: " + (w * 2) + "x" + h);
            return result;
        }
        
        // 四宫格 (3或4张)
        Bitmap result = Bitmap.createBitmap(w * 2, h * 2, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(result);
        canvas.drawColor(Color.BLACK); // 背景色（3摄时右下角填黑）
        
        // 左上
        canvas.drawBitmap(bitmaps.get(0), 0, 0, null);
        // 右上
        canvas.drawBitmap(bitmaps.get(1), w, 0, null);
        // 左下
        canvas.drawBitmap(bitmaps.get(2), 0, h, null);
        // 右下（如果有第4张）
        if (count >= 4) {
            canvas.drawBitmap(bitmaps.get(3), w, h, null);
        }
        
        AppLog.d(TAG, "四宫格拼接完成，尺寸: " + (w * 2) + "x" + (h * 2));
        return result;
    }
    
    /**
     * 压缩 Bitmap 到目标大小
     * 使用二分法动态调整 JPEG 质量
     * 
     * @param bitmap 原图
     * @param targetSizeKB 目标大小（KB），0 表示不压缩
     * @return 压缩后的 byte[]
     */
    public byte[] compressToTargetSize(Bitmap bitmap, int targetSizeKB) {
        if (bitmap == null) {
            return null;
        }
        
        // 不压缩：使用 95% 质量
        if (targetSizeKB <= 0) {
            AppLog.d(TAG, "不压缩模式，使用 95% 质量");
            return compressWithQuality(bitmap, 95);
        }
        
        int targetSizeBytes = targetSizeKB * 1024;
        int minQualityLimit = 10;  // 最低质量限制
        int minQuality = minQualityLimit;
        int maxQuality = 95;
        int quality = 70; // 初始质量
        byte[] result = null;
        int iterations = 0;
        int maxIterations = 6;  // 最多6次迭代，足够覆盖10-95范围
        
        // 二分法查找最佳质量
        while (minQuality <= maxQuality && iterations < maxIterations) {
            iterations++;
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, baos);
            result = baos.toByteArray();
            
            int currentSize = result.length;
            int currentSizeKB = currentSize / 1024;
            
            // 容差：目标的 20% 或 20KB，取较大值，让它更早收敛
            int tolerance = Math.max(20, targetSizeKB / 5);
            if (Math.abs(currentSizeKB - targetSizeKB) <= tolerance) {
                AppLog.d(TAG, "压缩完成: 质量=" + quality + ", 大小=" + currentSizeKB + "KB (目标=" + targetSizeKB + "KB), 迭代=" + iterations);
                break;
            }
            
            // 如果已经到最低质量，就不再降了
            if (quality <= minQualityLimit) {
                AppLog.d(TAG, "已达最低质量 " + minQualityLimit + "%, 大小=" + currentSizeKB + "KB (目标=" + targetSizeKB + "KB)");
                break;
            }
            
            if (currentSize > targetSizeBytes) {
                maxQuality = quality - 1;
            } else {
                minQuality = quality + 1;
            }
            quality = Math.max(minQualityLimit, (minQuality + maxQuality) / 2);
        }
        
        if (result != null) {
            AppLog.d(TAG, "最终压缩结果: " + (result.length / 1024) + "KB, 迭代次数: " + iterations);
        }
        
        return result;
    }
    
    /**
     * 压缩 Bitmap 到指定质量
     * 
     * @param bitmap 原图
     * @param quality JPEG 质量 (0-100)
     * @return 压缩后的 byte[]
     */
    public byte[] compressWithQuality(Bitmap bitmap, int quality) {
        if (bitmap == null) {
            return null;
        }
        
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, baos);
        return baos.toByteArray();
    }
}
