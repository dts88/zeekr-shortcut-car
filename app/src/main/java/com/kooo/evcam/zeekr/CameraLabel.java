package com.kooo.evcam.zeekr;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;

import androidx.appcompat.app.AppCompatDelegate;

import com.kooo.evcam.R;
import com.kooo.evcam.camera.CameraNames;
import com.kooo.evcam.settings.Languages;

/** Camera direction badge, drawn after the camera's crop and mirror transforms. */
final class CameraLabel {
    private final Context context;
    private final Paint background = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    /** 0–3 是环视的前、后、左、右，{@link LaneCycle#CABIN_REAR} 是后座舱。 */
    private final String[] names = new String[LaneCycle.CABIN_REAR + 1];
    private String language;

    CameraLabel(Context context) {
        this.context = context;
        background.setColor(0xB3000000);
        text.setColor(0xFFFFFFFF);
        text.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
    }

    void draw(Canvas canvas, int lane, int width, int height) {
        if (lane < 0 || lane >= names.length || width <= 0 || height <= 0) {
            return;
        }
        String currentLanguage = AppCompatDelegate.getApplicationLocales().toLanguageTags()
                + ":" + context.getResources().getConfiguration().getLocales().toLanguageTags();
        if (!currentLanguage.equals(language)) {
            Context localized = Languages.localized(context);
            for (int i = 0; i < names.length; i++) {
                names[i] = i == LaneCycle.CABIN_REAR
                        ? localized.getString(R.string.slot_cabin_rear)
                        : CameraNames.ofLane(localized, i);
            }
            language = currentLanguage;
        }
        float density = context.getResources().getDisplayMetrics().density;
        float size = Math.min(16f * density, Math.min(width / 6f, height / 5f));
        text.setTextSize(size);
        float margin = size / 2f;
        float paddingX = size / 2f;
        float paddingY = size / 4f;
        float top = text.ascent();
        float badgeWidth = text.measureText(names[lane]) + 2f * paddingX;
        float badgeHeight = text.descent() - top + 2f * paddingY;
        float y = margin;
        canvas.drawRoundRect(margin, y, margin + badgeWidth, y + badgeHeight,
                paddingY, paddingY, background);
        canvas.drawText(names[lane], margin + paddingX, y + paddingY - top, text);
    }
}
