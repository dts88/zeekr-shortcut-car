package com.kooo.evcam.zeekr;
import android.content.Context;
import android.graphics.*;
import android.view.View;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.kooo.evcam.settings.AppTheme;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.lang.reflect.*;
import static org.junit.Assert.*;
@RunWith(AndroidJUnit4.class)
public class ThemeWindowControlAndroidTest {
    @Test public void existingCloseControlRepaintsWithoutReplacingItsView() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{
            int original=AppCompatDelegate.getDefaultNightMode();
            try {
                Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
                Class<?> type=Class.forName("com.kooo.evcam.zeekr.SideViewWindow$CloseButton");
                Constructor<?> constructor=type.getDeclaredConstructor(Context.class);constructor.setAccessible(true);
                View button=(View)constructor.newInstance(context);button.layout(0,0,100,100);
                Field fill=type.getDeclaredField("fill");fill.setAccessible(true);
                Bitmap bitmap=Bitmap.createBitmap(100,100,Bitmap.Config.ARGB_8888);Canvas canvas=new Canvas(bitmap);
                AppTheme.apply(AppTheme.LIGHT);button.draw(canvas);int light=((Paint)fill.get(button)).getColor();
                AppTheme.apply(AppTheme.DARK);button.draw(canvas);int dark=((Paint)fill.get(button)).getColor();
                assertNotEquals(light,dark);assertTrue(Color.red(light)>Color.red(dark));
                assertNotNull(button.getContentDescription());bitmap.recycle();
            } catch(Exception e){throw new AssertionError(e);}
            finally {AppCompatDelegate.setDefaultNightMode(original);}
        });
    }
}
