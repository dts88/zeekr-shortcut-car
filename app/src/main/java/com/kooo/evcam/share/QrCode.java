package com.kooo.evcam.share;

import android.graphics.Bitmap;
import android.graphics.Color;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;

import com.kooo.evcam.AppLog;

import java.util.EnumMap;
import java.util.Map;

/**
 * 把一串地址画成二维码。
 *
 * <p>沿用上游 EVCam 的做法（ZXing）。这里只做一件事，所以没有做成一个类的实例。</p>
 */
public final class QrCode {

    private static final String TAG = "QrCode";

    private QrCode() {
    }

    /**
     * @param content 要编码的内容，这里是一个 http:// 地址
     * @param sizePx  正方形边长
     * @return 编码失败时返回 null —— 让调用方决定怎么提示，不在这里吞掉
     */
    public static Bitmap encode(String content, int sizePx) {
        if (content == null || content.isEmpty() || sizePx <= 0) {
            return null;
        }
        try {
            Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
            hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
            // 车机屏幕反光、手机举着也不稳，纠错级别高一点扫得快
            hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M);
            hints.put(EncodeHintType.MARGIN, 2);

            BitMatrix matrix = new QRCodeWriter()
                    .encode(content, BarcodeFormat.QR_CODE, sizePx, sizePx, hints);

            int[] pixels = new int[sizePx * sizePx];
            for (int y = 0; y < sizePx; y++) {
                int offset = y * sizePx;
                for (int x = 0; x < sizePx; x++) {
                    pixels[offset + x] = matrix.get(x, y) ? Color.BLACK : Color.WHITE;
                }
            }
            Bitmap bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888);
            // 整块 setPixels，不是逐点 setPixel —— 480×480 是二十多万次调用
            bitmap.setPixels(pixels, 0, sizePx, 0, 0, sizePx, sizePx);
            return bitmap;
        } catch (Exception e) {
            AppLog.e(TAG, "二维码生成失败", e);
            return null;
        }
    }
}
