package com.kooo.evcam.zeekr;

import android.graphics.Canvas;
import android.graphics.Paint;

/**
 * 画面停了怎么显示：超级镜子和主界面预览同一套（项目所有者 2026-10-20：主界面预览也要盖，仍用 80% 半透明）。
 *
 * <p>TextureView 会一直留着最后一帧，于是相机断了之后画面里仍然是一幅<b>看起来像实时</b>的路面 —— 盯着一张过时的画面
 * 变道、倒车，比看见一块黑屏危险得多。所以停了就压暗、写一句。压暗而不是盖死：让人看得出底下是张画面，只是不再更新了。</p>
 *
 * <p>这一层盖的是摄像头画面，不是应用自己的界面，所以不跟日夜模式走：底下永远是视频，压暗 + 浅色字在两种模式下都读得清。</p>
 */
final class StalledPicture {

    /**
     * 多久没有新画面就不再拿它当实时画面看。
     *
     * <p>最省的档位也有 10fps，2.5 秒是二十多帧没来 —— 到这个地步已经不是卡顿，是这条流停了。改输出、重建会话、
     * 关座舱前先关环视那零点几秒到一秒的断帧都在它以内，不会一闪一闪地盖。</p>
     */
    static final long AFTER_MS = 2500L;
    /** 压暗那一层：80% 黑。 */
    static final int SCRIM = 0xCC000000;
    /** 字：近白。 */
    static final int TEXT = 0xFFF2F2F3;

    private StalledPicture() {
    }

    /**
     * 盖上去：这一块整块压暗，正中写 {@code hint}（空的不写）。字号随这一块的大小走，不超过 {@code maxTextPx}。
     */
    static void draw(Canvas canvas, Paint scrim, Paint text, float left, float top, float width, float height,
                     String hint, float maxTextPx) {
        scrim.setColor(SCRIM);
        canvas.drawRect(left, top, left + width, top + height, scrim);
        if (hint == null || hint.isEmpty()) {
            return;
        }
        text.setColor(TEXT);
        text.setTextAlign(Paint.Align.CENTER);
        text.setTextSize(Math.max(1f, Math.min(maxTextPx,
                Math.min(height * 0.17f, width / (hint.length() * 0.56f)))));
        Paint.FontMetrics fm = text.getFontMetrics();
        canvas.drawText(hint, left + width / 2f, top + height / 2f - (fm.ascent + fm.descent) / 2f, text);
    }
}
