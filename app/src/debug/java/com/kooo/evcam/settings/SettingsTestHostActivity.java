package com.kooo.evcam.settings;

import android.os.Bundle;
import android.widget.FrameLayout;
import androidx.appcompat.app.AppCompatActivity;
import com.kooo.evcam.R;

/** Debug-only settings host: no vehicle, camera or startup permission flow. */
public class SettingsTestHostActivity extends AppCompatActivity {
    @Override protected void onCreate(Bundle saved) {
        setTheme(R.style.Theme_Cam);
        super.onCreate(saved);
        FrameLayout frame = new FrameLayout(this); frame.setId(R.id.fragment_container);
        setContentView(frame);
        if(saved==null) getSupportFragmentManager().beginTransaction()
                .replace(R.id.fragment_container,new SettingsShellFragment()).commitNow();
    }
}
