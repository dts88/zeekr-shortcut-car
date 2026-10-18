package com.kooo.evcam.settings;

import android.content.Context;
import android.os.Bundle;
import android.widget.FrameLayout;
import androidx.appcompat.app.AppCompatActivity;
import com.kooo.evcam.R;

/** Debug-only native host: uses Main's font context without opening cameras. */
public class TypographyTestHostActivity extends AppCompatActivity {
    @Override protected void attachBaseContext(Context base) {
        super.attachBaseContext(MainFontScale.wrap(base));
    }
    @Override protected void onCreate(Bundle saved) {
        setTheme(R.style.Theme_Cam); super.onCreate(saved);
        FrameLayout frame = new FrameLayout(this); frame.setId(R.id.fragment_container);
        setContentView(frame);
    }
}
